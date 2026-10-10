package org.phonemesh.app

import org.phonemesh.ble.RadioRegions
import org.phonemesh.ble.RegionResult
import org.phonemesh.core.CountryRegions
import org.phonemesh.core.RegionCheck
import org.phonemesh.core.RegionStep
import java.util.Locale

/** What the app remembers per radio (Settings). */
interface RegionMemory {
    fun kept(address: String): Set<String>
    fun keep(address: String, country: String)
    fun radioCountry(address: String): String?
    fun setRadioCountry(address: String, country: String?)
    fun helperSet(address: String): Boolean
    fun setHelperSet(address: String, on: Boolean)
}

/** The connected radio, as RegionSetup needs it (AppCore). */
interface RegionLink {
    val address: String?
    val connected: Boolean
    fun region(): String?
    fun setRegion(region: String, done: (RegionResult) -> Unit)
    fun reconnect()
}

/** Delayed work on the main thread; tests run it by hand. */
interface RegionClock {
    fun after(ms: Long, task: Runnable)
    fun cancel(task: Runnable)
}

enum class RegionFailure { NO_RETURN, NOT_TAKEN, TRY_AGAIN }

sealed interface RegionPhase {
    data object Idle : RegionPhase
    data class Working(val region: String, val country: String?, val helper: Boolean) : RegionPhase
    data class Done(val region: String, val country: String?, val helper: Boolean) : RegionPhase
    data class Failed(val why: RegionFailure) : RegionPhase
}

/**
 * Spec "The flow after a write": write, let the radio restart, reconnect, read the region again, retry once.
 * A write starts only from [yes], [helperChoose] or [retry], and only for a region in [RadioRegions.all]. Main thread.
 */
