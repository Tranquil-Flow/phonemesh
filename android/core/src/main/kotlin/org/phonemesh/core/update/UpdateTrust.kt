package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import java.io.IOException

/**
 * The phone's rules for what update metadata it believes (hub updates spec 5.3, decisions 45, 61, 62, 70). It holds one
 * [TrustState] and changes it only by whole, signed steps, each saved before it counts:
 *
 * - First start: the packaged baseline catalog, signed by the packaged catalog key, becomes the state. Damaged saved state is
 *   reported as [Opened.Corrupt]; it is never replaced by the baseline (a torn very first write is the one exception, since
 *   it is exactly a fresh install).
 * - A catalog is accepted only if signed by the epoch's catalog key, for this epoch, newer than the held one (the same signed
 *   content again is harmless, other content under the same number is a conflict), readable by this app, and not re-using a
 *   version with other bytes. Rollback is impossible: a lower number is refused.
 * - A recovery policy, signed only by the recovery key, starts a higher epoch with a new catalog key, drops the old epoch's
 *   catalog (so its offers stop), and sets the lowest catalog number of the new epoch. The old key is dead from then on.
 * - Version floors go up only when a pack is installed ([recordInstalled]); seeing an offer changes nothing.
 *
 * App updates are out of this first version (decision 62): [offers] lists packs only. Not thread safe by itself; the
 * downloader calls it from one place at a time.
 */
