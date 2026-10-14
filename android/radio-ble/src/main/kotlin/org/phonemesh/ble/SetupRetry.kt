package org.phonemesh.ble

/**
 * Tries a radio connection again when it fails while it is being set up, before the radio is ready: Android's
 * status 133, a link that drops, or a step that times out. Android 10 (the Fairphone) does this often; one tap
 * should still be enough. Two more tries, [delaysMs] apart; a connection that was ready and then dropped is not
 * this class's business. Confined to the radio worker; pure logic behind seams, tested without Bluetooth.
 */
internal class SetupRetry(
    private val later: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val again: () -> Unit,
    private val delaysMs: List<Long> = DELAYS_MS,
) {
    private var tries = 0
    private var pending: Runnable? = null

    /** A new connection attempt: every try is available again. */
    fun begin() {
        end()
        tries = 0
    }

    /** A setup failure: true when another try is scheduled, false when the attempt is over. */
    fun failed(): Boolean {
        if (tries >= delaysMs.size) return false
        val task = Runnable { pending = null; again() }
        pending?.let(cancel)
        pending = task
        later(task, delaysMs[tries++])
        return true
    }

    /** The attempt ended another way (ready, the user, a new connection, the transport closing). */
    fun end() {
        pending?.let(cancel)
        pending = null
    }

    val waiting: Boolean get() = pending != null

    companion object {
        val DELAYS_MS = listOf(1_000L, 3_000L)
    }
}