class RegionSetup(
    countries: CountryRegions,
    private val memory: RegionMemory,
    private val link: RegionLink,
    private val clock: RegionClock,
    private val guess: () -> String?,
    private val changed: () -> Unit,
) {
    private val check = RegionCheck(countries)
    private val countryList = countries
    private var picked: String? = null
    private var target: RegionPhase.Working? = null
    private var targetAddress: String? = null
    private var writes = 0
    /** The link dropped after the last write, so the next ready report carries a new handshake's settings. */
    private var fresh = false

    var phase: RegionPhase = RegionPhase.Idle
        private set
    // Test hook: tests shorten the wait for the radio's return.
    internal var returnMs = RETURN_MS

    private val nudge = Runnable { if (phase is RegionPhase.Working && link.connected) link.reconnect() }
    private val giveUp = Runnable {
        if (phase is RegionPhase.Working) {
            clock.cancel(nudge)
            set(RegionPhase.Failed(RegionFailure.NO_RETURN))
        }
    }
    private val tryReconnect = object : Runnable {
        override fun run() {
            if (target == null) return
            if (link.address != targetAddress) { stop(RegionPhase.Idle); return }
            if (!link.connected) link.reconnect()
            clock.after(RECONNECT_MS, this)
        }
    }
    private val idle = Runnable { if (phase is RegionPhase.Done) set(RegionPhase.Idle) }

    /** The chosen country, else the phone's guess; upper case; null when unknown. */
    fun country(): String? = (picked ?: guess())?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun step(): RegionStep {
        val address = link.address
        if (!link.connected || address == null) return RegionStep.Ready
        return check.decide(link.region(), country(), memory.kept(address), memory.helperSet(address))
    }

    /** Nothing to ask and nothing running: setup step 2 counts as done. */
    val settled: Boolean get() = phase == RegionPhase.Idle && step() == RegionStep.Ready

    /** No message goes out while a region write is running, or while the connected radio has no region. */
    val blocksSending: Boolean
        get() = phase is RegionPhase.Working || (link.connected && link.region() == RegionCheck.UNSET)

    /** From the country list; [RegionCheck.NOT_LISTED] for "My country is not here". */
    fun choose(country: String?) {
        picked = country
        changed()
    }

    /** Yes on the Set up card, Change on the Change card. */
    fun yes() {
        if (phase is RegionPhase.Working) return
        val country = when (val now = step()) {
            is RegionStep.AskSet -> now.country
            is RegionStep.AskChange -> now.country
            else -> return
        }
        val line = countryList.country(country) ?: return
        if (line.region !in RadioRegions.all) return
        start(RegionPhase.Working(line.region, line.code, helper = false))
    }

    fun keep() {
        val now = step() as? RegionStep.AskChange ?: return
        memory.keep(link.address ?: return, now.country)
        changed()
    }

    /** For helpers → Set. A region outside [RadioRegions.all] is refused here, before anything reaches the radio. */
    fun helperChoose(region: String) {
        if (phase is RegionPhase.Working || !link.connected || link.address == null) return
        if (region !in RadioRegions.all) return
        val country = country()?.takeIf { it != RegionCheck.NOT_LISTED }
        start(RegionPhase.Working(region, country, helper = true))
    }

    /** Try again on a failure card. With the radio away it only reconnects; the next tap writes. */
    fun retry() {
        val last = target ?: return
        if (phase !is RegionPhase.Failed) return
        if (link.address != targetAddress) { stop(RegionPhase.Idle); return }
        if (!link.connected) { link.reconnect(); return }
        start(last)
    }

    fun onReady() {
        val working = target ?: return
        if (link.address != targetAddress) { stop(RegionPhase.Idle); return }
        // The write never went out: a reconnect is not a verification; the card keeps offering Try again.
        if ((phase as? RegionPhase.Failed)?.why == RegionFailure.TRY_AGAIN) return
        // Without a restart since the write, the region on hand is not the radio's answer to it.
        if (!fresh) return
        if (link.region() == working.region) {
            val address = targetAddress ?: return
            memory.setRadioCountry(address, working.country)
            memory.setHelperSet(address, working.helper)
            stop(RegionPhase.Done(working.region, working.country, working.helper))
            clock.after(DONE_MS, idle)
        } else if (writes < MAX_WRITES) {
            write(working)
        } else {
            stop(RegionPhase.Failed(RegionFailure.NOT_TAKEN))
        }
    }

    fun onLost() {
        if (target == null) return
        if (link.address != targetAddress) { stop(RegionPhase.Idle); return }
        fresh = true
        clock.cancel(nudge)
        clock.cancel(tryReconnect)
        // Only a wait for the radio's return reconnects by itself; a Try again card leaves the link alone.
        val waiting = phase is RegionPhase.Working || (phase as? RegionPhase.Failed)?.why == RegionFailure.NO_RETURN
        if (waiting) clock.after(FIRST_RECONNECT_MS, tryReconnect)
    }

    private fun start(working: RegionPhase.Working) {
        target = working
        targetAddress = link.address
        writes = 0
        write(working)
    }

    private fun write(working: RegionPhase.Working) {
        // Timers of an earlier attempt must not reconnect or give up under this write.
        cancelTimers()
        clock.cancel(idle)
        fresh = false
        writes++
        set(working)
        link.setRegion(working.region) { result ->
            if (target !== working || phase != working) return@setRegion
            when (result) {
                RegionResult.SENT -> {
                    clock.cancel(giveUp)
                    clock.cancel(nudge)
                    clock.after(returnMs, giveUp)
                    clock.after(NUDGE_MS, nudge)
                }
                RegionResult.DROPPED, RegionResult.NO_SETTINGS -> stop(RegionPhase.Failed(RegionFailure.TRY_AGAIN))
            }
        }
    }

    private fun cancelTimers() = listOf(nudge, giveUp, tryReconnect).forEach { clock.cancel(it) }

    /** Ends the wait and every timer. A [RegionPhase.Failed] keeps [target] so Try again and a late return still work. */
    private fun stop(next: RegionPhase) {
        cancelTimers()
        if (next !is RegionPhase.Failed) { target = null; targetAddress = null }
        set(next)
    }

    private fun set(next: RegionPhase) {
        phase = next
        if (next is RegionPhase.Failed && next.why == RegionFailure.NO_RETURN) {
            clock.cancel(tryReconnect)
            clock.after(RECONNECT_MS, tryReconnect)
        }
        changed()
    }

    companion object {
        const val RETURN_MS = 60_000L
        const val NUDGE_MS = 8_000L
        const val FIRST_RECONNECT_MS = 3_000L
        const val RECONNECT_MS = 10_000L
        const val DONE_MS = 2_000L
        const val MAX_WRITES = 2
    }
}
