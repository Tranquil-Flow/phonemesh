package org.phonemesh.app

import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.account.Profile
import org.phonemesh.core.wire.ClaimedSender
import java.io.File

/**
 * A phone with history worth saving (v0.1.2 B1): a person chat with [BIG] messages (past the old 500-message display
 * limit), replies (to an old message, and to one that never existed), reactions, a received picture, a lost picture, a message that never left and a planned one; the
 * shared channel with a claimed sender and an old radio; and a private group where one member is a contact, one is
 * not, and one message was shared by an admin. Call after TestCore.install(), off the main thread.
 */
object ChatFixture {
    const val BIG = 600
    const val DAY = 86_400_000L

    class Built(
        val bea: PersonFixture.Bea, val yaw: PersonFixture.Bea, val person: Long, val shared: Long, val legacy: Long, val group: Long,
        val groupHex: String, val now: Long, val picture: ByteArray,
    )

    /** A valid WEBP container of [size] bytes. */
    fun webp(size: Int = 300): ByteArray {
        val body = ByteArray(size - 12) { ((it * 7 + 3) and 0xFF).toByte() }
        return "RIFF".toByteArray() + java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(body.size + 4).array() + "WEBP".toByteArray() + body
    }

    fun build(core: AppCore, now: Long = System.currentTimeMillis()): Built {
        val (bea, personChat) = PersonFixture.meet("Bea")
        val yaw = PersonFixture.Bea("Yaw", 0x00838F)
        val picture = webp()
        return TestCore.onMain {
            val h = core.history
            // The person chat: BIG messages, newest ten minutes ago; the first 20 are older than 90 days.
            var firstIn = 0L
            for (i in 1..BIG) {
                val time = if (i <= 20) now - 120 * DAY + i * 60_000L else now - (BIG - i + 10) * 60_000L
                if (i % 2 == 1) {
                    h.addIncoming(personChat, bea.account, "bea $i", "fr", time, false, packetId = 1000L + i, replyTo = if (i == 5 || i == 25) 1001L else null)
                    if (firstIn == 0L) firstIn = 1000L + i
                } else {
                    val m = h.addOutgoing(personChat, "me $i", "fr", time, replyTo = if (i == 4 || i == 28) 1001L else if (i == 30) 999_999L else null)
                    h.attachPacket(m.id, 2000L + i)
                    h.setStatus(m.id, if (i % 4 == 0) DeliveryStatus.DELIVERED else DeliveryStatus.SENT)
                }
            }
            h.addReaction(personChat, bea.account, "👍", 2000L + BIG, now - 5 * 60_000L)
            h.addReaction(personChat, null, "❤️", 1000L + BIG - 1, now - 4 * 60_000L)
            h.addReaction(personChat, bea.account, "😂", 1001L, now - 119 * DAY)
            val stuck = h.addOutgoing(personChat, "never left", "fr", now - 3 * 60_000L)
            h.setStatus(stuck.id, DeliveryStatus.QUEUED)
            h.addPlanned(personChat, "planned hello", "fr", now + DAY, null)
            // A received picture with its file, and one that was lost on the way.
            val file = "in-fixture.pic"
            File(core.picturesDir, file).writeBytes(picture)
            val sketch = ByteArray(16) { (it + 1).toByte() }
            h.addIncomingPicture(personChat, bea.account, PictureRow(0, 7, 1, 64, 48, sketch, file, 2, 2, false), now - 2 * 60_000L, false)
            h.addIncomingPicture(personChat, bea.account, PictureRow(0, 8, 1, 40, 30, sketch, null, 1, 3, true), now - 90_000L, false)
            // The shared channel: a claimed sender; the old radio chat: a radio id.
            val shared = h.groupChat(0)
            h.addIncoming(shared.id, ClaimedSender.key(yaw.qr.card.accountId, "Yaw"), "bonjour tout le monde", "fr", now - 50_000L, false, packetId = 31L)
            h.addOutgoing(shared.id, "bonjour Yaw", "fr", now - 40_000L).also { h.setStatus(it.id, DeliveryStatus.SENT) }
            val legacy = h.directChat("!1234abcd", "Old radio")
            h.addIncoming(legacy.id, "!1234abcd", "ancien message", "fr", now - 30_000L, false)
            // A private group: Bea (a contact), Yaw (only a name the group knows), and a message an admin shared.
            val hex = "ab".repeat(16)
            val group = h.privateGroupChat(hex, "Famille")
            core.groupStore.rows.putGroup(GroupRows.GroupRow(hex, group.id, byteArrayOf(1), ByteArray(33) { 2 }, byteArrayOf(3), byteArrayOf(), null, null, null, 0, null,
                "ACTIVE", 1, true, "Famille", 0x2E7D32, byteArrayOf(), false, null, false))
            core.groupStore.rows.setProfile(hex, yaw.account, Profile("Yaw", 0x00838F).encode(), 1)
            h.addIncoming(group.id, bea.account, "on se retrouve ?", "fr", now - 20_000L, false, packetId = 41L)
            h.addIncoming(group.id, yaw.account, "oui !", "fr", now - 19_000L, false, packetId = 42L, replyTo = 41L)
            h.addIncoming(group.id, bea.account, "il y avait ça avant", "fr", now - 18_000L, false, packetId = 43L, sharedBy = yaw.account)
            h.addOutgoing(group.id, "moi aussi", "fr", now - 17_000L, replyTo = 42L).also { h.attachPacket(it.id, 44L); h.setStatus(it.id, DeliveryStatus.SENT) }
            h.addNote(group.id, "joined:${yaw.account}", now - 21_000L)
            h.addReaction(group.id, yaw.account, "👍", 44L, now - 16_000L)
            Built(bea, yaw, personChat, shared.id, legacy.id, group.id, hex, now, picture)
        }
    }

    /** A saved copy made on this phone, as a file would hold it: the sealed bytes of [chats] (all four chats by default). */
    fun copy(
        core: AppCore, built: Built, key: org.phonemesh.core.backup.RecoveryKey, work: File, chats: Set<Long>? = null, since: Long? = null,
        random: java.security.SecureRandom? = null,
    ): ByteArray {
        val scope = ChatScope(chats ?: setOf(built.person, built.shared, built.legacy, built.group), since)
        val snap = checkNotNull(ChatSnapshot.build(core.history, core.picturesDir, work, scope, built.now, core::exportLabel))
        snap.use {
            val preview = org.phonemesh.core.backup.ChatExport.preview(it, 5, 1) as org.phonemesh.core.backup.ChatExport.Preview.Ok
            val sink = java.io.ByteArrayOutputStream()
            val saved = ChatBackup(work).save(it, preview, key, 5, 1, { sink }, { java.io.ByteArrayInputStream(sink.toByteArray()) })
            check(saved is ChatBackup.Saved.Ok) { "the copy was not saved: $saved" }
            return sink.toByteArray()
        }
    }
}
