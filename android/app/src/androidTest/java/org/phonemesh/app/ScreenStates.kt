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

    /** Force both themes, independently of the emulator's appearance setting. */
    val all: List<Pair<String, () -> Activity>> = states.map { (name, state) ->
        name to { inTheme(ThemeMode.LIGHT, state) }
    }

    val dark: List<Pair<String, () -> Activity>> = states.map { (name, state) ->
        "$name-dark" to { inTheme(ThemeMode.DARK, state) }
    }

    private fun inTheme(theme: ThemeMode, state: () -> Activity): Activity {
        val previous = TestCore.nextTheme
        TestCore.nextTheme = theme
        return try { state() } finally { TestCore.nextTheme = previous }
    }
}
