package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import java.io.File

/**
 * Behavioral RED, existing APIs only: no new helper, so a failure here is behaviour, not missing compilation. Bea's real
 * account scans Awa's real code (SoftKeys, mutual QR) exactly as PersonChatTest does. Today a picture body from a person
 * is dropped without a bubble, and an old radio chat still sends texts and pictures.
 */
@RunWith(AndroidJUnit4::class)
class PersonPictureRedTest {
    private val core get() = TestCore.core

    @After fun tearDown() = TestCore.uninstall()

    private class Bea {
        val keys = SoftKeys.generate()
        val pair = KeyPairBytes.generate()
        val qr = QrCode(ContactCard.create(keys, org.phonemesh.core.account.Profile("Bea", 0x8E24AA)), pair.public)
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        val sessions = LinkedHashMap<AccountId, SessionState>()
        private val replays = LinkedHashMap<String, MailStore.Replay>()
        private var next = 1L
        val store = object : MailStore {
            override fun pendingReceipts() = replays.values.filter { it.due != null }
            override fun replay(digest: String, now: Long, due: Long) = replays.containsKey(digest)
            override fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long) {}
            override fun pendingIncoming(now: Long) = emptyList<MailStore.HeldIncoming>()
            override fun incomingExpiry(): Long? = null
            override fun contacts() = sessions.keys.toList()
            override fun session(contact: AccountId) = sessions[contact]
            override fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?, consumeHeldId: Long?,
                incoming: MailStore.Incoming?, expected: SessionState): MailStore.Committed? {
                sessions[contact] = state
                incoming?.let { replays[it.digest] = MailStore.Replay(it.digest, contact, it.responseAck, it.due, it.at + MailStore.REPLAY_LIFETIME) }
                return MailStore.Committed(outgoing?.let { val id = next++; rows[id] = it.copy(id = id); id })
            }
            override fun unconfirmed() = rows.values.toList()
            override fun update(row: Postman.Outgoing) { rows[row.id] = row }
            override fun markSent(id: Long) {}
            override fun markHub(codes: Set<String>) {}
            override fun markDelivered(contact: AccountId, ack: Ack) {}
            override fun lastAck(contact: AccountId): Ack? = null
            override fun saveAck(contact: AccountId, ack: Ack) {}
            override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) {}
            override fun held(contact: AccountId) = emptyList<Postman.Held>()
            override fun dropHeld(id: Long) {}
        }
        val postman = Postman(keys, store, quietDelay = { 20_000L })
        lateinit var awa: AccountId
    }

    private fun meet(): Pair<Bea, Long> {
        TestCore.install()
        TestCore.radio.acceptBroadcasts = true
        val bea = Bea()
        val awaText = TestCore.onMain { core.accountsFlow.showCode() }
        val awaQr = (AddCode.parse(awaText) as AddCode.Qr).code
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        bea.awa = awaQr.card.accountId
        bea.sessions[bea.awa] = Start.qr(bea.keys, bea.qr, bea.pair, awaQr)!!
        return bea to added.chatId
    }

    private fun deliver(packet: ByteArray) = TestCore.onMain { core.onBroadcastData("!0000bea0", MeshRadio.ACCOUNT_PORT, packet, 0, 1L, "") }

    @Test fun aPictureFromAPersonBecomesOneBubbleWithItsFile() {
        val (bea, chatId) = meet()
        val bytes = ByteArray(396) { (it * 3 + 1).toByte() }
        val head = PictureWire.Head(4, PictureCodec.WEBP, 8, 8, 396, 2, PictureWire.crc(bytes), byteArrayOf(1, 2, 3))
        val bodies = listOf<Body>(Body.PictureHead(PictureWire.encode(head))) +
            (0..1).map { Body.PicturePart(PictureWire.encode(PictureWire.Part(4, it, bytes.copyOfRange(it * 198, (it + 1) * 198)))) }
        bodies.forEachIndexed { i, body -> deliver(checkNotNull(bea.postman.send(bea.awa, body, null, 1_000L + i)).packet) }
        val shown = TestCore.onMain { core.history.messages(chatId).filter { it.kind == MessageKind.PICTURE } }.single()
        val file = TestCore.onMain { core.history.picture(shown.id)!!.file }
        assertArrayEquals(bytes, File(core.picturesDir, checkNotNull(file)).readBytes())
    }

    @Test fun anOldRadioChatCannotSendATextOrAPicture() {
        TestCore.install()
        val chat = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val photo = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 200)) }
        TestCore.onMain { core.setDraft(chat, "hello") }
        assertFalse(TestCore.onMain { core.send(chat) })
        assertFalse(TestCore.onMain { core.canSendPictures(chat) })
        assertFalse(TestCore.onMain { core.sendPicture(chat, PictureMaker.prepare(photo, PictureSize.MEDIUM, grey = false)) })
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.radio.sentData.isEmpty())
        assertEquals(0, TestCore.onMain { core.history.messages(chat).size })
    }
}
