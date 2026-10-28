package org.phonemesh.app

/**
 * Lets a busy worker report progress without flooding the screen's thread (B4-5): [post] gets a whole percent only
 * when it changed and at least [minGapMs] passed since the last one, and always the final 100. A job of a million
 * records posts at most 101 times, whatever the callback rate. Called from one worker thread.
 */
class ProgressGate(
    private val minGapMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
    private val post: (Int) -> Unit,
) {
    private var last = -1
    private var lastAt = Long.MIN_VALUE

    fun update(percent: Int) {
        val p = percent.coerceIn(0, 100)
        if (p == last) return
        val now = clock()
        if (p < 100 && lastAt != Long.MIN_VALUE && now - lastAt < minGapMs) return
        last = p
        lastAt = now
        post(p)
    }

    /** [done] of [total] units as a percent. */
    fun update(done: Long, total: Long) = update(if (total <= 0) 0 else (100.0 * minOf(done, total) / total).toInt())

    /** The percent of a part of the job: [from]..[to] of the whole. */
    fun share(from: Int, to: Int, done: Long, total: Long) = update(if (total <= 0) from else from + ((to - from) * minOf(1.0, done.toDouble() / total)).toInt())
}
