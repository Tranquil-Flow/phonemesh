package org.phonemesh.app.demo

import android.content.Context
import org.phonemesh.app.AccountStore
import org.phonemesh.app.Accounts
import org.phonemesh.app.AppMailStore
import org.phonemesh.app.History
import org.phonemesh.app.Vault
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.Start
import org.phonemesh.app.PictureMaker
import org.phonemesh.app.PictureSize
import org.phonemesh.core.PictureWire
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck
import org.phonemesh.core.wire.SharedChatWire
import org.phonemesh.core.wire.SharedPost
import org.phonemesh.core.wire.WireV2

/**
 * One demo person with a radio: the app's own account and mail engines ([AccountStore], [Accounts], [AppMailStore],
 * [Postman]) on their own database and keys, so their chats with the phone use the real start, ratchet, receipts and
 * retries. Only the screen is missing: they answer from [DemoScript]. All methods may be called from any thread.
 */
class DemoPerson(app: Context, val who: Who, private val mesh: DemoMesh, private val clock: DemoClock) {
    enum class Who(val key: String, val label: String, val node: String, val colour: Int, val replies: Boolean) {
        /** Met in person (QR codes), writes back. */
        KOFI("kofi", "Kofi", "!d0e1c0f1", 3, true),
        /** Sends you a request by link; sends receipts but never writes back. */
        AMA("ama", "Ama", "!d0e1a3a1", 5, false),
        /** You open his link; he accepts and writes back. */
        YAW("yaw", "Yaw", "!d0e1ca01", 1, true),
    }

    private val history = History(app, "demo-${who.key}.db")
    private val store = AccountStore(history, Vault(VAULT), softwareOnly = true, keyPrefix = "demo-${who.key}")
    private val accounts: Accounts
    private val postman: Postman
    private var said = 0
    private val random = java.security.SecureRandom()
    private val lastLanguage = HashMap<AccountId, String>()

    init {
        if (history.accountRow()?.name.isNullOrEmpty()) store.setName(who.label, who.colour)
        accounts = Accounts(history, store, send = { false }, now = clock::now)
        // Receipts after the protocol's shortest quiet time (15 s) instead of a random 15 to 60 s, so Delivered comes soon.
        postman = Postman(store, AppMailStore(history, store), quietDelay = { Postman.MIN_QUIET_MS })
        mesh.join(who.node) { _, port, payload, _ -> heard(port, payload) }
        clock.after(TICK_MS) { tick() }
    }

    val accountId: AccountId get() = store.accountId

    @Synchronized fun knows(account: AccountId): Boolean = history.contactRow(account.toString()) != null

    /** The six safety pictures their phone shows for [account] (slow: call off the main thread). */
    @Synchronized fun safetyIcons(account: AccountId): IntArray? = if (knows(account)) accounts.safetyPicture(account) else null

    /** The text of their QR code, as their screen would show it. */
    @Synchronized fun showCode(): String = accounts.showCode()

    @Synchronized fun link(): String = accounts.link()

    /** They scan the phone's QR code (after showing theirs). Met in person: they say hello a moment later. */
    @Synchronized fun scan(code: String): Boolean {
        val added = accounts.scanned(code) as? Accounts.Added.Contact ?: return false
        clock.after(GREET_MS) { say(added.account, DemoScript.metGreeting(language(added.account))) }
        return true
    }

    /** They open the phone's link: a hello goes out, and the phone shows a request. */
    @Synchronized fun openLink(link: String): Boolean {
        val card = AddCode.parse(link)?.card ?: return false
        return accounts.openLink(card) is Accounts.Added.Contact
    }

    @Synchronized fun writeTo(account: AccountId, language: String) {
        if (!knows(account)) return
        lastLanguage[account] = language
        sayNow(account, Body.Text(DemoScript.tag(language), DemoScript.news(who, language, said++)))
    }

    /** They take a picture (a drawn scene) and send it, made and split exactly as the app does it. */
    @Synchronized fun sendPicture(account: AccountId): Boolean {
        if (!knows(account)) return false
        val picture = PictureMaker.prepare(DemoPictures.scene(said++), PictureSize.MEDIUM, grey = false)
        val packets = PictureWire.splitV2(random.nextInt(0x10000), picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
        postman.sendPicture(account, null, packets, clock.now()).filterNotNull().forEach {
            mesh.broadcast(who.node, WireV2.PORT, it.packet)
            postman.transmitted(it.outboxId)
        }
        return true
    }

    /** A post in the shared chat, under their name. */
    fun post(text: String, language: String) {
        val post = SharedPost.Post(SharedPost.Kind.TEXT, accountId, clock.now() / 1_000, who.label, DemoScript.tag(language), null, text)
        mesh.broadcast(who.node, SharedChatWire.PORT, SharedPost.encode(post))
    }

    private fun language(account: AccountId) = lastLanguage[account] ?: DemoScript.defaultLanguage()

    @Synchronized private fun heard(port: Int, payload: ByteArray) {
        val now = clock.now()
        when (port) {
            WireV2.PORT -> if (Start.isHello(payload)) {
                val request = accounts.onPacket(payload) ?: return
                clock.after(ACCEPT_MS) { accept(request) }
            } else {
                val got = postman.receive(payload, now) ?: return
                answer(got)
            }
            HubAck.PORT -> postman.hubAck(payload)
        }
    }

    @Synchronized private fun accept(request: Accounts.Request) {
        val added = runCatching { accounts.accept(request) }.getOrNull() ?: return
        clock.after(GREET_MS) { say(added.account, DemoScript.linkGreeting(who, language(added.account))) }
    }

    private fun answer(got: Postman.Received) {
        val body = got.body
        val language = when (body) {
            is Body.Text -> body.language
            is Body.Reply -> body.language
            else -> language(got.contact)
        }
        lastLanguage[got.contact] = DemoScript.tag(language)
        if (!who.replies) return
        when (body) {
            is Body.Text, is Body.Reply -> clock.after(REPLY_MS) {
                synchronized(this) { sayNow(got.contact, Body.Reply(got.ref, DemoScript.tag(language), DemoScript.reply(who, language, said++))) }
            }
            is Body.PictureHead -> clock.after(REPLY_MS) { synchronized(this) { sayNow(got.contact, Body.Reaction(got.ref, "👍")) } }
            else -> Unit
        }
    }

    @Synchronized private fun say(account: AccountId, text: String) = sayNow(account, Body.Text(language(account), text))

    private fun sayNow(account: AccountId, body: Body) {
        val out = postman.send(account, body, null, clock.now()) ?: return
        mesh.broadcast(who.node, WireV2.PORT, out.packet)
        postman.transmitted(out.outboxId)
    }

    /** What their phone's postman does each second: receipts, held messages and resends that are due. */
    private fun tick() {
        runCatching {
            synchronized(this) {
                val now = clock.now()
                postman.recoverIncoming(now).forEach { answer(it) }
                (postman.due(now) + postman.unsent()).distinctBy { it.outboxId }.forEach {
                    mesh.broadcast(who.node, WireV2.PORT, it.packet)
                    postman.transmitted(it.outboxId)
                }
            }
        }
        clock.after(TICK_MS) { tick() }
    }

    companion object {
        private const val VAULT = "phonemesh-demo-vault"
        private const val TICK_MS = 1_000L
        private const val ACCEPT_MS = 2_500L
        private const val GREET_MS = 4_000L
        private const val REPLY_MS = 6_000L
    }
}
