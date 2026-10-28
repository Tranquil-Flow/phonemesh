package org.phonemesh.core.update

import org.phonemesh.core.PackInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * What the Updates screen shows and does (hub updates spec 5.8; decisions 45 and 62), with no Android in it.
 *
 * - [refresh] asks the enrolled hub for its signed catalog (through [UpdateDownloader.refresh]) and lists the packs the phone
 *   already has that the catalog offers newer. It never copies a pack.
 * - A pack is copied only when the person asks, one pack per [download] call ("always asks", decision 62): there is no timer,
 *   no automatic start after a check, no "download everything". [pause] keeps the bytes; [download] again resumes.
 * - The only way out is [transport] (the hub's own Wi-Fi, pinned); when it is null nothing is attempted and the person is told to
 *   join the hub's Wi-Fi. Copies run one at a time; [refresh] may run beside them.
 */
class UpdateController(
    private val trust: UpdateTrust,
    private val activation: PackActivation,
    private val work: File,
    private val transport: () -> HubTransport?,
    /** Installed packs by id with their versions: only these are offered an update. */
    private val installed: () -> Map<String, Long>,
    private val appVersion: Long,
    private val sdk: Int,
    private val executor: Executor,
    private val open: (PackInfo) -> Boolean = { true },
    private val downloaderFor: (HubTransport, File) -> UpdateDownloader = { hub, dir -> UpdateDownloader(trust, hub, dir) },
    /** What an installed pack takes on disk (it stays beside its replacement until that opens). */
    private val installedBytes: (String) -> Long = { 0L },
    /** Milliseconds since 1970: only to tell whether the held list is past its review date. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Called after every check that may have changed what is trusted (a new list can revoke an installed pack). */
    private val onTrustChanged: () -> Unit = {},
) {
    /** Why a copy stopped; the screen turns each into one plain sentence beside the action. */
    enum class Why { HUB_GONE, NO_SPACE, NOT_TRUSTED, BUSY, REFUSED, OPEN_FAILED, NOT_SAVED, NOT_ON_HUB, NOT_OFFERED }

    sealed class State {
        /** Offered: nothing happens until the person taps Download. */
        object Ready : State()
        /** Asked for, behind another copy. */
        object Waiting : State()
        class Copying(val done: Long, val total: Long) : State()
        class Paused(val done: Long, val total: Long) : State()
        /** Stopped by the connection or by repeated failures; the bytes are kept and Resume goes on. */
        class Stopped(val why: Why, val done: Long, val total: Long) : State()
        /** Whole file in hand: hashing and installing. */
        object Installing : State()
        object Done : State()
        class Failed(val why: Why) : State()
    }

    class Row(val key: String, val kind: Int, val id: String, val label: String, val version: Long, val installed: Long, val bytes: Long, val state: State)

    /** The one sentence about the hub itself, apart from rows. */
    enum class Notice {
        NONE, NO_WIFI, CHECKING, NO_UPDATES,
        /** The hub's list is the one held or a newer one, and nothing is offered. */
        UP_TO_DATE,
        /** The hub gave an older list than the one held: nothing newer here (and nothing to say about the world). */
        HUB_OLDER,
        UNREACHABLE, LIST_NOT_TRUSTED, NEEDS_NEWER_APP, SAVE_FAILED,
    }

    /**
     * [listIssuedAt]: when the held list was made, seconds since 1970 (0 when none). [listOld]: the clock is plausible and past
     * the list's review date (advisory only). [revoked]: ids of installed packs the held list withdraws.
     */
    class View(val notice: Notice, val rows: List<Row>, val listIssuedAt: Long = 0, val listOld: Boolean = false, val revoked: List<String> = emptyList()) {
        val busy: Boolean get() = notice == Notice.CHECKING || rows.any { it.state is State.Copying || it.state is State.Installing || it.state == State.Waiting }
    }

    private class Item(val offer: UpdateTrust.Offer, val installed: Long, var state: State)

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private var notice = Notice.NONE
    private val stop = HashSet<String>()
    private val queue = ArrayDeque<String>()
    private var draining = false
    /** Called on whichever thread changed something; screens hop to their own. */
    @Volatile var onChange: (() -> Unit)? = null

    fun view(): View = synchronized(lock) { snapshot() }

    /** How many verified offers (from the catalog already accepted, no network) the phone holds for packs it has. */
    fun pending(): Int { val have = installed(); return trust.offers(appVersion, sdk, have).count { it.entry.id in have } }

    private fun snapshot(): View {
        val held = trust.catalog()
        val clock = now()
        return View(notice, items.map { (k, i) ->
            val e = i.offer.entry
            Row(k, e.kind, e.id, e.label, e.version, i.installed, e.bytes, i.state)
        }, listIssuedAt = held?.issuedAt ?: 0L, listOld = held != null && clock >= PLAUSIBLE_CLOCK_MILLIS && clock / 1000 > held.reviewAfter,
            revoked = trust.revokedInstalled())
    }

    private fun changed() { onChange?.invoke() }
    private fun key(e: UpdateMetadata.Entry) = "${e.kind}/${e.id}"

    // ---- looking ----

    /** Asks the hub, off the calling thread. Never copies a pack. */
    fun refresh() {
        synchronized(lock) { notice = if (transport() == null) Notice.NO_WIFI else Notice.CHECKING }
        changed()
        if (synchronized(lock) { notice } == Notice.NO_WIFI) return
        executor.execute { try { check() } catch (e: RuntimeException) { finishCheck(Notice.UNREACHABLE) } }
    }

    private fun check() {
        val hub = transport() ?: return finishCheck(Notice.NO_WIFI)
        val result = downloaderFor(hub, work).refresh()
        val note = when (result) {
            is UpdateDownloader.Refresh.NoUpdates -> Notice.NO_UPDATES
            is UpdateDownloader.Refresh.Failed -> Notice.UNREACHABLE
            is UpdateDownloader.Refresh.Updated -> {
                val c = result.catalog; val r = result.recovery
                when {
                    c is UpdateTrust.CatalogResult.Refused && c.reason == UpdateTrust.Reason.NEEDS_NEWER_APP -> Notice.NEEDS_NEWER_APP
                    c is UpdateTrust.CatalogResult.Refused && c.reason == UpdateTrust.Reason.STORE_FAILED -> Notice.SAVE_FAILED
                    c is UpdateTrust.CatalogResult.Refused && c.reason in LIST_REFUSED -> Notice.LIST_NOT_TRUSTED
                    r is UpdateTrust.RecoveryResult.Refused && r.reason in LIST_REFUSED -> Notice.LIST_NOT_TRUSTED
                    // An older list than the one held says nothing about what is new (U7-9).
                    c is UpdateTrust.CatalogResult.Refused && c.reason == UpdateTrust.Reason.OLDER -> Notice.HUB_OLDER
                    else -> Notice.NONE
                }
            }
        }
        finishCheck(note)
        runCatching { sweep() }
        runCatching { onTrustChanged() }
    }

    /**
     * Removes copies nobody can use any more (U7-15): a finished or partial file whose digest the held list no longer offers
     * (or revokes), and stray temporary files. A copy in progress for a listed pack stays.
     */
    private fun sweep() {
        val catalog = trust.catalog()
        val wanted = catalog?.entries?.filter { e -> catalog.revoked.none { it.contentEquals(e.digest) } }?.map { hex(it.digest) }?.toSet() ?: emptySet()
        val busyNames = synchronized(lock) { items.values.filter { it.state.keepsRow() && it.state !is State.Done }.map { hex(it.offer.entry.digest) }.toSet() }
        for (sub in listOf("ready", "partial")) {
            File(work, sub).listFiles().orEmpty().forEach { f ->
                val name = f.name.removePrefix(".").substringBefore('.')
                val tmp = f.name.startsWith(".") && f.name.endsWith(".tmp")
                if ((name !in wanted && name !in busyNames) || (tmp && name !in busyNames)) f.delete()
            }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun finishCheck(note: Notice) {
        synchronized(lock) {
            val have = installed()
            val offers = trust.offers(appVersion, sdk, have).filter { it.entry.id in have }
            val fresh = LinkedHashMap<String, Item>()
            for (o in offers) {
                val k = key(o.entry)
                val old = items[k]
                // A copy in progress or finished keeps its row; any other row is rebuilt from what is offered now.
                fresh[k] = if (old != null && old.offer.entry.version == o.entry.version && old.state.keepsRow()) old else Item(o, have[o.entry.id] ?: 0L, State.Ready)
            }
            items.entries.filter { it.key !in fresh && it.value.state.keepsRow() && it.value.state !is State.Done }.forEach { fresh[it.key] = it.value }
            items.clear(); items.putAll(fresh)
            notice = if (note == Notice.NONE && items.isEmpty()) Notice.UP_TO_DATE
                else if ((note == Notice.NO_UPDATES || note == Notice.HUB_OLDER) && items.isNotEmpty()) Notice.NONE else note
        }
        changed()
    }

    private fun State.keepsRow() = this is State.Copying || this is State.Waiting || this is State.Installing || this is State.Paused || this is State.Stopped || this is State.Done

    // ---- asking ----

    /** The person tapped Download or Resume on [key]. Returns false when that row cannot be copied now. */
    fun download(key: String): Boolean {
        synchronized(lock) {
            val item = items[key] ?: return false
            val s = item.state
            val allowed = s == State.Ready || s is State.Paused || s is State.Stopped || (s is State.Failed && s.why != Why.NOT_OFFERED)
            if (!allowed) return false
            stop.remove(key)
            item.state = State.Waiting
            queue.addLast(key)
            if (!draining) { draining = true; executor.execute { drain() } }
        }
        changed()
        return true
    }

    /** Pause on [key]: the bytes stay. Also takes a waiting row out of the line. */
    fun pause(key: String) {
        synchronized(lock) {
            val item = items[key] ?: return
            when (item.state) {
                State.Waiting -> { stop.add(key); queue.remove(key); item.state = State.Paused(0, item.offer.entry.bytes) }
                is State.Copying -> stop.add(key)
                else -> return
            }
        }
        changed()
    }

    /** The app leaves the foreground: nothing keeps copying (spec 5.5, first version). */
    fun pauseAll() {
        val keys = synchronized(lock) { items.filter { it.value.state is State.Copying || it.value.state == State.Waiting }.keys.toList() }
        keys.forEach { pause(it) }
    }

    private fun drain() {
        while (true) {
            val k = synchronized(lock) { queue.removeFirstOrNull().also { if (it == null) draining = false } } ?: return
            try { copy(k) } catch (e: RuntimeException) { synchronized(lock) { items[k] }?.let { set(it, State.Failed(Why.REFUSED)) } }
        }
    }

    /** Sets [item]'s state; a row that was replaced by a newer offer meanwhile is no longer shown and takes nothing from this copy. */
    private fun set(item: Item, s: State) { synchronized(lock) { item.state = s }; changed() }

    private fun copy(k: String) {
        val item = synchronized(lock) { items[k]?.takeIf { it.state == State.Waiting } } ?: return
        val offer = item.offer; val total = offer.entry.bytes
        if (synchronized(lock) { k in stop }) { set(item, State.Paused(0, total)); return }
        val hub = transport()
        if (hub == null) { set(item, State.Stopped(Why.HUB_GONE, 0, total)); return }
        set(item, State.Copying(0, total))
        val result = try {
            downloaderFor(hub, work).download(offer, { done, all -> set(item, State.Copying(done, all)) }, { synchronized(lock) { k in stop } },
                installedBytes = installedBytes(offer.entry.id))
        } catch (e: IOException) {
            // Only a full disk is "no space"; any other failure to write is a save problem, said as such (U7-7).
            set(item, State.Failed(if (isNoSpace(e)) Why.NO_SPACE else Why.NOT_SAVED)); return
        }
        when (result) {
            is UpdateDownloader.Download.Ready -> install(item, offer, result.file)
            is UpdateDownloader.Download.Paused -> set(item, State.Paused(result.offset, total))
            is UpdateDownloader.Download.NeedsResume -> set(item, State.Stopped(Why.HUB_GONE, result.offset, total))
            is UpdateDownloader.Download.NoSpace -> set(item, State.Failed(Why.NO_SPACE))
            UpdateDownloader.Download.NotAuthorized -> set(item, State.Failed(Why.NOT_OFFERED))
            UpdateDownloader.Download.NotOnThisHub -> set(item, State.Failed(Why.NOT_ON_HUB))
            UpdateDownloader.Download.WrongHash -> set(item, State.Failed(Why.NOT_TRUSTED))
            is UpdateDownloader.Download.Refused -> set(item, State.Failed(Why.REFUSED))
            is UpdateDownloader.Download.Busy -> set(item, State.Failed(Why.BUSY))
        }
    }

    private fun isNoSpace(e: Throwable) = generateSequence(e) { it.cause }.take(8).any { x -> x.message?.let { "No space" in it || "ENOSPC" in it } == true }

    private fun install(item: Item, offer: UpdateTrust.Offer, file: File) {
        set(item, State.Installing)
        val result = try { activation.activate(trust, offer, file, open) } catch (e: IOException) { PackActivation.Result.StateNotSaved }
        when (result) {
            is PackActivation.Result.Activated -> { file.delete(); set(item, State.Done) }
            PackActivation.Result.NotAuthorized -> { file.delete(); set(item, State.Failed(Why.NOT_OFFERED)) }
            // The phone could not store a file that is exactly the signed one: keep it, so the next try needs no transfer (U7-7).
            is PackActivation.Result.Rejected -> when (result.why) {
                "NO_SPACE" -> set(item, State.Failed(Why.NO_SPACE))
                "DAMAGED" -> set(item, State.Failed(Why.NOT_SAVED))
                else -> { file.delete(); set(item, State.Failed(Why.NOT_TRUSTED)) }
            }
            PackActivation.Result.WrongFile -> { file.delete(); set(item, State.Failed(Why.NOT_TRUSTED)) }
            PackActivation.Result.OpenFailed -> set(item, State.Failed(Why.OPEN_FAILED))
            PackActivation.Result.StateNotSaved -> set(item, State.Failed(Why.NOT_SAVED))
        }
    }

    companion object {
        /** Before this (2020-01-01) a phone's clock has not been set: no "old list" warning from it. */
        private const val PLAUSIBLE_CLOCK_MILLIS = 1_577_836_800_000L
        private val LIST_REFUSED = setOf(UpdateTrust.Reason.NOT_SIGNED, UpdateTrust.Reason.MALFORMED, UpdateTrust.Reason.WRONG_EPOCH, UpdateTrust.Reason.CONFLICT, UpdateTrust.Reason.BAD_KEY)
    }
}
