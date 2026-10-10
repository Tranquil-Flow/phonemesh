package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.ble.RadioRegions
import org.phonemesh.ble.RegionResult
import org.phonemesh.core.CountryRegions
import org.phonemesh.core.RegionCheck
import org.phonemesh.core.RegionStep

class RegionSetupTest {
    private class Memory : RegionMemory {
        val keptMap = HashMap<String, MutableSet<String>>()
        val countries = HashMap<String, String?>()
        val helpers = HashSet<String>()
        override fun kept(address: String) = keptMap[address].orEmpty()
        override fun keep(address: String, country: String) { keptMap.getOrPut(address) { HashSet() } += country }
        override fun radioCountry(address: String) = countries[address]
        override fun setRadioCountry(address: String, country: String?) { countries[address] = country }
        override fun helperSet(address: String) = address in helpers
        override fun setHelperSet(address: String, on: Boolean) { if (on) helpers += address else helpers -= address }
    }

    private class Link : RegionLink {
        override var address: String? = "AA"
        override var connected = true
        var radioRegion: String? = "UNSET"
        var result = RegionResult.SENT
        val writes = mutableListOf<String>()
        var reconnects = 0
        /** True keeps a write in flight until [finish], like the real radio's read-then-write. */
        var defer = false
        private var waiting: ((RegionResult) -> Unit)? = null
        override fun region() = if (connected) radioRegion else null
        override fun setRegion(region: String, done: (RegionResult) -> Unit) {
            writes += region
            if (defer) waiting = done else done(result)
        }
        fun finish(with: RegionResult = result) { val done = waiting; waiting = null; done?.invoke(with) }
        /** Like the real link: a new connection tears the old one down, so a write still in flight ends DROPPED. */
        override fun reconnect() { reconnects++; finish(RegionResult.DROPPED) }
    }

