package org.phonemesh.app

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Passes [input] through and calls [report] with the bytes read so far each time another [step]
 * bytes have gone by (once per read, however many steps it crosses), so the screen is updated a
 * few times per pack rather than for every buffer.
 */
class ProgressInputStream(input: InputStream, private val step: Long, private val report: (Long) -> Unit) :
    FilterInputStream(input) {
    private var count = 0L
    private var next = step

    private fun add(n: Long) {
        if (n <= 0) return
        count += n
        if (count >= next) {
            next = (count / step + 1) * step
            report(count)
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    override fun markSupported() = false
}
