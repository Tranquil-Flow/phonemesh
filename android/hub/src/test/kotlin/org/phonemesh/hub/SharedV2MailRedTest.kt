package org.phonemesh.hub

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BEHAVIORAL RED (existing APIs only: Mailbox, Packets.shared, plain bytes). The hub keeps only shared-chat posts it can
 * read; a version-2 post must be kept like a version-1 one, or phones that ask the hub never see replies and reactions.
 */
class SharedV2MailRedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun frame(kind: Int, ref: ByteArray, language: String, text: String): ByteArray =
        byteArrayOf(2, kind.toByte()) + ByteArray(8) { 7 } + byteArrayOf(0, 0, 0, 5) + byteArrayOf(3) + "Awa".toByteArray() +
            byteArrayOf(language.length.toByte()) + language.toByteArray() + ref + text.toByteArray()

    @Test fun aVersionTwoSharedPostIsMailAndAVersionOneStaysMail() {
        val box = Mailbox(folder.root, 30)
        val ref = byteArrayOf(1, 2, 3, 4)
        assertEquals(Mailbox.Kind.SHARED, box.classify(303, frame(1, ByteArray(0), "fr", "salut"))?.first)
        assertEquals(Mailbox.Kind.SHARED, box.classify(303, frame(2, ref, "fr", "salut"))?.first)
        assertEquals(Mailbox.Kind.SHARED, box.classify(303, frame(3, ref, "", "👍"))?.first)
        assertEquals(Mailbox.Kind.SHARED, box.classify(303, Packets.shared("toujours lu"))?.first)
    }
}
