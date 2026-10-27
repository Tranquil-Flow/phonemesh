package org.phonemesh.app

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.Profile
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.account.SafetyPicture
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.WireV2

/**
 * Adding people (accounts plan step 11): show and scan QR codes, send and open links, hellos and their requests, the
 * safety picture. Main thread. [send] hands one accounts-port packet to the radio and says whether it took it; a
 * packet it cannot take stays in the outbox as WAITING (the step 13 engine retries it).
 */
class Accounts(
    private val history: History,
    private val store: AccountStore,
    private val send: (ByteArray) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A person who opened this phone's link and is waiting for Accept or Ignore. */
    class Request(val id: ByteArray, val account: AccountId, val name: String, val colour: Int, internal val hello: Start.HelloReceived)

    sealed class Added {
        /** Added; [met] when it was a mutual QR scan. */
        class Contact(val account: AccountId, val chatId: Long, val met: Boolean) : Added()
        /** This phone's own code. */
        object Own : Added()
        /** A QR code scanned before this phone showed its own: show yours, then scan again. */
        object ShowYoursFirst : Added()
        /** Not a Phonemesh code, or damaged. */
        object Bad : Added()
        /** Local storage failed; no contact or session success may be advertised. */
        object NotSaved : Added()
    }

    private var shown: Pair<QrCode, KeyPairBytes>? = null
    private var shownAt = 0L
    // Lives exactly as long as the in-memory one-time private key. A process restart loses both.
    // One showing may start one session per person, never A1/B1 -> A1/B2 -> A1/B1.
    private val showingStarts = HashMap<AccountId, ByteArray>()

    /** The text of this phone's QR code: a fresh one-time key per showing, kept for [SHOWN_MS] for the other's scan. */
    fun showCode(): String {
        val current = shown
        if (current != null && now() - shownAt < SHOWN_MS && current.first.card.prekey.contentEquals(store.prekey)) {
            return AddCode.of(current.first)
        }
        val pair = KeyPairBytes.generate()
        val code = QrCode(store.card(), pair.public)
        showingStarts.clear()
        shown = code to pair
        shownAt = now()
        return AddCode.of(code)
    }

    fun link(): String = AddCode.of(store.card())

    /** What the scanner read. A QR code from another Phonemesh starts a session at once, as "met in person". */
    fun scanned(text: String): Added {
        val code = AddCode.parse(text) ?: return Added.Bad
        if (code.card.accountId == store.accountId) return Added.Own
        return when (code) {
            is AddCode.Link -> openLink(code.card)
            is AddCode.Qr -> {
                val (mine, pair) = shown?.takeIf { now() - shownAt < SHOWN_MS } ?: return Added.ShowYoursFirst
                val state = Start.qr(store, mine, pair, code.code) ?: return Added.Bad
                val account = code.card.accountId
                val digest = org.phonemesh.core.crypto.Bytes.sha256(state.encode())
                val used = showingStarts[account]
                if ((used != null && !used.contentEquals(digest)) || (used == null && showingStarts.size >= MAX_SHOWING_STARTS)) {
                    // Retire the private key before asking to show a fresh code; the old code cannot reset it.
                    shown = null
                    showingStarts.clear()
                    return Added.ShowYoursFirst
                }
                try {
                    history.transaction {
                        keepContact(code.card.encode(), code.card.profile, met = true, told = true)
                        check(store.installQrSession(account, state))
                        store.pendingRequests.retire(account)
                        Added.Contact(account, history.personChat(account.toString(), code.card.name).id, met = true)
                    }.also { showingStarts[account] = digest }
                } catch (e: Exception) {
                    if (e !is android.database.sqlite.SQLiteException && e !is java.security.GeneralSecurityException) throw e
                    Added.NotSaved
                }
            }
        }
    }

    /** A link was opened (tapped in a message, or the page's button): add the person and send them a hello. */
    fun openLink(card: ContactCard): Added {
        if (card.accountId == store.accountId) return Added.Own
        val account = card.accountId
        history.contactRow(account.toString())?.let { return Added.Contact(account, history.personChat(account.toString(), it.name).id, it.met) }
        // Opening their link is consent to the existing authenticated request, not a competing new start.
        try {
            if (store.pendingRequests.contains(account)) {
                val pending = store.pendingRequests.all().firstOrNull { AccountId.of(it.identity) == account }
                    ?: throw org.phonemesh.core.mail.MailStore.Refused()
                return accept(request(pending))
            }
        } catch (failure: Exception) {
            if (failure !is android.database.sqlite.SQLiteException && failure !is java.security.GeneralSecurityException &&
                failure !is org.phonemesh.core.mail.MailStore.Refused) throw failure
            return Added.NotSaved
        }
        val profile = store.profile() ?: return Added.NotSaved
        val opened = Start.link(store, profile, card, KeyPairBytes.generate()) ?: return Added.Bad
        val made = try {
            history.transaction {
                keepContact(card.encode(), card.profile, met = false, told = true)
                val row = History.OutboxRow(0, account.toString(), null, WireV2.PORT, opened.hello, null, null,
                    now(), 0, now() + org.phonemesh.core.mail.Postman.RETRY_MS[0], "WAITING")
                if (!store.storeSession(account, opened.state, row)) throw org.phonemesh.core.mail.MailStore.Refused()
                val id = history.lastOutboxId
                store.pendingRequests.retire(account)
                Added.Contact(account, history.personChat(account.toString(), card.name).id, met = false) to id
            }
        } catch (failure: Exception) {
            if (failure !is android.database.sqlite.SQLiteException && failure !is java.security.GeneralSecurityException &&
                failure !is org.phonemesh.core.mail.MailStore.Refused) throw failure
            return Added.NotSaved
        }
        // Only publish durable bytes. AppCore returns false here and credits its asynchronous queue callback.
        if (runCatching { send(opened.hello) }.getOrDefault(false)) {
            runCatching { history.setOutboxState(made.second, "SENT") }
        }
        return made.first
    }

    /** A hello and its replay marker commit together before the request is shown. */
    fun onPacket(packet: ByteArray): Request? {
        if (!Start.isHello(packet)) return null
        val hello = Start.hello(store, packet) ?: return null
        val account = AccountId.of(hello.identity)
        if (history.contactRow(account.toString()) != null) {
            // Both already chose to add one another. F13 chooses the smaller opener, never a replay or QR downgrade.
            history.transaction {
                val current = store.session(account) ?: return@transaction
                if (history.hasQrStart(account.toString()) || !current.iAmL || current.heard || account >= store.accountId) return@transaction
                if (!history.firstHello(hello.id, now())) return@transaction
                history.retireSessionWork(account.toString())
                if (!store.storeSession(account, hello.state)) throw org.phonemesh.core.mail.MailStore.Refused()
                store.pendingRequests.retire(account)
            }
            return null
        }
        if (!store.pendingRequests.add(hello, now())) return null
        return request(hello)
    }

    private fun request(hello: Start.HelloReceived) =
        Request(hello.id.copyOf(), AccountId.of(hello.identity), hello.name, hello.colour, hello)

    fun requests(): List<Request> = store.pendingRequests.all().map { request(it) }

    fun accept(request: Request): Added.Contact = history.transaction {
        val hello = store.pendingRequests.current(request.account, request.id)
            ?: throw org.phonemesh.core.mail.MailStore.Refused()
        if (history.contactRow(request.account.toString()) != null) throw org.phonemesh.core.mail.MailStore.Refused()
        keepContact(hello.identity, hello.profile, met = false)
        if (!store.storeSession(request.account, hello.state)) throw org.phonemesh.core.mail.MailStore.Refused()
        if (!store.pendingRequests.remove(request.account, hello.id)) throw org.phonemesh.core.mail.MailStore.Refused()
        Added.Contact(request.account, history.personChat(request.account.toString(), hello.name).id, met = false)
    }

    fun ignore(request: Request) { store.pendingRequests.remove(request.account, request.id) }

    /** The six icons both phones show for [account]; slow (a hash of 65,536 rounds): call off the main thread. */
    fun safetyPicture(account: AccountId): IntArray? {
        val contact = history.contactRow(account.toString()) ?: return null
        return SafetyPicture.icons(store.identity, identityOf(contact.card))
    }

    /** "Ce sont les mêmes" ([met] true) or "Pas les mêmes" (false: not, or no longer, met in person). */
    fun setMet(account: AccountId, met: Boolean) {
        val row = history.contactRow(account.toString()) ?: return
        history.putContact(History.ContactRow(row.accountId, row.card, row.name, row.colour, met, row.voice, row.added, row.profile))
    }

    fun isMet(account: AccountId): Boolean = history.contactRow(account.toString())?.met == true

    /**
     * Keeps [profile] unless the contact already has a newer one (an old card scanned again). [told]: they got this
     * phone's current profile in the same exchange (QR, or the hello), so no update needs to follow.
     */
    private fun keepContact(card: ByteArray, profile: Profile, met: Boolean, told: Boolean = false) {
        val account = AccountId.of(identityOf(card)).toString()
        val old = history.contactRow(account)
        val known = old?.profile?.let { Profile.decode(it) }
        val kept = if (known != null && known.revision > profile.revision) known else profile
        history.putContact(History.ContactRow(account, card, kept.name, kept.colour, met || old?.met == true, old?.voice,
            old?.added ?: now(), kept.encode()))
        if (told) store.profile()?.let { history.setProfileSent(account, it.revision, 0L) }
    }

    companion object {
        const val SHOWN_MS = 10 * 60_000L
        private const val MAX_SHOWING_STARTS = 128

        /**
         * A contact row holds a whole card (QR, link) or only the identity key (a hello): the identity either way. It
         * sits at bytes 2..34 of every card version, so cards saved before version 2 still read (they were checked
         * when saved).
         */
        fun identityOf(card: ByteArray): ByteArray = if (card.size == 33) card else card.copyOfRange(2, 35)
    }
}