    private class Clock : RegionClock {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        override fun after(ms: Long, task: Runnable) { tasks += (now + ms) to task }
        override fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
                tasks.remove(next); now = next.first; next.second.run()
            }
            now = until
        }
    }

    private val countries = CountryRegions.load(
        "[countries]\nTG | EU_868 | h:006a4e | src\nDE | EU_868 | h:000000 | src\nQQ | ITU1_2M | h:000000 | src\n",
    )
    private val memory = Memory()
    private val link = Link()
    private val clock = Clock()
    private var guess: String? = "TG"
    private var changes = 0
    /** The log lines, each with whether it was a warning. */
    private val logged = mutableListOf<Pair<Boolean, String>>()
    private val setup = RegionSetup(countries, memory, link, clock, { guess }, { changes++ }, { warn, line -> logged += warn to line }, { clock.now })

    /** The radio restarts with [region] and the handshake finishes. */
    private fun comeBack(region: String) { link.connected = false; setup.onLost(); link.radioRegion = region; link.connected = true; setup.onReady() }

    @Test fun newRadioIsSetAfterYesAndVerified() {
        assertEquals(RegionStep.AskSet("TG"), setup.step())
        assertTrue(setup.blocksSending)
        setup.yes()
        assertEquals(listOf("EU_868"), link.writes)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        comeBack("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        assertEquals("TG", memory.radioCountry("AA"))
        assertFalse(setup.blocksSending)
        clock.advance(2_000)
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue(setup.settled)
    }

    @Test fun unchangedRadioIsWrittenOnceMoreThenFails() {
        setup.yes()
        comeBack("UNSET")
        assertEquals(2, link.writes.size)
        comeBack("UNSET")
        assertEquals(2, link.writes.size)
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), setup.phase)
    }

    @Test fun radioThatNeverReturnsFailsAfterSixtySecondsAndKeepsTrying() {
        setup.yes()
        link.connected = false; setup.onLost()
        clock.advance(60_000)
        assertEquals(RegionPhase.Failed(RegionFailure.NO_RETURN), setup.phase)
        val before = link.reconnects
        clock.advance(30_000)
        assertTrue(link.reconnects > before)
        link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
    }

    @Test fun verifiesEvenWhenTheLinkNeverDrops() {
        setup.yes()
        clock.advance(7_999)
        assertEquals("not before the radio had its time to restart", 0, link.reconnects)
        clock.advance(1)
        assertEquals(1, link.reconnects)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        // The forced reconnect drops the link; the new handshake carries the region: verified.
        link.connected = false; setup.onLost()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        assertNull("nothing is remembered before the new handshake", memory.radioCountry("AA"))
        link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        assertEquals("TG", memory.radioCountry("AA"))
        assertEquals(listOf("EU_868"), link.writes)
        clock.advance(300_000)
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue(setup.settled)
        assertEquals("no reconnect after the radio is verified", 1, link.reconnects)
        assertTrue(clock.tasks.isEmpty())
    }

    /** The radio never drops and never changes: each write gets its own forced reconnect, and there is one retry only. */
    @Test fun aRadioThatNeverDropsAndNeverChangesIsWrittenOnceMoreThenFails() {
        setup.yes()
        clock.advance(8_000)
        assertEquals(1, link.reconnects)
        comeBack("UNSET")
        assertEquals(listOf("EU_868", "EU_868"), link.writes)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        clock.advance(7_999)
        assertEquals("the retry waits its own eight seconds", 1, link.reconnects)
        clock.advance(1)
        assertEquals(2, link.reconnects)
        comeBack("UNSET")
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), setup.phase)
        assertEquals(2, link.writes.size)
        assertNull(memory.radioCountry("AA"))
        clock.advance(300_000)
        assertEquals(2, link.reconnects)
        assertEquals(2, link.writes.size)
        assertTrue(clock.tasks.isEmpty())
    }

    // ---- S3: a failure card can be left; leaving writes nothing and leaves no timer or reconnect behind

    @Test fun backFromAFailedChangeShowsTheQuestionAgainSoKeepWorks() {
        link.radioRegion = "US"
        link.result = RegionResult.DROPPED
        setup.yes()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        assertFalse(setup.settled)
        val before = changes
        setup.back()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue("the screen is told", changes > before)
        assertEquals(RegionStep.AskChange("TG", "US"), setup.step())
        assertEquals(1, link.writes.size)
        assertTrue(clock.tasks.isEmpty())
        // Try again belongs to the card that was left: it writes nothing now.
        link.result = RegionResult.SENT
        setup.retry()
        assertEquals(1, link.writes.size)
        setup.keep()
        assertTrue(setup.settled)
        assertEquals(setOf("TG"), memory.kept("AA"))
        assertEquals(1, link.writes.size)
        assertEquals(0, link.reconnects)
    }

    @Test fun backFromARadioThatDidNotReturnStopsEveryTimerAndReconnect() {
        setup.yes()
        link.connected = false; setup.onLost()
        clock.advance(60_000)
        assertEquals(RegionPhase.Failed(RegionFailure.NO_RETURN), setup.phase)
        assertTrue("the card's own reconnect loop is running", clock.tasks.isNotEmpty())
        val reconnects = link.reconnects
        setup.back()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue("timers left: ${clock.tasks.size}", clock.tasks.isEmpty())
        clock.advance(300_000)
        assertEquals(reconnects, link.reconnects)
        assertEquals(1, link.writes.size)
        // The radio comes back later, still without a region: the question again, and nothing written by itself.
        link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertEquals(RegionStep.AskSet("TG"), setup.step())
        assertEquals(1, link.writes.size)
        assertNull(memory.radioCountry("AA"))
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun backFromASettingTheRadioDidNotTakeLeadsToTheQuestionAndTheHelperPath() {
        setup.yes()
        comeBack("UNSET")
        comeBack("UNSET")
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), setup.phase)
        setup.back()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertEquals(RegionStep.AskSet("TG"), setup.step())
        assertEquals(2, link.writes.size)
        assertTrue(clock.tasks.isEmpty())
        // A later handshake is not taken for an answer to the write that was given up.
        comeBack("EU_868")
        assertEquals(RegionPhase.Idle, setup.phase)
        assertNull(memory.radioCountry("AA"))
        assertEquals(2, link.writes.size)
    }

    @Test fun aHelperCanSetTheRadioFromTheCardThatSaysAskAHelper() {
        setup.yes()
        comeBack("UNSET")
        comeBack("UNSET")
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), setup.phase)
        setup.helperChoose("EU_433")
        assertEquals(RegionPhase.Working("EU_433", "TG", true), setup.phase)
        assertEquals(listOf("EU_868", "EU_868", "EU_433"), link.writes)
        comeBack("EU_433")
        assertEquals(RegionPhase.Done("EU_433", "TG", true), setup.phase)
        assertTrue(memory.helperSet("AA"))
    }

    @Test fun backDoesNothingUnlessACardHasFailed() {
        setup.back()
        assertEquals(RegionPhase.Idle, setup.phase)
        link.defer = true
        setup.yes()
        setup.back()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        link.finish()
        assertEquals("the wait for the radio is still armed", 2, clock.tasks.size)
        setup.back()
        assertEquals(2, clock.tasks.size)
        comeBack("EU_868")
        setup.back()
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        assertEquals(1, link.writes.size)
    }

    // ---- S2: the log says what was written, who chose it and how it ended

    private fun lines() = logged.map { it.second }

    @Test fun theLogRecordsTheRegionTheCountryAndThatAHelperChoseIt() {
        guess = "BJ"
        link.address = "AA:BB:CC:DD:EE:01"
        setup.helperChoose("EU_868")
        val started = lines().single { "started" in it }
        listOf("region=EU_868", "country=BJ", "helper=yes", "attempt=1").forEach { assertTrue("$it in: $started", it in started) }
        assertTrue(lines().toString(), lines().any { "result" in it && "SENT" in it })
        clock.advance(1_500)
        link.connected = false; setup.onLost()
        assertTrue(lines().toString(), lines().any { "lost" in it && "1500 ms" in it })
        clock.advance(4_000)
        link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertTrue(lines().toString(), lines().any { "returned" in it && "5500 ms" in it })
        val verified = lines().single { "verified" in it }
        listOf("region=EU_868", "country=BJ", "helper=yes").forEach { assertTrue("$it in: $verified", it in verified) }
        assertTrue("nothing here is a warning", logged.none { it.first })
        assertTrue("no radio address in the log", lines().none { "AA:BB" in it || "EE:01" in it })
    }

    @Test fun aNormalSetIsLoggedAsNotAHelpersChoice() {
        setup.yes()
        val started = lines().single { "started" in it }
        listOf("region=EU_868", "country=TG", "helper=no", "attempt=1").forEach { assertTrue("$it in: $started", it in started) }
    }

    @Test fun theLogSaysWhatTheRadioReportedWhenItDidNotTakeTheSetting() {
        link.radioRegion = "US"
        setup.yes()
        comeBack("US")
        assertTrue(lines().toString(), lines().any { "started" in it && "attempt=2" in it })
        comeBack("US")
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), setup.phase)
        val (warn, line) = logged.single { "NOT_TAKEN" in it.second }
        assertTrue("a failure is a warning", warn)
        listOf("wanted=EU_868", "radio=US", "writes=2").forEach { assertTrue("$it in: $line", it in line) }
    }

    @Test fun theLogNamesEveryOtherFailureToo() {
        link.result = RegionResult.DROPPED
        setup.yes()
        assertTrue(lines().toString(), lines().any { "result" in it && "DROPPED" in it })
        assertTrue(logged.toString(), logged.any { it.first && "TRY_AGAIN" in it.second && "wanted=EU_868" in it.second && "radio=UNSET" in it.second })
        setup.back()
        link.result = RegionResult.NO_SETTINGS
        setup.yes()
        assertTrue(lines().toString(), lines().any { "result" in it && "NO_SETTINGS" in it })
        setup.back()
        link.result = RegionResult.SENT
        setup.yes()
        link.connected = false; setup.onLost()
        clock.advance(60_000)
        assertEquals(RegionPhase.Failed(RegionFailure.NO_RETURN), setup.phase)
        assertTrue(logged.toString(), logged.any { it.first && "NO_RETURN" in it.second && "wanted=EU_868" in it.second && "radio=none" in it.second })
    }

    @Test fun droppedOrNoSettingsAskToTryAgain() {
        link.result = RegionResult.DROPPED
        setup.yes()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        link.result = RegionResult.SENT
        setup.retry()
        assertEquals(listOf("EU_868", "EU_868"), link.writes)
        link.result = RegionResult.NO_SETTINGS
        val other = RegionSetup(countries, memory, link, clock, { guess }, {})
        other.yes()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), other.phase)
    }

    @Test fun keepIsRememberedPerRadio() {
        link.radioRegion = "US"
        assertEquals(RegionStep.AskChange("TG", "US"), setup.step())
        setup.keep()
        assertEquals(RegionStep.Ready, setup.step())
        assertEquals(setOf("TG"), memory.kept("AA"))
        assertTrue(link.writes.isEmpty())
    }

    @Test fun helperChoiceMarksTheRadio() {
        guess = "BJ"
        assertEquals(RegionStep.AskHelper("BJ"), setup.step())
        setup.helperChoose("EU_868")
        comeBack("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "BJ", true), setup.phase)
        assertTrue(memory.helperSet("AA"))
        assertEquals("BJ", memory.radioCountry("AA"))
        guess = "TG"; link.radioRegion = "US"
        assertEquals(RegionStep.Ready, setup.step())
    }

    @Test fun notListedHelperChoiceStoresNoCountry() {
        guess = null
        assertEquals(RegionStep.ChooseCountry, setup.step())
        setup.choose(RegionCheck.NOT_LISTED)
        assertEquals(RegionStep.AskHelper(null), setup.step())
        setup.helperChoose("EU_868")
        comeBack("EU_868")
        assertNull(memory.radioCountry("AA"))
        assertTrue(memory.helperSet("AA"))
    }

    @Test fun aNormalSetClearsAnEarlierHelperMark() {
        memory.helpers += "AA"
        setup.yes()
        comeBack("EU_868")
        assertFalse(memory.helperSet("AA"))
    }

    @Test fun chosenCountryReplacesTheGuess() {
        setup.choose("de")
        assertEquals(RegionStep.AskSet("DE"), setup.step())
    }

    @Test fun anotherRadioCancelsTheWait() {
        setup.yes()
        link.connected = false; setup.onLost()
        link.address = "BB"; link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertNull(memory.radioCountry("BB"))
        clock.advance(60_000)
        assertEquals(RegionPhase.Idle, setup.phase)
    }

    @Test fun countryCodesAreNormalized() {
        guess = " tg "
        assertEquals("TG", setup.country())
        guess = ""
        assertNull(setup.country())
    }

    @Test fun nothingIsAskedWhileDisconnected() {
        link.connected = false
        assertEquals(RegionStep.Ready, setup.step())
        assertFalse(setup.blocksSending)
    }

    @Test fun yesDoesNothingWhenThereIsNothingToSet() {
        link.radioRegion = "EU_868"
        setup.yes()
        assertTrue(link.writes.isEmpty())
    }

    // ---- D1: no reconnect or give-up timer of an earlier attempt survives a failure or a retry

    @Test fun aFailedWriteAfterALostLinkLeavesNoTimerBehindAndTryAgainWorks() {
        setup.yes()
        link.connected = false; setup.onLost()
        clock.advance(1_000)
        // The radio is back unchanged; the second write does not go out.
        link.result = RegionResult.DROPPED
        link.connected = true; setup.onReady()
        assertEquals(2, link.writes.size)
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        assertTrue("timers left: ${clock.tasks.size}", clock.tasks.isEmpty())
        // The link goes again while the card waits: nothing reconnects by itself any more.
        link.connected = false; setup.onLost()
        val reconnects = link.reconnects
        clock.advance(30_000)
        assertEquals(reconnects, link.reconnects)
        assertTrue(clock.tasks.isEmpty())
        // The person connects again and taps Try again; the write stays in flight past every old deadline.
        link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        link.result = RegionResult.SENT
        link.defer = true
        setup.retry()
        assertEquals(3, link.writes.size)
        clock.advance(40_000)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        assertEquals(reconnects, link.reconnects)
        link.finish()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        assertEquals(2, clock.tasks.size)
        comeBack("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        clock.advance(RegionSetup.DONE_MS)
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue(clock.tasks.isEmpty())
        clock.advance(300_000)
        assertEquals(reconnects, link.reconnects)
        assertEquals(3, link.writes.size)
    }

    @Test fun aLinkLostDuringTheWriteArmsNothingWhicheverReportComesFirst() {
        link.defer = true
        setup.yes()
        link.connected = false; setup.onLost()
        link.finish(RegionResult.DROPPED)
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        assertTrue(clock.tasks.isEmpty())
        val other = RegionSetup(countries, memory, link, clock, { guess }, {})
        link.connected = true
        other.yes()
        link.finish(RegionResult.DROPPED)
        link.connected = false; other.onLost()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), other.phase)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun tryAgainWhileTheRadioIsAwayReconnectsAndWritesNothing() {
        link.result = RegionResult.DROPPED
        setup.yes()
        link.connected = false; setup.onLost()
        setup.retry()
        assertEquals(1, link.reconnects)
        assertEquals(1, link.writes.size)
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun tryAgainNeverWritesToAnotherRadio() {
        link.result = RegionResult.DROPPED
        setup.yes()
        link.result = RegionResult.SENT
        link.address = "BB"
        setup.retry()
        assertEquals(1, link.writes.size)
        assertEquals(RegionPhase.Idle, setup.phase)
        setup.retry()
        assertEquals(1, link.writes.size)
    }

    @Test fun anotherRadioEndsATryAgainCard() {
        link.result = RegionResult.DROPPED
        setup.yes()
        link.connected = false; setup.onLost()
        link.address = "BB"; link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Idle, setup.phase)
    }

    @Test fun forgettingTheRadioEndsTheWait() {
        setup.yes()
        link.address = null; link.connected = false; setup.onLost()
        assertEquals(RegionPhase.Idle, setup.phase)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun aSecondTapWhileWorkingWritesNothing() {
        link.defer = true
        setup.yes()
        setup.yes()
        setup.helperChoose("US")
        setup.retry()
        assertEquals(listOf("EU_868"), link.writes)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
    }

    // ---- D2: only an allowed region is ever handed to the radio

    @Test fun helperCannotChooseARegionThatIsNotAllowed() {
        guess = "BJ"
        val refused = listOf(
            "UNSET", "UNRECOGNIZED", "ITU1_2M", "ITU2_2M", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM",
            "", "eu_868", "NOWHERE",
        )
        refused.forEach { region ->
            assertFalse(region, region in RadioRegions.all)
            setup.helperChoose(region)
            assertTrue(region, link.writes.isEmpty())
            assertEquals(region, RegionPhase.Idle, setup.phase)
        }
        assertFalse(memory.helperSet("AA"))
        setup.helperChoose("EU_868")
        assertEquals(listOf("EU_868"), link.writes)
        assertEquals(RegionPhase.Working("EU_868", "BJ", true), setup.phase)
    }

    @Test fun aCountryLineWithARegionThatIsNotAllowedIsNeverWritten() {
        guess = "QQ"
        assertEquals(RegionStep.AskSet("QQ"), setup.step())
        setup.yes()
        assertTrue(link.writes.isEmpty())
        assertEquals(RegionPhase.Idle, setup.phase)
    }

    @Test fun helperChoiceNeedsAConnectedRadio() {
        link.connected = false
        setup.helperChoose("EU_868")
        assertTrue(link.writes.isEmpty())
        assertEquals(RegionPhase.Idle, setup.phase)
    }

    // ---- D4: only a region read after a new handshake verifies a write

    @Test fun aRegionReadWithoutANewHandshakeVerifiesNothing() {
        setup.yes()
        // The same connection now shows the wanted region: that is not proof the radio restarted with it.
        link.radioRegion = "EU_868"
        setup.onReady()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        assertNull(memory.radioCountry("AA"))
        // Positive control: the same value after a real restart counts.
        comeBack("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        assertEquals("TG", memory.radioCountry("AA"))
    }

    @Test fun anOldRegionReadWithoutANewHandshakeWritesNothingAgain() {
        setup.yes()
        setup.onReady()
        setup.onReady()
        assertEquals(1, link.writes.size)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        // The retry's own check also waits for the restart after that retry.
        comeBack("UNSET")
        assertEquals(2, link.writes.size)
        link.radioRegion = "EU_868"
        setup.onReady()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        comeBack("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
    }

    // ---- D5: nothing is sent while a region write is running

    @Test fun sendingIsBlockedWhileASetRadioIsBeingChanged() {
        link.radioRegion = "US"
        assertFalse(setup.blocksSending)
        setup.yes()
        assertEquals(RegionPhase.Working("EU_868", "TG", false), setup.phase)
        assertTrue(setup.blocksSending)
        link.connected = false; setup.onLost()
        assertTrue(setup.blocksSending)
        link.radioRegion = "EU_868"; link.connected = true; setup.onReady()
        assertEquals(RegionPhase.Done("EU_868", "TG", false), setup.phase)
        assertFalse(setup.blocksSending)
    }

    @Test fun aFailedChangeOfASetRadioDoesNotBlockSending() {
        link.radioRegion = "US"
        link.result = RegionResult.DROPPED
        setup.yes()
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), setup.phase)
        assertFalse(setup.blocksSending)
    }
}
