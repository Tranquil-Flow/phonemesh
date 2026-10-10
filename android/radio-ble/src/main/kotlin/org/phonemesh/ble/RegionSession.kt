package org.phonemesh.ble

import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

/**
 * The region side of one radio link: the LoRa settings this connection's handshake gave, and at most one
 * region write. No Android; [BleMeshTransport] supplies the link. Confined to the transport's work thread,
 * except [region], which any thread may read.
 */
internal class RegionSession(
    private val ready: () -> Boolean,
    private val local: () -> Int?,
    /** Queues one admin message for the local node; false when the link refused it. */
    private val write: (admin: AdminMessage, localNode: Int, wantResponse: Boolean) -> Boolean,
    /** True when the link will take all of [messages] now; asked before the first of them is queued. */
    private val room: (messages: List<AdminMessage>, localNode: Int) -> Boolean,
    private val later: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    /** Delivers a result to the caller (the transport posts it to the main thread). */
    private val finish: (done: (RegionResult) -> Unit, result: RegionResult) -> Unit,
) {
    private class Pending(val code: RegionCode, val done: (RegionResult) -> Unit, val timeout: Runnable)

    @Volatile private var settings: LoRaConfig? = null
    private var pending: Pending? = null

    /**
     * A new connection or handshake starts, or the link ended: settings from before no longer count, so
     * [region] is unknown and nothing is copied from them. A write still waiting ends DROPPED.
     */
    fun reset() {
        settings = null
        pending?.let { waiting ->
            pending = null
            cancel(waiting.timeout)
            finish(waiting.done, RegionResult.DROPPED)
        }
    }

    /** The LoRa settings the radio itself reported on this connection. */
    fun onSettings(lora: LoRaConfig) {
        settings = lora
    }

    /** "UNSET", a region name, "UNRECOGNIZED" (newer firmware), or null until this handshake is complete. */
    fun region(): String? = if (ready()) settings?.region?.name else null

    /** Asks the local radio for its LoRa settings, then writes the region. Throws for a region that is not allowed. */
    fun start(region: String, done: (RegionResult) -> Unit) {
        val code = writable(region)
        val node = local()
        if (!ready() || node == null || pending != null) {
            finish(done, RegionResult.DROPPED)
            return
        }
        val waiting = Pending(code, done, Runnable { complete(null) })
        pending = waiting
        if (!write(RegionWriter.getLora(), node, true)) {
            pending = null
            finish(done, RegionResult.DROPPED)
            return
        }
        later(waiting.timeout, READ_MS)
    }

    /** An admin reply arrived. Only the local node's reply moves a waiting write on. */
    fun onReply(event: RadioEvent.AdminLora) {
        val node = local() ?: return
        if (pending != null && event.from == unsigned(node)) complete(event)
    }

    /** [reply] is the local radio's answer, or null when the wait ran out. */
    private fun complete(reply: RadioEvent.AdminLora?) {
        val waiting = pending ?: return
        pending = null
        cancel(waiting.timeout)
        val node = local()
        if (!ready() || node == null) {
            finish(waiting.done, RegionResult.DROPPED)
            return
        }
        val base = RegionWriter.base(reply, unsigned(node), settings)
        if (base == null) {
            finish(waiting.done, RegionResult.NO_SETTINGS)
            return
        }
        val messages = RegionWriter.messages(base, waiting.code, RegionWriter.passkey(reply, unsigned(node)))
        // All or nothing: DROPPED tells the person the radio was not changed, so no part of the edit may be queued.
        val sent = room(messages, node) && messages.all { message -> write(message, node, false) }
        finish(waiting.done, if (sent) RegionResult.SENT else RegionResult.DROPPED)
    }

    private fun unsigned(node: Int): Long = node.toLong() and MeshtasticProtocol.BROADCAST_NUM

    companion object {
        const val READ_MS = 10_000L

        /** The one gate every region write passes: a name from [RadioRegions.all], or IllegalArgumentException. */
        fun writable(region: String): RegionCode {
            require(region in RadioRegions.all) { "not a writable region: $region" }
            return requireNotNull(RegionWriter.code(region)) { "not a writable region: $region" }
        }
    }
}
