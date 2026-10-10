package org.phonemesh.app

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.StoryTimeline

/** Each screen in a realistic state, built on the fake radio and speech. Used by ScreenChecksTest and ScreenshotTour. */
object ScreenStates {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    fun language(): Activity {
        TestCore.install(language = null)
        return Views.start(LanguageActivity::class.java)
    }

    private fun tagsOf(activity: Activity, prefix: String) =
        Views.all(activity).filter { (it.tag as? String)?.startsWith(prefix) == true }

    private fun expect(condition: Boolean, what: String) = check(condition) { "Screen state not reached: $what" }

    /** Finish lifecycle cleanup before the next state clears and reuses the test preferences. */
    fun finish(activity: Activity?) {
        Views.finish(activity)
        if (activity != null) Views.waitUntil { activity.isDestroyed }
    }

    /** The welcome screen held at [t] (StoryTimeline time). */
    fun welcome(t: Float): Activity {
        val screen = language()
        val picture = Views.tagged(screen, "story") as StoryView
        TestCore.onMain { picture.freezeAt(t) }
        inst.waitForIdleSync()
        return screen
    }

    fun languageFromSettings(): Activity {
        TestCore.install(language = "fr")
        val screen = Views.start(LanguageActivity::class.java)
        expect(Views.tagged(screen, "back") != null, "the back arrow")
        return screen
    }

    fun chatsSetup(): Activity = chatsStep(1)

