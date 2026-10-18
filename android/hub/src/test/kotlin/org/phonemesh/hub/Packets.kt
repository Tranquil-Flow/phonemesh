package org.phonemesh.hub

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.wire.SharedChatWire
import org.phonemesh.core.wire.WireV2

/** Packets with the right shapes; the hub never opens them, so their sealed parts are filler. */
object Packets {
    fun code(n: Int) = ByteArray(WireV2.CODE) { (n + it).toByte() }
    fun message(n: Int, size: Int = 40): ByteArray = WireV2.Header(code(n), null).encode() + ByteArray(size - WireV2.PLAIN_HEADER) { n.toByte() }
    fun hello(n: Int): ByteArray = byteArrayOf(WireV2.HELLO_BYTE) + ByteArray(120) { n.toByte() }
    fun shared(text: String): ByteArray = SharedChatWire.encode(SharedChatWire.Message(AccountId.fromBytes(ByteArray(8) { 7 }), "Awa", "fr", text))
}

class FakeRadio : Radio {
    val sent = ArrayList<Pair<Int, ByteArray>>()
    override fun broadcast(port: Int, payload: ByteArray) { sent += port to payload.copyOf() }
}
