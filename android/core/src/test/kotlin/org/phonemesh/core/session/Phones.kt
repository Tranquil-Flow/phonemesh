package org.phonemesh.core.session

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body

/** A phone in a conversation, for tests: it stores each new state the way the app must. */
internal class Phone(val name: String, var keys: OwnKeys = SoftKeys.generate()) {
    val card get() = ContactCard.create(keys, name, 1)
    lateinit var state: SessionState
    var newPair: () -> KeyPairBytes = KeyPairBytes::generate
    var now = 0L

    fun seal(body: Body, ack: Ack? = null): Ratchet.Sealed =
        Ratchet.seal(state, Body.encode(body, ack), newPair).also { state = it.state }

    fun send(text: String): Ratchet.Sealed = seal(Body.Text("fr", text))

    fun open(packet: ByteArray): Ratchet.Opened =
        Ratchet.open(state, packet, keys, now).also { if (it is Ratchet.Opened.Delivered) state = it.state }

    fun receive(packet: ByteArray): String? = when (val opened = open(packet)) {
        is Ratchet.Opened.Delivered -> (Body.decodeWithAck(opened.body)!!.body as? Body.Text)?.text
        else -> null
    }

    /** A QR code to show, and the one-time key pair behind it. */
    fun showQr(): Pair<QrCode, KeyPairBytes> = KeyPairBytes.generate().let { QrCode(card, it.public) to it }

    companion object {
        fun qrPair(): Pair<Phone, Phone> {
            val a = Phone("A"); val b = Phone("B")
            val (qa, ka) = a.showQr(); val (qb, kb) = b.showQr()
            a.state = checkNotNull(Start.qr(a.keys, qa, ka, QrCode.decode(qb.encode())!!))
            b.state = checkNotNull(Start.qr(b.keys, qb, kb, QrCode.decode(qa.encode())!!))
            return a to b
        }

        /** The opener, then the link's owner, after the hello arrived. */
        fun linkPair(): Pair<Phone, Phone> {
            val opener = Phone("Opener"); val owner = Phone("Owner")
            val opened = checkNotNull(Start.link(opener.keys, "Opener", 3, owner.card, KeyPairBytes.generate()))
            opener.state = opened.state
            owner.state = checkNotNull(Start.hello(owner.keys, opened.hello)).state
            return opener to owner
        }
    }
}