    fun chatsStep(step: Int): Activity {
        require(step in 1..3)
        when (step) {
            1 -> TestCore.install(voices = emptyList(), connected = false)
            2 -> TestCore.install(connected = false)
            else -> TestCore.install()
        }
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, listOf("card_voice", "card_radio", "card_hello")[step - 1]) != null, "step $step card")
        return screen
    }

    fun chatsRadioLost(): Activity {
        TestCore.install(connected = false)
        TestCore.onMain {
            TestCore.core.settings.firstMessageSent = true
            TestCore.core.settings.setupFinished = true
        }
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, "card_radio") != null && Views.tagged(screen, "step_of") == null, "one radio card without numbers")
        return screen
    }

    fun conversationMicHint(): Activity {
        TestCore.install()
        val group = TestCore.onMain {
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] Bonjour", false)
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] On se retrouve au marché ?", false)
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] À midi", false)
            TestCore.core.groupChatId()
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "mic_hint") != null, "the microphone hint")
        expect(tagsOf(screen, "message:").size == 3, "three incoming messages without a first send")
        return screen
    }

    fun recordingTip(): Activity {
        val screen = recordingLocked()
        expect(Views.tagged(screen, "tip:cancel") != null, "the first-recording tip")
        return screen
    }

    fun noteWithPicture(): Activity {
        TestCore.install()
        TestCore.onMain {
            TestCore.core.settings.firstMessageSent = true
            TestCore.core.settings.setupFinished = true
        }
        val screen = Views.start(ChatsActivity::class.java)
        TestCore.onMain { TestCore.core.note(UiText(R.string.pack_damaged)) }
        inst.waitForIdleSync()
        expect(Views.tagged(screen, "howto:damaged") != null, "the note's picture")
        return screen
    }

    fun help(): Activity {
        TestCore.install()
        val screen = Views.start(HelpActivity::class.java)
        expect(Views.tagged(screen, "help:power") != null, "the Help rows")
        return screen
    }

    fun chatsRows(language: String = "fr"): Activity {
        TestCore.install(language = language)
        seedChats()
        TestCore.onMain { TestCore.core.settings.setupFinished = true }
        val screen = Views.start(ChatsActivity::class.java)
        val expected = TestCore.onMain { TestCore.core.chatRows().size }
        expect(expected >= 2, "group and private chat seeded")
        expect(tagsOf(screen, "chat:").size == expected, "one row per chat ($expected)")
        return screen
    }

    fun conversation(): Activity {
        TestCore.install()
        val group = seedChats()
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        val messages = TestCore.onMain { TestCore.core.history.messages(group).size }
        expect(messages >= 4, "seeded messages ($messages)")
        expect(tagsOf(screen, "message:").size == messages, "one bubble per message ($messages)")
        expect(tagsOf(screen, "status:").isNotEmpty(), "a status icon")
        expect(tagsOf(screen, "resend:").isNotEmpty(), "a resend button")
        return screen
    }

    fun draftTooLong(): Activity {
        TestCore.install()
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        TestCore.onMain { TestCore.core.setDraft(group, "Ceci est un très long message ".repeat(8).trim()) }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "too_long") != null, "the too-long notice")
        return screen
    }

    fun draftCut(): Activity {
        TestCore.install()
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        TestCore.speech.nextTranscript = "Ceci est un très long message ".repeat(8).trim()
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        Views.tap(screen, "mic")
        Views.tap(screen, "mic")
        expect(Views.tagged(screen, "draft_cut") != null, "the cut-off notice")
        return screen
    }

    fun recordingLocked(): Activity {
        TestCore.install()
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        Views.tap(screen, "mic") // the screen's own path: startRecording, then locked, then a render
        val started = TestCore.onMain { TestCore.core.recordState == AppCore.RecordState.RECORDING && TestCore.core.recordLocked }
        expect(started, "recording started and locked")
        val stop = screen.getString(R.string.stop_recording)
        expect(Views.tagged(screen, "mic")?.contentDescription?.toString() == stop, "the mic shows '$stop'")
        return screen
    }

    fun radio(): Activity {
        TestCore.install()
        TestCore.onMain { TestCore.core.settings.radioName = "Radio du marché" }
        val screen = Views.start(RadioActivity::class.java)
        TestCore.onMain {
            TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -58)
            TestCore.radio.found("AA:BB:CC:DD:EE:02", "Ama", -77)
        }
        inst.waitForIdleSync()
        expect(tagsOf(screen, "radios").isNotEmpty() && Views.all(screen).any { it is android.widget.ListView && it.childCount >= 2 }, "two radios listed")
        return screen
    }

    fun settings(): Activity {
        TestCore.install()
        TestCore.onMain { Fixtures.store(context).install(Fixtures.engine("base")) }
        return Views.start(SettingsActivity::class.java)
    }

    private const val RADIO = "AA:BB:CC:DD:EE:01"

    /** The country's name in French, the states' language. */
    private fun countryName(code: String) = java.util.Locale("", code).getDisplayCountry(java.util.Locale.FRENCH)

    private fun expectWords(screen: Activity, id: Int, vararg args: Any) {
        val words = screen.getString(id, *args)
        expect(Views.withText(screen, words) != null, "the words '$words'")
    }

    /** Polls from the test thread, for conditions that look things up on the main thread themselves. */
    private fun await(what: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            expect(android.os.SystemClock.uptimeMillis() < end, what)
            Thread.sleep(50)
        }
        inst.waitForIdleSync()
    }

    /** A remembered radio connects reporting [region], with the phone in [country]. Setup step 1 (the voice) is done. */
    private fun radioWith(region: String, country: String?) {
        TestCore.install(connected = false)
        TestCore.access.country = country
        TestCore.onMain {
            TestCore.core.settings.radioAddress = RADIO
            TestCore.core.settings.radioName = "Kofi"
            TestCore.radio.region = region
            TestCore.radio.connected(true)
        }
    }

    /** The radio restarts and its new handshake reports [region]. */
    private fun restartWith(region: String) {
        TestCore.onMain {
            TestCore.radio.connected(false)
            TestCore.radio.region = region
            TestCore.radio.connected(true)
        }
        inst.waitForIdleSync()
    }

    /** The chats screen with the region card showing the button tagged [button]. */
    private fun regionChats(button: String): Activity {
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, "card_region") != null, "the region card")
        expect(Views.tagged(screen, "region_picture") != null, "the region picture")
        expect(Views.tagged(screen, button) != null, "the $button button")
        return screen
    }

    /** A radio with no region, the phone in a supported country: the Set up card. */
    fun regionSetUp(country: String = "TG"): Activity {
        radioWith("UNSET", country)
        val screen = regionChats("region_yes")
        expect(Views.tagged(screen, "region_other") != null, "the Other country button")
        expectWords(screen, R.string.region_set, countryName(country))
        return screen
    }

    /** The same question after setup: a problem card without step numbers. */
    fun regionProblem(): Activity {
        radioWith("UNSET", "TG")
        TestCore.onMain {
            TestCore.core.settings.firstMessageSent = true
            TestCore.core.settings.setupFinished = true
        }
        val screen = regionChats("region_yes")
        expect(Views.tagged(screen, "step_of") == null, "no step numbers")
        return screen
    }

    /** A radio set for another part of the world: the Change card. */
    fun regionChange(): Activity {
        radioWith("US", "TG")
        val screen = regionChats("region_change")
        expect(Views.tagged(screen, "region_keep") != null, "the Keep button")
        expectWords(screen, R.string.region_change_to, countryName("TG"))
        return screen
    }

    /** No country known: the list opens by itself once; closed without a choice, the card leads back to it. */
    fun regionChooseCountry(): Activity {
        radioWith("UNSET", null)
        val screen = Views.start(ChatsActivity::class.java)
        await("the country list opening") { Views.resumed() is RegionActivity }
        val list = Views.resumed()
        Views.finish(list)
        await("the chats screen in front again") { Views.resumed() === screen }
        expect(Views.tagged(screen, "card_region") != null, "the region card")
        expect(Views.tagged(screen, "region_countries") != null, "the country list button")
        expectWords(screen, R.string.region_choose_country)
        return screen
    }

    /** A country without a line in the country file: only the way to the helper list. */
    fun regionHelper(): Activity {
        radioWith("UNSET", "BJ")
        val screen = regionChats("region_helpers")
        expect(Views.tagged(screen, "region_yes") == null, "no Yes button")
        expectWords(screen, R.string.region_unknown, countryName("BJ"))
        return screen
    }

    /** "My country is not here" was chosen. */
    fun regionNotListed(): Activity {
        radioWith("UNSET", "TG")
        TestCore.onMain { TestCore.core.region.choose(org.phonemesh.core.RegionCheck.NOT_LISTED) }
        val screen = regionChats("region_helpers")
        expectWords(screen, R.string.region_unknown_here)
        return screen
    }

    /** Yes was tapped: the radio is restarting and there is nothing to tap. */
    fun regionWorking(): Activity {
        val screen = regionSetUp()
        Views.tap(screen, "region_yes")
        expectWords(screen, R.string.region_working)
        expect(Views.tagged(screen, "region_yes") == null && Views.tagged(screen, "region_retry") == null, "no buttons while working")
        expect(TestCore.radio.regionWrites == listOf("EU_868"), "one write")
        return screen
    }

    /** The radio came back with the new region: two seconds of green. */
    fun regionDone(): Activity {
        val screen = regionSetUp()
        Views.tap(screen, "region_yes")
        restartWith("EU_868")
        expectWords(screen, R.string.region_ready, countryName("TG"))
        return screen
    }

    /** The radio went away after the write and did not return in time. */
    fun regionNoReturn(): Activity {
        val screen = regionSetUp()
        val yes = checkNotNull(Views.tagged(screen, "region_yes"))
        TestCore.onMain {
            TestCore.core.region.returnMs = 0
            yes.performClick()
            TestCore.radio.connected(false)
        }
        Views.waitUntil { TestCore.core.region.phase == RegionPhase.Failed(RegionFailure.NO_RETURN) }
        expectWords(screen, R.string.region_no_return)
        expectWords(screen, R.string.region_no_return_hint)
        expect(Views.tagged(screen, "region_retry")?.let { TestCore.onMain { it.isEnabled } } == true, "Try again ready")
        return screen
    }

    /** The radio came back twice without the new region. */
    fun regionNotTaken(): Activity {
        val screen = regionSetUp()
        Views.tap(screen, "region_yes")
        restartWith("UNSET")
        restartWith("UNSET")
        expect(TestCore.onMain { TestCore.core.region.phase } == RegionPhase.Failed(RegionFailure.NOT_TAKEN), "the write did not take")
        expect(TestCore.radio.regionWrites.size == RegionSetup.MAX_WRITES, "two writes")
        expectWords(screen, R.string.region_not_taken)
        expect(Views.tagged(screen, "region_retry") != null, "Try again")
        return screen
    }

    /** The write never went out. */
    fun regionNotChanged(): Activity {
        val screen = regionSetUp()
        TestCore.onMain { TestCore.radio.regionResult = org.phonemesh.ble.RegionResult.DROPPED }
        Views.tap(screen, "region_yes")
        expectWords(screen, R.string.region_not_changed)
        expect(Views.tagged(screen, "region_retry") != null, "Try again")
        return screen
    }

    /** A draft that cannot go out: the chat screen explains with the same card, and Send is off. */
    fun regionBlockedSend(): Activity {
        radioWith("UNSET", "TG")
        val group = TestCore.onMain {
            TestCore.core.groupChatId().also { TestCore.core.setDraft(it, "Bonjour") }
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "card_region") != null, "the region card")
        expect(Views.tagged(screen, "region_yes") != null, "the Yes button")
        expect(Views.tagged(screen, "draft_send")?.let { TestCore.onMain { it.isEnabled } } == false, "Send is off")
        return screen
    }

    fun regionCountries(): Activity {
        radioWith("UNSET", null)
        val screen = Views.launch<RegionActivity>(RegionActivity.countries(context))
        expect(listOf("country:DE", "country:TG", "country_not_here").all { Views.tagged(screen, it) != null }, "both countries, then not here")
        return screen
    }

    /** The helper list; with [chosen], a region is picked and the box ticked, so Set is on. Nothing is written. */
    fun regionHelpers(chosen: Boolean = false): Activity {
        radioWith("UNSET", "BJ")
        val screen = Views.launch<RegionActivity>(RegionActivity.helpers(context))
        expect(tagsOf(screen, "region:").size >= 3, "the region rows")
        val set = checkNotNull(Views.tagged(screen, "helper_set")) { "Screen state not reached: the Set button" }
        expect(!TestCore.onMain { set.isEnabled }, "Set is off before a choice")
        if (chosen) {
            Views.tap(screen, "region:EU_868")
            val tick = Views.tagged(screen, "helper_tick") as android.widget.CheckBox
            TestCore.onMain { tick.isChecked = true }
            inst.waitForIdleSync()
            expect(Views.tagged(screen, "region:EU_868")?.let { TestCore.onMain { it.isActivated } } == true, "the chosen row is marked")
            expect(TestCore.onMain { set.isEnabled }, "Set is on")
        }
        expect(TestCore.radio.regionWrites.isEmpty(), "nothing written")
        return screen
    }

    /** Two voices heard in the group, a private chat, one group message sent and one failed. */
    fun seedChats(): Long = TestCore.onMain {
        val core = TestCore.core
        val radio = TestCore.radio
        radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"), RadioPeer("!0000bbbb", "Ama")))
        radio.receive("!0000aaaa", "[pm1:fr] On se retrouve au marché à midi ?", false)
        radio.receive("!0000bbbb", "[pm1:fr] D'accord, j'arrive.", false)
        radio.receive("!0000aaaa", "[pm1:fr] Tu as la clé ?", true)
        val group = core.groupChatId()
        core.setDraft(group, "Je suis en route.")
        core.send(group)
        radio.deliver(radio.sent.last().packetId, DeliveryState.QUEUED)
        radio.ready = false
        core.setDraft(group, "Attendez-moi.")
        core.send(group)
        radio.ready = true
        group
    }

    private val states: List<Pair<String, () -> Activity>> = listOf(
        "01-welcome-1" to { welcome(.2f) },
        "01-welcome-3" to { welcome(StoryTimeline.STILL) },
        "01-welcome-5" to { welcome(.85f) },
        "01-welcome-still" to { language() },
        "01-language-from-settings" to { languageFromSettings() },
        "02-chats-step1" to { chatsStep(1) },
        "02-chats-step2" to { chatsStep(2) },
        "02-chats-step3" to { chatsStep(3) },
        "02-chats-radio-lost" to { chatsRadioLost() },
        "03-chats" to { chatsRows() },
        "04-chats-english" to { chatsRows("en-US") },
        "05-conversation" to { conversation() },
        "05-conversation-mic-hint" to { conversationMicHint() },
        "06-draft-too-long" to { draftTooLong() },
        "06-draft-cut" to { draftCut() },
        "07-recording" to { recordingLocked() },
        "07-recording-tip" to { recordingTip() },
        "08-radio" to { radio() },
        "09-settings" to { settings() },
        "10-help" to { help() },
        "11-note-picture" to { noteWithPicture() },
    )

    /** The radio region cards and lists; checked by RegionScreensTest, so each test class stays short. */
    private val regionStates: List<Pair<String, () -> Activity>> = listOf(
        "12-region-set-up-de" to { regionSetUp("DE") },
        "12-region-set-up-tg" to { regionSetUp("TG") },
        "12-region-problem" to { regionProblem() },
        "12-region-change" to { regionChange() },
        "12-region-choose-country" to { regionChooseCountry() },
        "12-region-helper" to { regionHelper() },
        "12-region-not-listed" to { regionNotListed() },
        "12-region-working" to { regionWorking() },
        "12-region-done" to { regionDone() },
        "12-region-no-return" to { regionNoReturn() },
        "12-region-not-taken" to { regionNotTaken() },
        "12-region-not-changed" to { regionNotChanged() },
        "13-region-blocked-send" to { regionBlockedSend() },
        "14-region-countries" to { regionCountries() },
        "15-region-helpers" to { regionHelpers() },
        "15-region-helpers-chosen" to { regionHelpers(chosen = true) },
    )

    /** Force both themes, independently of the emulator's appearance setting. */
    val all: List<Pair<String, () -> Activity>> = states.map { (name, state) ->
        name to { inTheme(ThemeMode.LIGHT, state) }
    }

    val dark: List<Pair<String, () -> Activity>> = states.map { (name, state) ->
        "$name-dark" to { inTheme(ThemeMode.DARK, state) }
    }

    val region: List<Pair<String, () -> Activity>> = regionStates.map { (name, state) ->
        name to { inTheme(ThemeMode.LIGHT, state) }
    }

    val regionDark: List<Pair<String, () -> Activity>> = regionStates.map { (name, state) ->
        "$name-dark" to { inTheme(ThemeMode.DARK, state) }
    }

    private fun inTheme(theme: ThemeMode, state: () -> Activity): Activity {
        val previous = TestCore.nextTheme
        TestCore.nextTheme = theme
        return try { state() } finally { TestCore.nextTheme = previous }
    }
}
