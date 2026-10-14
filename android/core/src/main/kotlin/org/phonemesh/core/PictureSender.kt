package org.phonemesh.core

/**
 * Sends a picture's packets strictly one after another: the next goes only when the other radio acknowledged the
 * previous one. A packet that was not acknowledged goes again (new packet id, chosen by the caller), up to [tries]
 * times in all; then the picture has failed. Pure state, no timers: the radio's delivery reports drive it.
 */
class PictureSender(private val packets: List<ByteArray>, private val tries: Int = DEFAULT_TRIES) {
    sealed class Action {
        /** Send packet [index]; [attempt] counts from 1. */
        data class Send(val index: Int, val attempt: Int) : Action()
        data class Finished(val delivered: Boolean) : Action()
    }

    init {
        require(packets.isNotEmpty()) { "a picture has at least its HEAD" }
        require(tries >= 1)
    }

    private var index = 0
    private var attempt = 0
    var finished = false
        private set

    /** Packets the other radio acknowledged so far. */
    val acknowledged: Int get() = index

    val total: Int get() = packets.size

    fun packet(index: Int): ByteArray = packets[index]

    fun start(): Action {
        check(attempt == 0 && !finished) { "already started" }
        attempt = 1
        return Action.Send(0, 1)
    }

    /** The outcome of packet [index]'s latest attempt; null when it is not the one in flight. */
    fun onOutcome(index: Int, acknowledged: Boolean): Action? {
        if (finished || attempt == 0 || index != this.index) return null
        if (acknowledged) {
            this.index++
            if (this.index == packets.size) return finish(true)
            attempt = 1
            return Action.Send(this.index, 1)
        }
        if (attempt >= tries) return finish(false)
        attempt++
        return Action.Send(index, attempt)
    }

    fun cancel(): Action? = if (finished) null else finish(false)

    private fun finish(delivered: Boolean): Action {
        finished = true
        return Action.Finished(delivered)
    }

    companion object {
        const val DEFAULT_TRIES = 3
    }
}
