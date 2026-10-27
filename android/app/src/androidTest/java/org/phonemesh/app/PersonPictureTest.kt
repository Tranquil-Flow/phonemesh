package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureJoin
import org.phonemesh.core.PictureWire
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck
import java.io.File

/**
 * Pictures between people through AppCore, AppMailStore, History and the fake radio, with a real mutual-QR contact
 * (PersonFixture). Uses new production symbols (splitV2, sendPicture on Postman): not the behavioural RED class.
 */
@RunWith(AndroidJUnit4::class)
class PersonPictureTest {
    private val core get() = TestCore.core
    private val hubStatus = DeliveryStatus.HUB

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    private val sketch = byteArrayOf(1, 2, 3)

    private fun beaPicture(bea: PersonFixture.Bea, id: Int, size: Int): Pair<ByteArray, List<ByteArray>> {
        val bytes = ByteArray(size) { (it * 13 + 1).toByte() }
        val packets = PictureWire.splitV2(id, PictureCodec.WEBP, 64, 48, bytes, sketch)
        return bytes to bea.postman.sendPicture(bea.awa, null, packets, 1_000).map { checkNotNull(it).packet }
    }

    private fun mixed(packets: List<ByteArray>, seed: Long): List<ByteArray> = packets.toMutableList().also {
        java.util.Collections.shuffle(it, java.util.Random(seed))
    }

