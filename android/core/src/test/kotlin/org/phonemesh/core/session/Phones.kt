package org.phonemesh.core.session

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.wire.Body

/** Two phones in a conversation, for tests. */
internal class Phone(val name: String, val keys: OwnKeys = SoftKeys.generate()) {
    val card = ContactCard.create(keys, name, 1)
    lateinit var state: SessionState
    var newPair: () -> KeyPairBytes = KeyPairBytes::generate

    fun send(text: String): Ratchet.Sealed = Ratchet.seal(state, Body.encode(Body.Text("fr", text)), newPair)

    fun receive(packet: ByteArray): String? = when (val opened = Ratchet.open(state, packet, keys)) {
        is Ratchet.Opened.Delivered -> (Body.decode(opened.body) as Body.Text).text
        else -> null
    }

    companion object {
        fun qrPair(): Pair<Phone, Phone> {
            val a = Phone("A"); val b = Phone("B")
            a.state = checkNotNull(Start.qr(a.keys, b.card))
            b.state = checkNotNull(Start.qr(b.keys, a.card))
            return a to b
        }
    }
}
