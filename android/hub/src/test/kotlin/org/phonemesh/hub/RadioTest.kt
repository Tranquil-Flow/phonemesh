package org.phonemesh.hub

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class RadioTest {
    @Test fun framesSurviveAnySplitAndLogTextBetweenThem() {
        val a = Framing.frame(byteArrayOf(1, 2, 3)); val b = Framing.frame(ByteArray(300) { it.toByte() })
        val stream = "boot ok\r\n".toByteArray() + a + "INFO | x\n".toByteArray() + byteArrayOf(0x94.toByte(), 0x00) + b
        for (chunk in listOf(1, 3, 7, stream.size)) {
            val frames = ArrayList<ByteArray>(); val lines = ArrayList<String>()
            val reader = Framing.Reader()
            stream.toList().chunked(chunk).forEach { reader.feed(it.toByteArray(), frames = { f -> frames += f }, lines = { l -> lines += l }) }
            assertEquals(2, frames.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), frames[0])
            assertEquals(300, frames[1].size)
            assertEquals(listOf("boot ok", "INFO | x"), lines)
        }
    }

    @Test fun aBadLengthResynchronises() {
        val frames = ArrayList<ByteArray>()
        Framing.Reader().feed(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x7f, 0x7f) + Framing.frame(byteArrayOf(9)), frames = { frames += it })
        assertEquals(1, frames.size)
    }

    /** Checked against the Semtech formula in field-mesh-communications-planning's lora_airtime.py (LongFast). */
    @Test fun airtimeMatchesTheSemtechFormula() {
        val airtime = Airtime()
        for ((bytes, ms) in listOf(1 to 436.224, 36 to 681.984, 202 to 1910.784, 228 to 2115.584)) assertEquals(ms, airtime.millis(bytes), 0.01)
    }

    private fun fromRadio(build: MeshProtos.FromRadio.Builder.() -> Unit) = Framing.frame(MeshProtos.FromRadio.newBuilder().apply(build).build().toByteArray())

    @Test fun theSerialRadioAsksForConfigReportsItsModeAndPassesUpOurBroadcasts() {
        val toHub = PipedOutputStream(); val hubIn = PipedInputStream(toHub, 8192)
        val written = ByteArrayOutputStream()
        val data = ArrayList<Pair<Int, ByteArray>>(); var mode: ConfigProtos.Config.DeviceConfig.RebroadcastMode? = null; var node = 0L
        val radio = SerialRadio(hubIn, written, object : RadioListener {
            override fun onData(port: Int, payload: ByteArray, signal: String) { synchronized(data) { data += port to payload } }
            override fun onRebroadcastMode(m: ConfigProtos.Config.DeviceConfig.RebroadcastMode) { mode = m }
            override fun onReady(nodeNum: Long) { node = nodeNum }
        })
        radio.start(configId = 77)
        val out = written.toByteArray()
        assertTrue("wakes first", out.copyOf(32).all { it == 0xC3.toByte() })
        val frames = ArrayList<ByteArray>()
        Framing.Reader().feed(out.copyOfRange(32, out.size), frames = { frames += it })
        assertEquals(77, MeshProtos.ToRadio.parseFrom(frames.single()).wantConfigId)

        fun packet(port: Int, to: Int, payload: ByteArray) = fromRadio {
            setPacket(MeshProtos.MeshPacket.newBuilder().setFrom(0x1234).setTo(to).setId(5).setDecoded(
                MeshProtos.Data.newBuilder().setPortnumValue(port).setPayload(ByteString.copyFrom(payload))))
        }
        toHub.write(fromRadio { setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(0xabcdef)) })
        toHub.write(fromRadio { setConfig(ConfigProtos.Config.newBuilder().setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().setRebroadcastMode(ConfigProtos.Config.DeviceConfig.RebroadcastMode.KNOWN_ONLY))) })
        toHub.write(packet(301, -1, byteArrayOf(1)))
        toHub.write(packet(303, -1, byteArrayOf(3)))
        toHub.write(packet(300, -1, byteArrayOf(0)))      // pictures are not hub mail
        toHub.write(packet(301, 0x55, byteArrayOf(2)))    // to one radio: not ours
        toHub.write(packet(1, -1, "hi".toByteArray()))   // a text
        toHub.flush()
        val until = System.currentTimeMillis() + 5000
        while (synchronized(data) { data.size } < 2 && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(listOf(301, 303), synchronized(data) { data.map { it.first } })
        assertEquals(ConfigProtos.Config.DeviceConfig.RebroadcastMode.KNOWN_ONLY, mode)
        assertEquals(0xabcdefL, node)

        written.reset()
        radio.broadcast(302, byteArrayOf(1, 1) + ByteArray(8))
        val sent = ArrayList<ByteArray>()
        Framing.Reader().feed(written.toByteArray(), frames = { sent += it })
        val packet = MeshProtos.ToRadio.parseFrom(sent.single()).packet
        assertEquals(302, packet.decoded.portnumValue)
        assertEquals(-1, packet.to)
        assertTrue(!packet.wantAck)
        radio.stop()
    }
}