    private fun deliver(packet: ByteArray) = PersonFixture.deliver(packet)
    private fun count(sql: String): Long = TestCore.onMain { core.history.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) } }
    private fun pictures(chatId: Long) = TestCore.onMain { core.history.messages(chatId).filter { it.kind == MessageKind.PICTURE } }
    private fun fileOf(messageId: Long): ByteArray? = TestCore.onMain {
        core.history.picture(messageId)?.file?.let { File(core.picturesDir, it).takeIf { f -> f.isFile }?.readBytes() }
    }

    private fun photo(): Bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 300) for (y in 0 until 200) setPixel(x, y, Color.rgb((x * 7) % 256, (y * 5) % 256, (x + y) % 256))
    }

    // ---- Receiving

    @Test fun aPictureArrivingOutOfOrderWithCopiesBecomesOneBubbleAndOneFile() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val (bytes, packets) = beaPicture(bea, 21, 3000)
        deliver(packets[0])
        mixed(packets.drop(1) + packets.drop(1).take(3), 5).forEach { deliver(it) }
        val shown = pictures(chatId).single()
        assertEquals(Direction.IN, shown.direction)
        assertArrayEquals(bytes, fileOf(shown.id))
        assertEquals("its pieces are dropped once the file exists", 0L, count("SELECT count(*) FROM picture_pieces"))
        assertEquals("the head names the bubble", AppMailStore.refNumber(Ratchet.reference(packets[0].copyOfRange(1, 9))), shown.packetId)
    }

    @Test fun partsThatCameBeforeTheirHeadWaitOnDiskAcrossARestart() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        deliver(PersonFixture.send(bea, Body.Text("fr", "hello")))
        val (bytes, packets) = beaPicture(bea, 22, 1000)
        packets.drop(1).forEach { deliver(it) }
        assertTrue("no head yet, no bubble", pictures(chatId).isEmpty())
        assertEquals(packets.size - 1L, count("SELECT count(*) FROM picture_pieces"))
        TestCore.restart()
        deliver(packets[0])
        assertArrayEquals(bytes, fileOf(pictures(chatId).single().id))
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    @Test fun aHalfReceivedPictureSurvivesARestartAndFinishes() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val (bytes, packets) = beaPicture(bea, 23, 1000)
        packets.take(4).forEach { deliver(it) }
        val id = pictures(chatId).single().id
        TestCore.restart()
        val row = TestCore.onMain { core.history.picture(id)!! }
        assertFalse("a person's picture is not lost with the process", row.lost)
        assertEquals(3, row.have)
        packets.drop(4).forEach { deliver(it) }
        assertArrayEquals(bytes, fileOf(id))
    }

    @Test fun aPartPastTheHeadsCountIsConsumedAndLaterTextStillArrives() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        deliver(PersonFixture.send(bea, Body.Text("fr", "hello")))
        val (_, packets) = beaPicture(bea, 24, 400)
        deliver(packets[0])
        deliver(PersonFixture.send(bea, Body.PicturePart(PictureWire.encodeV2(PictureWire.Part(24, 9, ByteArray(10)))), 1_001))
        deliver(PersonFixture.send(bea, Body.Text("fr", "after"), 1_002))
        assertTrue(TestCore.onMain { core.history.messages(chatId).any { it.text == "after" } })
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces WHERE idx = 9"))
    }

    @Test fun aPictureThatFailsItsChecksumIsLostAndItsPiecesAreDropped() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val split = PictureWire.splitV2(25, PictureCodec.WEBP, 64, 48, ByteArray(400) { it.toByte() }, sketch)
        val packets = listOf((split[0] as PictureWire.Head).copy(crc = 12345L)) + split.drop(1)
        packets.forEach {
            val body = if (it is PictureWire.Head) Body.PictureHead(PictureWire.encodeV2(it)) else Body.PicturePart(PictureWire.encodeV2(it))
            deliver(PersonFixture.send(bea, body))
        }
        val pictureMessageId = pictures(chatId).single().id
        assertTrue(TestCore.onMain { core.history.picture(pictureMessageId)!!.lost })
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    @Test fun storageFailureOnPiecesIsNotAcknowledgedAndTheRetryIsAccepted() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val (_, packets) = beaPicture(bea, 26, 1000)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER fail_pieces BEFORE INSERT ON picture_pieces BEGIN SELECT RAISE(ABORT, 'injected pieces failure'); END")
        }
        assertTrue("a recoverable storage failure must not crash", runCatching { deliver(packets[0]) }.isSuccess)
        assertTrue("no bubble without its head kept", pictures(chatId).isEmpty())
        assertEquals("the packet was not recorded as received", 0L, count("SELECT count(*) FROM received_mail"))
        assertNotNull(TestCore.onMain { core.lastNote })
        TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER fail_pieces") }
        deliver(packets[0])
        assertEquals(1, pictures(chatId).size)
        assertEquals(1L, count("SELECT count(*) FROM received_mail"))
    }

    // ---- Orphans, expiry, deletion: bounded storage and no loss before the file exists

    @Test fun looseParts_AreKeptForFourPicturesWithoutEvictingAcceptedBytes() {
        TestCore.install()
        val (bea, _) = PersonFixture.meet()
        deliver(PersonFixture.send(bea, Body.Text("fr", "hello")))
        // Eleven sealed packets including the greeting fit the peer's twenty-packet window.
        for (id in 41..45) deliver(beaPicture(bea, id, 100).second[1])
        assertEquals(PersonPictures.MAX_ORPHANS.toLong(), count("SELECT count(DISTINCT picture_id) FROM picture_pieces"))
        assertEquals("acknowledged parts are retained", 1L, count("SELECT count(*) FROM picture_pieces WHERE picture_id = 41"))
        assertEquals("the fifth group can retry; it was not consumed", 0L, count("SELECT count(*) FROM picture_pieces WHERE picture_id = 45"))
    }

    @Test fun stalePiecesAreGivenUpButAWholePictureWaitsForItsFile() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val (_, half) = beaPicture(bea, 31, 1000)
        half.take(3).forEach { deliver(it) }
        val halfId = pictures(chatId).single().id
        // The file row cannot be written yet: the whole picture stays as pieces, which must survive expiry and a restart.
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER block_file BEFORE UPDATE OF file ON pictures BEGIN SELECT RAISE(ABORT, 'injected file failure'); END")
        }
        val (bytes, whole) = beaPicture(bea, 32, 400)
        whole.forEach { deliver(it) }
        val wholeId = pictures(chatId).last().id
        TestCore.onMain {
            assertNull(core.history.picture(wholeId)!!.file)
            assertFalse(core.history.picture(wholeId)!!.lost)
        }
        assertTrue("pieces are kept while the file row fails", count("SELECT count(*) FROM picture_pieces WHERE picture_id = 32") > 0)
        TestCore.onMain { core.history.writableDatabase.execSQL("UPDATE picture_pieces SET at = 0") }
        TestCore.restart()
        Views.waitUntil { core.history.picture(halfId)!!.lost }
        TestCore.onMain {
            assertFalse("a whole picture is never given up", core.history.picture(wholeId)!!.lost)
            assertNull(core.history.picture(wholeId)!!.file)
        }
        assertEquals("the half picture's pieces went", 0L, count("SELECT count(*) FROM picture_pieces WHERE picture_id = 31"))
        assertTrue(count("SELECT count(*) FROM picture_pieces WHERE picture_id = 32") > 0)
        TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER block_file") }
        deliver(PersonFixture.send(bea, Body.Text("fr", "any receive finishes it"), 2_000))
        assertArrayEquals(bytes, fileOf(wholeId))
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    @Test fun deletingAPictureOrAChatDropsItsPieces() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        deliver(PersonFixture.send(bea, Body.Text("fr", "hello")))
        deliver(beaPicture(bea, 51, 1000).second.let { it[0] })
        val id = pictures(chatId).single().id
        assertTrue(count("SELECT count(*) FROM picture_pieces") > 0)
        assertTrue(TestCore.onMain { core.deleteMessage(id) })
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
        deliver(beaPicture(bea, 52, 400).second[1])
        assertEquals(1L, count("SELECT count(*) FROM picture_pieces"))
        assertTrue(TestCore.onMain { core.deleteChat(chatId) })
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    // ---- Sending and the bubble's status

    @Test fun aPictureIsSentOnlyWhenEveryPacketIsAcceptedAndDeliveredOnlyWhenEveryPacketIs() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val total = PictureWire.partsForV2(picture.bytes.size) + 1
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        TestCore.onMain {
            assertEquals(MessageKind.PICTURE, message.kind)
            assertEquals("every packet is kept before any is sent", total, core.history.outbox(bea.account).size)
            assertEquals("one write in flight", 1, TestCore.radio.broadcasts.size)
            assertTrue("nothing used the old radio-address paths", TestCore.radio.sent.isEmpty() && TestCore.radio.sentData.isEmpty())
        }
        repeat(total) { index ->
            TestCore.onMain { TestCore.radio.deliver(TestCore.radio.broadcasts[index].packetId, DeliveryState.SUBMITTING) }
            assertEquals("being written is not accepted", index + 1, TestCore.radio.broadcasts.size)
            TestCore.onMain { TestCore.radio.deliver(TestCore.radio.broadcasts[index].packetId, DeliveryState.QUEUED) }
            if (index < total - 1) {
                Views.waitUntil { TestCore.radio.broadcasts.size == index + 2 }
                assertEquals("Sent needs every packet accepted", DeliveryStatus.WAITING, TestCore.onMain { core.history.message(message.id)!!.status })
            }
        }
        assertEquals(DeliveryStatus.SENT, TestCore.onMain { core.history.message(message.id)!!.status })
        assertTrue(TestCore.radio.broadcasts.all { it.port == MeshRadio.ACCOUNT_PORT && it.payload.size <= 228 })
        // The hub holds some packets: not enough. All of them: the hub status. (Until the parent adds HUB it reads Sent.)
        val codes = TestCore.onMain { core.history.outbox(bea.account).map { it.code!! } }
        TestCore.onMain { core.postman.hubAck(HubAck.encode(codes.take(1))) }
        assertEquals(DeliveryStatus.SENT, TestCore.onMain { core.history.message(message.id)!!.status })
        TestCore.onMain { core.postman.hubAck(HubAck.encode(codes)) }
        assertEquals(hubStatus, TestCore.onMain { core.history.message(message.id)!!.status })
        // Bea: the head first (it carries the key), the rest shuffled. Her receipt delivers the whole picture.
        val air = TestCore.radio.broadcasts.map { it.payload }
        val got = (listOf(air[0]) + mixed(air.drop(1), 3)).mapNotNull { bea.postman.receive(it, 2_000) }
        val head = PictureWire.decodeV2(got.map { it.body }.filterIsInstance<Body.PictureHead>().single().bytes) as PictureWire.Head
        val parts = got.map { it.body }.filterIsInstance<Body.PicturePart>().map { PictureWire.decodeV2(it.bytes) as PictureWire.Part }.associate { it.index to it.data }
        assertArrayEquals(picture.bytes, PictureJoin.join(head, parts))
        assertTrue("the head names the bubble", got.first().ref.contentEquals(AppMailStore.refBytes(TestCore.onMain { core.history.message(message.id)!!.packetId!! })))
        deliver(bea.postman.due(60_000).single().packet)
        TestCore.onMain {
            assertEquals(DeliveryStatus.DELIVERED, core.history.message(message.id)!!.status)
            assertEquals(total, core.history.picture(message.id)!!.have)
        }
    }

    @Test fun aReceiptForSomePacketsShowsProgressNotDelivery() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val total = PictureWire.partsForV2(picture.bytes.size) + 1
        Views.waitUntil(8_000) { TestCore.radio.broadcasts.size == total }
        TestCore.radio.broadcasts.take(3).forEach { bea.postman.receive(it.payload, 2_000) }
        deliver(bea.postman.due(60_000).single().packet)
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        TestCore.onMain {
            assertEquals(3, core.history.picture(message.id)!!.have)
            assertNotEquals(DeliveryStatus.DELIVERED, core.history.message(message.id)!!.status)
        }
    }

    @Test fun aFailedPictureRetriesItsStoredPacketsUnchanged() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val before = TestCore.onMain { core.history.outbox(bea.account).map { it.packet.toList() } }
        val session = TestCore.onMain { core.history.sessionRow(bea.account)!! }
        TestCore.onMain {
            core.history.outbox(bea.account).forEach { core.history.setOutboxState(it.id, "FAILED") }
            core.history.setStatus(message.id, DeliveryStatus.FAILED)
            assertTrue(core.resend(message.id))
            assertEquals("the same rows, not new packets", before, core.history.outbox(bea.account).map { it.packet.toList() })
            assertTrue(core.history.outbox(bea.account).all { it.state == "WAITING" || it.state == "SENT" })
            assertTrue("the session did not move", session.second.contentEquals(core.history.sessionRow(bea.account)!!.second))
        }
    }
    /** Real fresh-QR replacement, retaining this contact and the app's persisted message history. */
    private fun replacePictureSession(bea: PersonFixture.Bea) {
        val accept = TestCore.radio.acceptBroadcasts
        TestCore.restart()
        val pair = org.phonemesh.core.session.KeyPairBytes.generate()
        val qr = org.phonemesh.core.account.QrCode(bea.qr.card, pair.public)
        val mine = TestCore.onMain { core.accountsFlow.showCode() }
        val ours = (org.phonemesh.core.account.AddCode.parse(mine) as org.phonemesh.core.account.AddCode.Qr).code
        assertTrue(TestCore.onMain { core.accountsFlow.scanned(org.phonemesh.core.account.AddCode.of(qr)) } is Accounts.Added.Contact)
        bea.store.sessions[bea.awa] = org.phonemesh.core.session.Start.qr(bea.keys, qr, pair, ours)!!
        bea.store.rows.clear()
        TestCore.onMain { TestCore.radio.acceptBroadcasts = accept; TestCore.radio.connected(true) }
    }

    @Test fun aPartlyDeliveredPictureCanBeResentAfterFreshQrReplacement() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val total = PictureWire.partsForV2(picture.bytes.size) + 1
        assertTrue(total > 3)
        Views.waitUntil(8_000) { TestCore.radio.broadcasts.size == total }
        val id = TestCore.onMain { core.history.lastMessage(chatId)!!.id }
        TestCore.radio.broadcasts.take(3).forEach { bea.postman.receive(it.payload, 2_000) }
        deliver(bea.postman.due(60_000).single().packet)
        assertEquals(3, TestCore.onMain { core.history.picture(id)!!.have })
        replacePictureSession(bea)
        TestCore.onMain {
            assertEquals(DeliveryStatus.FAILED, core.history.message(id)!!.status)
            assertTrue(core.resend(id))
        }
        Views.waitUntil(8_000) { TestCore.radio.broadcasts.size == total }
        val got = TestCore.radio.broadcasts.mapNotNull { bea.postman.receive(it.payload, 2_000) }
        val head = PictureWire.decodeV2(got.map { it.body }.filterIsInstance<Body.PictureHead>().single().bytes) as PictureWire.Head
        val parts = got.map { it.body }.filterIsInstance<Body.PicturePart>().map { PictureWire.decodeV2(it.bytes) as PictureWire.Part }.associate { it.index to it.data }
        assertArrayEquals(picture.bytes, PictureJoin.join(head, parts))
        deliver(bea.postman.due(60_000).single().packet)
        TestCore.onMain {
            val live = core.history.outbox(bea.account).filter { it.messageId == id && it.state != "SUPERSEDED" }
            assertEquals(total, live.size)
            assertTrue(live.all { it.state == "DELIVERED" })
            assertEquals(DeliveryStatus.DELIVERED, core.history.message(id)!!.status)
            assertEquals(total, core.history.picture(id)!!.have)
            assertEquals(1, core.history.messages(chatId).count { it.kind == MessageKind.PICTURE })
        }
        // Historical superseded rows must not fail the now-delivered bubble on the next QR replacement.
        replacePictureSession(bea)
        assertEquals(DeliveryStatus.DELIVERED, TestCore.onMain { core.history.message(id)!!.status })
    }

    @Test fun fileMaterializationRecoversWithoutAnUnrelatedPacket() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER block_file_retry BEFORE UPDATE OF file ON pictures BEGIN SELECT RAISE(ABORT, 'injected file failure'); END")
        }
        val (bytes, packets) = beaPicture(bea, 72, 400)
        packets.forEach { deliver(it) }
        val id = pictures(chatId).single().id
        assertNull(fileOf(id))
        TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER block_file_retry") }
        // No receive, reconnect or explicit materializer call: the owned timer must retry local work.
        Views.waitUntil(15_000) { core.history.picture(id)?.file != null }
        assertArrayEquals(bytes, fileOf(id))
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    @Test fun aFullOrphanBudgetNeverEvictsAcknowledgedParts() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        deliver(PersonFixture.send(bea, Body.Text("fr", "hello")))
        val batches = (81..85).map { beaPicture(bea, it, 100) }
        batches.forEach { (_, packets) -> deliver(packets[1]) }
        assertEquals(4L, count("SELECT count(DISTINCT picture_id) FROM picture_pieces"))
        assertEquals(1L, count("SELECT count(*) FROM picture_pieces WHERE picture_id = 81"))
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces WHERE picture_id = 85"))
        // The refused fifth part is not consumed: once heads free space its exact retransmission succeeds.
        batches.forEach { (_, packets) -> deliver(packets[0]) }
        deliver(batches.last().second[1])
        val rows = pictures(chatId)
        assertEquals(5, rows.size)
        rows.zip(batches).forEach { (row, batch) -> assertArrayEquals(batch.first, fileOf(row.id)) }
        assertEquals(0L, count("SELECT count(*) FROM picture_pieces"))
    }

    @Test fun anUnwritablePictureDirectoryRefusesSendWithoutRowsOrRadioTraffic() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        TestCore.onMain {
            val directory = core.picturesDir
            val saved = File(directory.parentFile, "pictures-review-saved")
            assertFalse(saved.exists())
            assertTrue(directory.renameTo(saved))
            try {
                directory.writeText("this fixture is not a directory")
                assertFalse(core.sendPicture(chatId, picture))
                assertTrue(core.history.messages(chatId).isEmpty())
                assertTrue(core.history.outbox(bea.account).isEmpty())
                assertTrue(TestCore.radio.broadcasts.isEmpty())
                assertNotNull(core.lastNote)
            } finally {
                assertTrue(directory.delete())
                assertTrue(saved.renameTo(directory))
            }
        }
    }

    @Test fun anUnreadablePictureFileRefusesResealWithoutThrowing() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val id = TestCore.onMain { core.history.lastMessage(chatId)!!.id }
        replacePictureSession(bea)
        TestCore.onMain {
            val file = File(core.picturesDir, core.history.picture(id)!!.file!!)
            assertTrue(file.setReadable(false, false))
            try {
                assertTrue(file.isFile)
                assertFalse(file.canRead())
                assertFalse(core.resend(id))
                assertEquals(DeliveryStatus.FAILED, core.history.message(id)!!.status)
                assertTrue(core.history.outbox(bea.account).all { it.state == "SUPERSEDED" })
                assertTrue(TestCore.radio.broadcasts.isEmpty())
                assertNotNull(core.lastNote)
            } finally {
                assertTrue(file.setReadable(true, true))
            }
        }
    }

    @Test fun aResealedPartialPictureUpdatesItsReactionReference() = resealedPictureReference(completeBefore = false)
    @Test fun aResealedCompletePictureUpdatesItsReactionReference() = resealedPictureReference(completeBefore = true)

    private fun resealedPictureReference(completeBefore: Boolean) {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val (bytes, first) = beaPicture(bea, 91, 400)
        (if (completeBefore) first else first.take(2)).forEach { deliver(it) }
        val original = pictures(chatId).single()
        replacePictureSession(bea)
        val (_, again) = beaPicture(bea, 91, 400)
        again.forEach { deliver(it) }
        val shown = pictures(chatId).single()
        assertEquals(original.id, shown.id)
        assertArrayEquals(bytes, fileOf(shown.id))
        val ref = Ratchet.reference(again[0].copyOfRange(1, 9))
        assertEquals(AppMailStore.refNumber(ref), shown.packetId)
        assertTrue(TestCore.onMain { core.react(shown.id, "👍") })
        val reactions = mutableListOf<Body.Reaction>()
        var nextPacket = 0
        Views.waitUntil(8_000) {
            while (nextPacket < TestCore.radio.broadcasts.size) {
                val packet = TestCore.radio.broadcasts[nextPacket++].payload
                (bea.postman.receive(packet, 2_000)?.body as? Body.Reaction)?.let { reactions.add(it) }
            }
            reactions.isNotEmpty()
        }
        assertEquals(1, reactions.size)
        assertArrayEquals(ref, reactions.single().ref)
    }


    /** S3: exhausting the random attempts must not return a still-used wire ID. */
    @Test fun aConstantPickerCannotAllocateAnExistingPictureId() {
        TestCore.install()
        val (_, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val used = TestCore.onMain { core.history.picture(core.history.lastMessage(chatId)!!.id)!!.pictureId }
        val next = TestCore.onMain {
            core.history.transaction { PersonPictures(core.history).freshId(chatId) { used } }
        }
        assertTrue("an allocated ID fits the unchanged wire format", next in 0..65535)
        assertNotEquals("64 collisions must not authorize reuse", used, next)
    }

    /** S3: removing our bubble does not remove the other phone's completed picture. */
    @Test fun aDeletedPictureIdIsNotAllocatedAgainAfterRestart() {
        TestCore.install()
        val (_, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val used = TestCore.onMain { core.history.picture(message.id)!!.pictureId }
        assertTrue(TestCore.onMain { core.deleteMessage(message.id) })
        TestCore.restart()
        assertNull(TestCore.onMain { core.history.message(message.id) })
        val next = TestCore.onMain {
            core.history.transaction { PersonPictures(core.history).freshId(chatId) { used } }
        }
        assertNotEquals("deletion and restart cannot recycle a peer-visible ID", used, next)
        assertTrue(next in 0..65535)
    }


    @Test fun aFailedPictureCommitRollsBackItsIdReservation() {
        TestCore.install()
        val (_, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_picture_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'picture failure'); END")
            assertFalse(core.sendPicture(chatId, picture))
            assertTrue(core.history.messages(chatId).isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
        }
        assertEquals(0L, count("SELECT count(*) FROM picture_ids"))
        TestCore.restart()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("DROP TRIGGER reject_picture_outbox")
            assertTrue(core.sendPicture(chatId, picture))
        }
        assertEquals(1L, count("SELECT count(*) FROM picture_ids"))
    }

    @Test fun anExhaustedPictureIdSpaceRefusesWithoutRowsOrTraffic() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("INSERT INTO picture_ids(account_id,used) VALUES(?,?)",
                arrayOf<Any>(bea.account, ByteArray(8192) { 0xff.toByte() }))
            assertFalse(core.sendPicture(chatId, picture))
            assertTrue(core.history.messages(chatId).isEmpty())
            assertTrue(core.history.outbox(bea.account).isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
            assertNotNull(core.lastNote)
        }
    }

    @Test fun deletingTheChatDoesNotRecycleItsPictureIds() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet(accept = false)
        val used = TestCore.onMain { PersonPictures(core.history).freshId(chatId) { 47 } }
        assertEquals(47, used)
        assertTrue(TestCore.onMain { core.deleteChat(chatId) })
        TestCore.restart()
        val next = TestCore.onMain {
            val newChat = core.history.personChat(bea.account, "Bea")
            PersonPictures(core.history).freshId(newChat.id) { used }
        }
        assertNotEquals(used, next)
    }

    @Test fun upgradingVersionTwelveReservesItsExistingOutgoingPictureIds() {
        TestCore.install()
        val (_, chatId) = PersonFixture.meet(accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture) })
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val used = TestCore.onMain {
            val id = core.history.picture(message.id)!!.pictureId
            core.history.writableDatabase.apply { execSQL("DROP TABLE picture_ids"); execSQL("DROP TABLE hub_custody"); execSQL("DROP TABLE hub_cursor"); version = 12 }
            id
        }
        TestCore.restart()
        val next = TestCore.onMain {
            assertEquals(17, core.history.readableDatabase.version)
            assertNotNull(core.history.message(message.id))
            PersonPictures(core.history).freshId(chatId) { used }
        }
        assertNotEquals(used, next)
    }

}
