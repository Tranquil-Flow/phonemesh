package org.phonemesh.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.RecordGesture
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
        expect(listOf("chats", "empty", "new_chat").none { Views.tagged(screen, it) != null }, "only the steps: no chat list, hint or plus")
        return screen
    }

    /** Step 1 on a phone whose download could not reach the internet: the friend is the filled button. */
    /** Step 2: say your name (the voice is ready, no name yet). */
    fun chatsStepName(): Activity {
        TestCore.install(connected = false, named = false)
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, "card_name") != null && Views.tagged(screen, "name_open") != null, "the name step and its button")
        return screen
    }

    /** The name screen: empty, or with a heard name and a colour picked. */
    fun nameScreen(filled: Boolean): Activity {
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        if (filled) {
            val field = Views.tagged(screen, "name_field") as android.widget.EditText
            TestCore.onMain { field.setText("Aminata") }
            Views.tap(screen, "colour_4")
            val save = Views.tagged(screen, "name_save")!!
            expect(TestCore.onMain { save.isEnabled }, "Save is on")
        }
        return screen
    }

    fun chatsStepOneOffline(): Activity {
        TestCore.install(voices = emptyList(), connected = false)
        TestCore.access.offline = true
        val screen = Views.start(ChatsActivity::class.java)
        expect(Views.tagged(screen, "card_voice") != null, "step 1 card")
        val friend = Views.tagged(screen, "voice_file") as? android.widget.Button
        val download = Views.tagged(screen, "voice_download") as? android.widget.Button
        val background = TestCore.onMain { Palette(screen).background }
        expect(friend != null && TestCore.onMain { friend.currentTextColor } == background, "the friend button is filled")
        expect(download != null && TestCore.onMain { download.currentTextColor } != background, "the Download button is outlined")
        return screen
    }

    /** Step 1 after "From a friend" was tapped: the two-picture guide, Choose the file, Back. */
    fun chatsFriendGuide(): Activity {
        val screen = chatsStep(1)
        Views.tap(screen, "voice_file")
        expect(Views.tagged(screen, "voice_guide") != null, "the friend guide")
        expect(Views.tagged(screen, "howto:friend") != null && Views.tagged(screen, "howto:allow") != null, "both guide pictures")
        expect(Views.tagged(screen, "voice_pick") != null && Views.tagged(screen, "voice_guide_back") != null, "Choose the file and Back")
        expect(Views.resumed() === screen, "no picker opened")
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
        expect(listOf("chats", "empty", "new_chat").all { Views.tagged(screen, it) != null }, "the chat list, the hint and the plus")
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

    /** A finger holds the microphone: only then does sliding left cancel, so only then is it taught. */
    fun recordingTip(): Activity {
        TestCore.install()
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        val mic = checkNotNull(Views.tagged(screen, "mic"))
        TestCore.onMain {
            val down = MotionEvent.obtain(1_000, 1_000, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
            mic.dispatchTouchEvent(down)
            down.recycle()
        }
        Thread.sleep(RecordGesture.TAP_MS + 200) // past a tap: only a hold is taught its gestures
        inst.waitForIdleSync()
        val held = TestCore.onMain { TestCore.core.recordState == AppCore.RecordState.RECORDING && !TestCore.core.recordLocked }
        expect(held, "recording held by a finger")
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

    fun help(group: Int = 0): Activity {
        TestCore.install()
        val screen = Views.start(HelpActivity::class.java)
        expect(Views.tagged(screen, "help:power") != null, "the Help rows")
        if (group > 0) {
            val title = checkNotNull(Views.tagged(screen, "help_group:$group"))
            TestCore.onMain { (screen.window.decorView.let { Views.descendants(it) }.first { it is android.widget.ScrollView } as android.widget.ScrollView).scrollTo(0, title.top) }
            inst.waitForIdleSync()
        }
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

    /** The chat as it is day to day: its one-time tips have been seen (conversationTip shows one). */
    fun conversation(): Activity {
        TestCore.install()
        val group = seedChats()
        TestCore.onMain { TestCore.core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet() }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        val messages = TestCore.onMain { TestCore.core.history.messages(group).size }
        expect(messages >= 4, "seeded messages ($messages)")
        expect(tagsOf(screen, "message:").size == messages, "one bubble per message ($messages)")
        expect(tagsOf(screen, "status:").isNotEmpty(), "a status icon")
        expect(tagsOf(screen, "resend:").isNotEmpty(), "a resend button")
        return screen
    }

    /** A reply being written (the bar above the draft), a reply bubble with its quote, and reactions under a message. */
    fun conversationReply(): Activity {
        TestCore.install()
        val group = TestCore.onMain {
            val core = TestCore.core
            TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"), RadioPeer("!0000bbbb", "Ama"), RadioPeer("!0000cccc", "Yao")))
            core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet()
            core.settings.firstMessageSent = true
            val group = core.groupChatId()
            val now = System.currentTimeMillis()
            core.history.addIncoming(group, "!0000aaaa", "On se retrouve au marché à midi ?", "fr", now - 60_000, false, packetId = 500)
            core.history.addReaction(group, "!0000bbbb", "👍", 500, now - 50_000)
            core.history.addReaction(group, "!0000cccc", "👍", 500, now - 45_000)
            core.history.addReaction(group, null, "❤️", 500, now - 40_000)
            val answer = core.history.addIncoming(group, "!0000bbbb", "Oui, je viens aussi.", "fr", now - 20_000, false, packetId = 501, replyTo = 500)
            core.setReplyTarget(group, answer.id)
            core.setDraft(group, "Parfait, à tout à l'heure.")
            group
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "reply_bar") != null, "the reply bar above the draft")
        expect(tagsOf(screen, "quote:").isNotEmpty(), "a reply bubble's quote")
        expect(tagsOf(screen, "reactions:").isNotEmpty(), "reactions under a message")
        return screen
    }

    /** A message planned for tomorrow morning above a new draft, which offers "Later" again. */
    fun draftPlanned(): Activity {
        TestCore.install()
        val group = seedChats()
        TestCore.onMain {
            val core = TestCore.core
            core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet()
            core.setDraft(group, "Le marché ouvre à huit heures.")
            val tomorrow = java.time.LocalDate.now().plusDays(1).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            check(core.plan(group, tomorrow))
            core.setDraft(group, "Et pensez au pain.")
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(tagsOf(screen, "planned:").size == 1, "one planned message above the draft")
        expect(Views.tagged(screen, "draft_later") != null, "the Later button under the draft")
        return screen
    }

    /** A draft with "Send later" open; [step] goes further: a day chosen, Android's clock, or Android's calendar. */
    fun planWindow(step: String = ""): Activity {
        TestCore.install()
        val group = seedChats()
        TestCore.onMain {
            TestCore.core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet()
            TestCore.core.setDraft(group, "Et pensez au pain.")
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        Views.tap(screen, "draft_later")
        val window = checkNotNull((screen as ConversationActivity).planForTest)
        fun tap(tag: String) {
            TestCore.onMain { Views.descendants(window.dialog.window!!.decorView).first { it.tag == tag }.performClick() }
            inst.waitForIdleSync()
        }
        when (step) {
            "day" -> {
                tap("plan_day")
                val day = java.time.LocalDate.now().plusDays(3)
                TestCore.onMain {
                    val calendar = window.picker as android.app.DatePickerDialog
                    calendar.updateDate(day.year, day.monthValue - 1, day.dayOfMonth)
                    calendar.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                }
                inst.waitForIdleSync()
                expect(TestCore.onMain { Views.descendants(window.dialog.window!!.decorView).first { it.tag == "plan_day" }.isSelected }, "the chosen day")
            }
            "clock" -> { tap("plan_time"); expect(window.picker is android.app.TimePickerDialog, "Android's clock") }
            "calendar" -> { tap("plan_day"); expect(window.picker is android.app.DatePickerDialog, "Android's calendar") }
            else -> expect(TestCore.onMain { window.dialog.isShowing }, "the time window")
        }
        return screen
    }

    /** "Forward to…": each other chat with its picture. */
    fun forwardTo(): Activity {
        TestCore.install()
        val group = seedChats()
        TestCore.onMain { TestCore.core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet() }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        val heard = TestCore.onMain { TestCore.core.history.messages(group).first { it.direction == Direction.IN } }
        TestCore.onMain { Views.descendants(screen.window.decorView).first { it.tag == "bubble:${heard.id}" }.performLongClick() }
        inst.waitForIdleSync()
        val menu = checkNotNull((screen as ConversationActivity).dialogForTest)
        TestCore.onMain { Views.descendants(menu.window!!.decorView).first { it.tag == "action_forward" }.performClick() }
        inst.waitForIdleSync()
        val list = checkNotNull(screen.dialogForTest)
        expect(TestCore.onMain { Views.descendants(list.window!!.decorView).any { (it.tag as? String)?.startsWith("forward_to:") == true } }, "the chats to forward to")
        return screen
    }

    /** An everyday scene as a camera would give it: sky, sun, ground and a house, at photo size. */
    private fun scenePhoto(sky: Int = Color.rgb(120, 180, 230), ground: Int = Color.rgb(170, 140, 80)): Bitmap =
        Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(sky)
            paint.color = ground; c.drawRect(0f, 760f, 1600f, 1200f, paint)
            paint.color = Color.rgb(250, 210, 60); c.drawCircle(1250f, 260f, 140f, paint)
            paint.color = Color.rgb(200, 90, 50); c.drawRect(420f, 520f, 860f, 860f, paint)
            paint.color = Color.rgb(90, 60, 40); c.drawRect(600f, 680f, 690f, 860f, paint)
            paint.color = Color.rgb(40, 120, 60); c.drawCircle(1100f, 700f, 150f, paint)
        }

    /** The picture screen: the camera and photo buttons, or a chosen photo made ready with its size and radio time. */
    fun pictureScreen(withPhoto: Boolean): Activity {
        TestCore.install()
        seedChats()
        val chat = TestCore.onMain { TestCore.core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val photo = if (withPhoto) {
            val file = java.io.File(context.cacheDir, "screen-photo.jpg")
            file.outputStream().use { scenePhoto().compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Uri.fromFile(file)
        } else null
        val screen = Views.launch<PictureActivity>(PictureActivity.intent(context, chat, photo))
        expect(Views.tagged(screen, "picture_take") != null && Views.tagged(screen, "picture_choose") != null, "camera and photo buttons")
        if (withPhoto) {
            Views.waitUntil(15_000) { Views.descendants(screen.window.decorView).any { it.tag == "picture_send" && it.isShown && it.isEnabled } }
            expect(Views.tagged(screen, "size:MEDIUM") != null, "the sizes")
        }
        return screen
    }

    /** A private chat with a whole picture, one still arriving, and the user's picture, message and reaction waiting for a radio. */
    fun pictureBubbles(): Activity {
        TestCore.install()
        seedChats()
        val chat = TestCore.onMain { TestCore.core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val whole = PictureMaker.prepare(scenePhoto(), PictureSize.MEDIUM, grey = false)
        val coming = PictureMaker.prepare(scenePhoto(Color.rgb(240, 160, 90), Color.rgb(90, 140, 60)), PictureSize.MEDIUM, grey = false)
        val mine = PictureMaker.prepare(scenePhoto(Color.rgb(150, 150, 200)), PictureSize.MEDIUM, grey = false)
        TestCore.onMain {
            val radio = TestCore.radio
            val core = TestCore.core
            core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet()
            org.phonemesh.core.PictureWire.split(41, org.phonemesh.core.PictureCodec.WEBP, whole.width, whole.height, whole.bytes, whole.sketch)
                .forEach { radio.receiveData("!0000aaaa", org.phonemesh.ble.MeshRadio.PICTURE_PORT, org.phonemesh.core.PictureWire.encode(it)) }
            org.phonemesh.core.PictureWire.split(42, org.phonemesh.core.PictureCodec.WEBP, coming.width, coming.height, coming.bytes, coming.sketch)
                .take(2).forEach { radio.receiveData("!0000aaaa", org.phonemesh.ble.MeshRadio.PICTURE_PORT, org.phonemesh.core.PictureWire.encode(it)) }
            radio.connected(false)
            core.sendPicture(chat, mine)
            core.setDraft(chat, "Je t'envoie la mienne.")
            core.send(chat)
            radio.receive("!0000aaaa", "[pm1:fr] Bravo, elle est belle !", true, org.phonemesh.ble.TextMark(packetId = 900))
            val news = core.history.messages(chat).first { it.packetId == 900L }
            core.react(news.id, "👍")
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chat))
        expect(tagsOf(screen, "picture:").size >= 2, "picture bubbles")
        expect(tagsOf(screen, "reaction_waiting:").isNotEmpty(), "a reaction waiting for a radio")
        return screen
    }

    /** The one-time "hold a message" tip, after the arrival tip was closed. */
    fun holdTip(): Activity {
        TestCore.install()
        val group = seedChats()
        TestCore.onMain {
            TestCore.core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet() - "hold"
            TestCore.core.setDraft(group, null)
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "tip:hold") != null, "the hold tip")
        return screen
    }

    /** The first failed message: its tip, with the large picture, between the messages and the microphone. */
    fun conversationTip(): Activity {
        TestCore.install()
        val group = seedChats()
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        expect(Views.tagged(screen, "tip:failed") != null, "the failed-message tip")
        val picture = Views.tagged(screen, "howto:failed")
        expect(picture != null && TestCore.onMain { picture.width >= screen.window.decorView.width * 4 / 5 }, "the tip's large picture")
        expect(tagsOf(screen, "message:").size >= 2, "messages stay in view above the tip")
        expect(tagsOf(screen, "resend:").isNotEmpty(), "the resend button the tip points at")
        expect(Views.tagged(screen, "mic") != null, "the microphone")
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

    /** The recording has stopped and its words are not a draft yet. */
    fun writing(): Activity {
        TestCore.install()
        TestCore.speech.holdTranscript = true
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        Views.tap(screen, "mic")
        Views.tap(screen, "mic")
        expect(TestCore.onMain { TestCore.core.recordState == AppCore.RecordState.TRANSCRIBING }, "the words are being written")
        expect(Views.tagged(screen, "writing") != null, "the writing sign")
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
        expect(Views.tagged(screen, "region_back") != null, "Back")
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
        expect(Views.tagged(screen, "region_helpers") != null, "For helpers")
        expect(Views.tagged(screen, "region_back") != null, "Back")
        return screen
    }

    /** The write never went out. */
    fun regionNotChanged(): Activity {
        val screen = regionSetUp()
        TestCore.onMain { TestCore.radio.regionResult = org.phonemesh.ble.RegionResult.DROPPED }
        Views.tap(screen, "region_yes")
        expectWords(screen, R.string.region_not_changed)
        expect(Views.tagged(screen, "region_retry") != null, "Try again")
        expect(Views.tagged(screen, "region_back") != null, "Back")
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
        expect(Views.tagged(screen, "draft_later")?.let { TestCore.onMain { it.isShown } } != true, "no Later while the region card asks")
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
        core.setDraft(group, "Attendez-moi.")
        core.send(group)
        // The radio took it and then could not send it: a failure to resend. (With no radio at all it would wait.)
        radio.deliver(radio.sent.last().packetId, DeliveryState.FAILED)
        group
    }

    private val states: List<Pair<String, () -> Activity>> = listOf(
        "01-welcome-1" to { welcome(.2f) },
        "01-welcome-3" to { welcome(StoryTimeline.STILL) },
        "01-welcome-5" to { welcome(.85f) },
        "01-welcome-still" to { language() },
        "01-language-from-settings" to { languageFromSettings() },
        "02-chats-step1" to { chatsStep(1) },
        "02-chats-step1-offline" to { chatsStepOneOffline() },
        "02-chats-friend-guide" to { chatsFriendGuide() },
        "02-chats-step-name" to { chatsStepName() },
        "02-chats-step2" to { chatsStep(2) },
        "02-chats-step3" to { chatsStep(3) },
        "02-chats-radio-lost" to { chatsRadioLost() },
        "03-chats" to { chatsRows() },
        "04-chats-english" to { chatsRows("en-US") },
        "05-conversation" to { conversation() },
        "05-conversation-mic-hint" to { conversationMicHint() },
        "05-conversation-tip" to { conversationTip() },
        "05-conversation-reply" to { conversationReply() },
        "05-forward-to" to { forwardTo() },
        "05-hold-tip" to { holdTip() },
        "05-picture-choose" to { pictureScreen(withPhoto = false) },
        "05-picture-ready" to { pictureScreen(withPhoto = true) },
        "05-picture-bubbles" to { pictureBubbles() },
        "06-draft-too-long" to { draftTooLong() },
        "06-draft-cut" to { draftCut() },
        "06-draft-planned" to { draftPlanned() },
        "06-plan-window" to { planWindow() },
        "06-plan-window-day" to { planWindow("day") },
        "06-plan-clock" to { planWindow("clock") },
        "06-plan-calendar" to { planWindow("calendar") },
        "07-recording" to { recordingLocked() },
        "07-recording-tip" to { recordingTip() },
        "07-writing" to { writing() },
        "08-radio" to { radio() },
        "09-settings" to { settings() },
        "09-name" to { nameScreen(filled = false) },
        "09-name-filled" to { nameScreen(filled = true) },
        "10-help" to { help() },
        "10-help-more" to { help(group = 2) },
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