class UpdateTrust private constructor(
    private val store: TrustStore, private val roots: UpdateRoots, val readerVersion: Long, private var current: TrustState,
) {
    enum class Reason { NOT_SIGNED, MALFORMED, WRONG_EPOCH, OLDER, CONFLICT, NEEDS_NEWER_APP, NOT_NEWER_EPOCH, BAD_KEY, STORE_FAILED }

    sealed class CatalogResult {
        class Accepted(val sequence: Long) : CatalogResult()
        object Same : CatalogResult()
        class Refused(val reason: Reason) : CatalogResult()
    }

    sealed class RecoveryResult {
        class Accepted(val epoch: Long) : RecoveryResult()
        object Same : RecoveryResult()
        class Refused(val reason: Reason) : RecoveryResult()
    }

    sealed class Opened {
        class Ready(val trust: UpdateTrust) : Opened()
        /** Saved state exists but cannot be read. Updates stay off; nothing is reset. */
        class Corrupt(val why: String) : Opened()
        /** The packaged baseline does not verify under the packaged keys (a build mistake), or cannot be saved. */
        class BadBaseline(val why: String) : Opened()
    }

    /** An update the person may be asked about: [entry] from the catalog of [epoch] and [sequence]. */
    class Offer(val entry: UpdateMetadata.Entry, val epoch: Long, val sequence: Long)

    val state: TrustState get() = current
    private var cached: Pair<Long, UpdateMetadata.Catalog?>? = null

    /** The accepted catalog, parsed; null right after a recovery until the new epoch's first catalog. */
    fun catalog(): UpdateMetadata.Catalog? {
        cached?.takeIf { it.first == current.generation }?.let { return it.second }
        val parsed = current.catalog?.let { UpdateMetadata.verify(it, mapOf(current.keyId to current.key), null) }?.takeIf { it.kind == UpdateMetadata.CATALOG }
            ?.let { UpdateMetadata.catalog(it.payload) }
        cached = current.generation to parsed
        return parsed
    }

    private fun payloadOf(bytes: ByteArray?, keys: Map<Int, ByteArray>, recovery: ByteArray?): ByteArray? =
        bytes?.let { UpdateMetadata.verify(it, keys, recovery) }?.payload

    private fun commit(next: TrustState): Boolean = try {
        store.save((next.generation % 2).toInt(), next.encode())
        current = next
        true
    } catch (_: IOException) { false }

    @Synchronized fun acceptCatalog(bytes: ByteArray): CatalogResult {
        val s = current
        val env = UpdateMetadata.verify(bytes, mapOf(s.keyId to s.key), null)?.takeIf { it.kind == UpdateMetadata.CATALOG }
            ?: return CatalogResult.Refused(Reason.NOT_SIGNED)
        val c = UpdateMetadata.catalog(env.payload) ?: return CatalogResult.Refused(Reason.MALFORMED)
        if (c.epoch != s.epoch) return CatalogResult.Refused(Reason.WRONG_EPOCH)
        if (c.sequence < s.sequence) return CatalogResult.Refused(Reason.OLDER)
        if (c.sequence == s.sequence) {
            val held = payloadOf(s.catalog, mapOf(s.keyId to s.key), null) ?: return CatalogResult.Refused(Reason.OLDER)
            return if (held.contentEquals(env.payload)) CatalogResult.Same else CatalogResult.Refused(Reason.CONFLICT)
        }
        if (c.minReader > readerVersion) return CatalogResult.Refused(Reason.NEEDS_NEWER_APP)
        // The same artifact version must keep the same bytes.
        catalog()?.entries?.forEach { old ->
            if (c.entries.any { it.kind == old.kind && it.id == old.id && it.version == old.version && it.abi == old.abi && !it.digest.contentEquals(old.digest) }) {
                return CatalogResult.Refused(Reason.CONFLICT)
            }
        }
        return if (commit(s.copy(generation = s.generation + 1, sequence = c.sequence, catalog = bytes))) CatalogResult.Accepted(c.sequence)
        else CatalogResult.Refused(Reason.STORE_FAILED)
    }

    @Synchronized fun acceptRecovery(bytes: ByteArray): RecoveryResult {
        val s = current
        val env = UpdateMetadata.verify(bytes, emptyMap(), roots.recoveryKey)?.takeIf { it.kind == UpdateMetadata.RECOVERY }
            ?: return RecoveryResult.Refused(Reason.NOT_SIGNED)
        val r = UpdateMetadata.recovery(env.payload) ?: return RecoveryResult.Refused(Reason.MALFORMED)
        if (r.epoch <= s.epoch) {
            val held = payloadOf(s.policy, emptyMap(), roots.recoveryKey)
            return if (r.epoch == s.epoch && held != null && held.contentEquals(env.payload)) RecoveryResult.Same else RecoveryResult.Refused(Reason.NOT_NEWER_EPOCH)
        }
        if (r.minReader > readerVersion) return RecoveryResult.Refused(Reason.NEEDS_NEWER_APP)
        // The successor key may be neither the recovery key nor the key it replaces.
        if (r.catalogKey.contentEquals(roots.recoveryKey) || r.catalogKey.contentEquals(s.key)) return RecoveryResult.Refused(Reason.BAD_KEY)
        val next = TrustState(s.generation + 1, r.epoch, r.catalogKeyId, r.catalogKey, r.minSequence - 1, null, bytes, s.floors)
        return if (commit(next)) RecoveryResult.Accepted(r.epoch) else RecoveryResult.Refused(Reason.STORE_FAILED)
    }

    /** The installed version floor of a pack (0 when none has been recorded). */
    fun floor(kind: Int, id: String): Long = current.floors[TrustState.floorKey(kind, id)]?.version ?: 0L

    /**
     * Pack updates worth offering to a phone of app version [app] and Android level [sdk]: from the accepted catalog, newer
     * than the floor and than what is [installed] (by pack id), runnable here, not revoked. One per pack, the highest.
     */
    fun offers(app: Long, sdk: Int, installed: Map<String, Long> = emptyMap()): List<Offer> {
        val c = catalog() ?: return emptyList()
        return c.entries.filter { e ->
            (e.kind == UpdateMetadata.ENGINE || e.kind == UpdateMetadata.VOICE) && e.packFormat == 2 &&
                e.version > maxOf(floor(e.kind, e.id), installed[e.id] ?: 0L) &&
                app >= e.minApp && (e.maxApp == 0L || app <= e.maxApp) && sdk >= e.minApi &&
                c.revoked.none { it.contentEquals(e.digest) }
        }.groupBy { it.kind to it.id }.values.map { it.maxBy { e -> e.version } }
            .sortedWith(compareBy({ it.kind }, { it.id })).map { Offer(it, current.epoch, current.sequence) }
    }

    /** Whether the accepted catalog of this epoch still lists the offered artifact and has not revoked it. */
    fun stillAuthorized(offer: Offer): Boolean {
        val c = catalog() ?: return false
        val e = offer.entry
        return offer.epoch == current.epoch && c.entries.any { it.kind == e.kind && it.id == e.id && it.version == e.version && it.digest.contentEquals(e.digest) } &&
            c.revoked.none { it.contentEquals(e.digest) }
    }

    /** Raises the floor of [entry]'s pack to its version after it was installed and opened. False if that would lower it or re-use a version with other content. */
    @Synchronized fun recordInstalled(entry: UpdateMetadata.Entry): Boolean {
        if (entry.kind != UpdateMetadata.ENGINE && entry.kind != UpdateMetadata.VOICE) return false
        val key = TrustState.floorKey(entry.kind, entry.id)
        val old = current.floors[key]
        if (old != null) {
            if (old.version > entry.version) return false
            if (old.version == entry.version) return old.sums.contentEquals(entry.sums)
        }
        if (current.floors.size >= TrustState.MAX_FLOORS && old == null) return false
        val next = current.floors + (key to TrustState.Floor(entry.version, entry.sums, entry.digest))
        return commit(current.copy(generation = current.generation + 1, floors = next))
    }

    /** Ids of installed packs that the accepted catalog now revokes (by archive digest, or by manifest digest of a revoked entry). */
    fun revokedInstalled(): List<String> {
        val c = catalog() ?: return emptyList()
        fun revoked(d: ByteArray) = c.revoked.any { it.contentEquals(d) }
        return current.floors.filter { (name, f) ->
            revoked(f.artifact) || c.entries.any { it.id == name.substringAfter('/') && it.sums.contentEquals(f.sums) && revoked(it.digest) }
        }.keys.map { it.substringAfter('/') }
    }

    companion object {
        /**
         * Loads the saved state, or starts from [baseline] on a first start. [readerVersion] is this app's versionCode.
         */
        fun open(
            store: TrustStore, roots: UpdateRoots = UpdateRoots.compiled, readerVersion: Long,
            baseline: ByteArray = UpdateRoots.baselineCatalog(),
        ): Opened {
            val a = store.load(0); val b = store.load(1)
            val best = listOfNotNull(a?.let(TrustState::decode), b?.let(TrustState::decode)).maxByOrNull { it.generation }
            if (best != null) return Opened.Ready(UpdateTrust(store, roots, readerVersion, best))
            // Nothing whole. The first save of an install goes to slot 1, so slot 0 was never written; a torn first save is a fresh start.
            if (a != null) return Opened.Corrupt("the saved update state is damaged")
            val env = UpdateMetadata.verify(baseline, mapOf(roots.catalogKeyId to roots.catalogKey), null)?.takeIf { it.kind == UpdateMetadata.CATALOG }
                ?: return Opened.BadBaseline("the packaged baseline catalog is not signed by the packaged catalog key")
            val c = UpdateMetadata.catalog(env.payload) ?: return Opened.BadBaseline("the packaged baseline catalog is malformed")
            if (c.minReader > readerVersion) return Opened.BadBaseline("the packaged baseline needs a newer app")
            val first = TrustState(1, c.epoch, roots.catalogKeyId, roots.catalogKey, c.sequence, baseline, null, emptyMap())
            return try {
                store.save((first.generation % 2).toInt(), first.encode())
                Opened.Ready(UpdateTrust(store, roots, readerVersion, first))
            } catch (e: IOException) { Opened.BadBaseline("the update state cannot be saved: ${e.message}") }
        }
    }
}
