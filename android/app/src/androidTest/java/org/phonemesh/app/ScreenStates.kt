package org.phonemesh.app

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer

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

    fun chatsSetup(): Activity {
        TestCore.install(voices = emptyList(), connected = false)
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, "card_voice") != null, "voice setup card")
        expect(Views.tagged(screen, "step_row:2") != null, "radio step row")
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

    val all: List<Pair<String, () -> Activity>> = listOf(
        "01-language" to { language() },
        "02-chats-setup" to { chatsSetup() },
        "03-chats" to { chatsRows() },
        "04-chats-english" to { chatsRows("en-US") },
        "05-conversation" to { conversation() },
        "06-draft-too-long" to { draftTooLong() },
        "07-recording" to { recordingLocked() },
        "08-radio" to { radio() },
        "09-settings" to { settings() },
    )
}
