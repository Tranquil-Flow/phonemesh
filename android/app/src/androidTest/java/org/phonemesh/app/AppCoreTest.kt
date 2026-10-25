package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit

/** AppCore with a fake radio and fake speech: nothing records, plays or transmits. */
@RunWith(AndroidJUnit4::class)
class AppCoreTest {
    private val core get() = TestCore.core
    private val radio get() = TestCore.radio
    private val speech get() = TestCore.speech
    private fun <T> main(block: () -> T): T = TestCore.onMain(block)
    private fun sendIn(chatId: Long, text: String): Message = main {
        core.setDraft(chatId, text)
        assertTrue(core.send(chatId))
        checkNotNull(core.history.lastMessage(chatId))
    }

    @After fun tearDown() = TestCore.uninstall()

    @Test fun deletingStopsItsPlaybackAndALateReceiptChangesNothing() {
        TestCore.install()
        val (bea, direct) = PersonFixture.meet("Kofi")
        val sent = sendIn(direct, "A Kofi")
        main { core.play(sent.id) }
        assertEquals(sent.id, core.playingId)
        assertTrue(main { core.deleteMessage(sent.id) })
        assertNull(core.playingId)
        assertNull(core.history.message(sent.id))
        PersonFixture.acknowledge(bea, radio.broadcasts.single().payload)
        assertNull(core.history.message(sent.id))
        assertNull(core.history.lastMessage(direct))
        assertFalse("already gone", main { core.deleteMessage(sent.id) })
        assertEquals("nothing more goes out over the radio", 1, radio.broadcasts.size)
    }

    @Test fun aNewMessageIsReadAloudOnlyWhenAskedWhileOpenAndQuiet() {
        TestCore.install()
        val group = main { core.groupChatId() }
        val screen = AppCore.Observer { }
        main { core.observe(screen) }
        main { radio.receive("!0000aaaa", "[pm1:fr] Premier", false) }
        assertTrue("off by default", speech.spoken.isEmpty())
        main { core.settings.readAloud = true; radio.receive("!0000aaaa", "[pm1:fr] Deuxième", false) }
        assertEquals(listOf("Deuxième"), speech.spoken)
        assertEquals(core.history.lastMessage(group)!!.id, core.playingId)
        main { radio.receive("!0000aaaa", "[pm1:fr] Troisième", false) }
        assertEquals("a voice already playing is never cut off", listOf("Deuxième"), speech.spoken)
        main { speech.finishPlayback(); core.unobserve(screen); radio.receive("!0000aaaa", "[pm1:fr] Quatrième", false) }
        assertEquals("no screen open, nothing is read", listOf("Deuxième"), speech.spoken)
    }

    @Test fun deletingAChatTakesItsDraftAndPlaybackAndTheGroupStaysEmpty() {
        TestCore.install()
        val group = main { core.groupChatId() }
        val kofi = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        main { radio.receive("!0000aaaa", "[pm1:fr] Salut", true); radio.receive("!0000bbbb", "[pm1:fr] Tous", false) }
        val heard = core.history.lastMessage(kofi)!!
        main { core.play(heard.id); core.setDraft(kofi, "Réponse") }
        assertTrue(main { core.deleteChat(kofi) })
        assertNull(core.playingId)
        assertNull(core.draft(kofi))
        assertNull(core.history.chat(kofi))
        assertTrue(main { core.deleteChat(group) })
        assertNotNull("the group stays", core.history.chat(group))
        assertNull(core.history.lastMessage(group))
        assertFalse("nothing left", main { core.deleteChat(group) })
        main { radio.receive("!0000aaaa", "[pm1:fr] Encore moi", true) }
        assertEquals("a new message starts the chat again", "Encore moi", core.history.lastMessage(main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) })!!.text)
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun aReplyCarriesTheAnsweredRadioIdAndEndsAfterSending() {
        TestCore.install()
        val group = main { core.groupChatId() }
        main { radio.receive("!0000aaaa", "[pm1:fr] On se voit ?", false, org.phonemesh.ble.TextMark(packetId = 500)) }
        val asked = core.history.lastMessage(group)!!
        assertEquals("the radio id is kept", 500L, asked.packetId)
        main { core.setReplyTarget(group, asked.id) }
        assertEquals(asked.id, core.replyTarget(group)!!.id)
        val answer = sendIn(group, "Oui, à midi")
        assertEquals(500L, answer.replyTo)
        assertEquals(500L, radio.sent.single().replyTo)
        assertFalse(radio.sent.single().reaction)
        assertNull("the reply ends with the send", core.replyTarget(group))
        assertEquals(asked.id, core.quoted(answer)!!.id)
        sendIn(group, "Et toi ?")
        assertEquals("the next message answers nothing", 0L, radio.sent.last().replyTo)
    }

    @Test fun aMessageThatNeverWentOutCannotBeAnswered() {
        TestCore.install()
        val group = main { core.groupChatId() }
        val mine = main { core.history.addOutgoing(group, "Brouillon", "fr", 1) }
        main { core.setReplyTarget(group, mine.id) }
        assertNull(core.replyTarget(group))
        assertFalse(main { core.react(mine.id, "👍") })
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun reactionsGoOutAsMeshtasticReactionsAndArriveUnderTheirMessage() {
        TestCore.install()
        val group = main { core.groupChatId() }
        main { radio.receive("!0000aaaa", "[pm1:fr] Le marché est ouvert", false, org.phonemesh.ble.TextMark(packetId = 700)) }
        val news = core.history.lastMessage(group)!!
        val unreadBefore = core.history.chat(group)!!.unread
        assertTrue(main { core.react(news.id, "👍") })
        val sent = radio.sent.single()
        assertEquals("👍", sent.text)
        assertEquals("^all", sent.destinationId)
        assertTrue(sent.reaction)
        assertEquals(700L, sent.replyTo)
        main { core.settings.readAloud = true; core.observe { } }
        main { radio.receive("!0000bbbb", "❤️", false, org.phonemesh.ble.TextMark(packetId = 701, replyTo = 700, reaction = true)) }
        assertEquals(listOf(Reaction(700, null, "👍"), Reaction(700, "!0000bbbb", "❤️")), core.history.reactions(group))
        assertEquals("a reaction is not a message", news.id, core.history.lastMessage(group)!!.id)
        assertEquals("nor unread", unreadBefore, core.history.chat(group)!!.unread)
        assertTrue("nor read aloud", speech.spoken.isEmpty())
        main { radio.receive("!0000bbbb", "x".repeat(40), false, org.phonemesh.ble.TextMark(packetId = 702, replyTo = 700, reaction = true)) }
        assertEquals("too long to be a reaction: dropped", 2, core.history.reactions(group).size)
        main { radio.connected(false) }
        assertTrue(main { core.react(news.id, "🙏") })
        assertEquals("no radio: it waits on this phone, marked", Reaction(700, null, "🙏", waiting = true),
            core.history.reactions(group).single { it.senderId == null })
        assertEquals("and says so", core.localized().getString(R.string.reaction_waiting), core.text(checkNotNull(core.lastNote)))
        assertEquals("nothing went out", 1, radio.sent.size)
        main { radio.connected(true) }
        assertEquals("it goes once a radio is ready", 2, radio.sent.size)
        assertEquals("🙏", radio.sent.last().text)
        assertTrue(radio.sent.last().reaction)
        assertEquals(700L, radio.sent.last().replyTo)
        assertFalse(core.history.reactions(group).single { it.senderId == null }.waiting)
        main { radio.connected(true) }
        assertEquals("sent once only", 2, radio.sent.size)
    }

    @Test fun planningKeepsTheDraftAsAPlannedMessageAndArmsTheAlarm() {
        TestCore.install()
        val group = main { core.groupChatId() }
        val due = System.currentTimeMillis() + 3_600_000
        main { core.setDraft(group, "Rendez-vous ce soir") }
        assertTrue(main { core.plan(group, due) })
        assertNull("the draft becomes the planned message", core.draft(group))
        val planned = core.history.planned(group).single()
        assertEquals("Rendez-vous ce soir", planned.text)
        assertEquals(due, TestCore.alarm.at)
        assertTrue("nothing goes out before its time", radio.sent.isEmpty())
        assertFalse(core.hasDuePlanned())
        main { core.sendDue() }
        assertTrue(radio.sent.isEmpty())
        main { core.cancelPlanned(planned.id) }
        assertTrue(core.history.planned(group).isEmpty())
        assertNull("no alarm once nothing is planned", TestCore.alarm.at)
        assertFalse("an empty draft cannot be planned", main { core.plan(group, due) })
    }

    @Test fun aDueMessageWaitsForTheRadioThenGoesOutAsAReplyMarkedPlanned() {
        TestCore.install()
        val group = main { core.groupChatId() }
        main { radio.receive("!0000aaaa", "[pm1:fr] Tu viens ?", false, org.phonemesh.ble.TextMark(packetId = 500)) }
        val asked = core.history.lastMessage(group)!!
        val due = System.currentTimeMillis() - 1_000
        main {
            core.setReplyTarget(group, asked.id)
            core.setDraft(group, "J'arrive")
            assertTrue(core.plan(group, due))
            radio.connected(false)
            core.sendDue()
        }
        assertTrue("the radio is away: it waits", radio.sent.isEmpty())
        assertTrue(core.hasDuePlanned())
        assertNull("the reply was planned with it", core.replyTarget(group))
        main { radio.connected(true) }
        val sent = radio.sent.single()
        assertEquals(500L, sent.replyTo)
        val message = core.history.lastMessage(group)!!
        assertEquals("J'arrive", message.text)
        assertEquals(due, message.plannedAt)
        assertEquals(500L, message.replyTo)
        assertEquals(sent.packetId, message.packetId)
        assertFalse(core.hasDuePlanned())
        assertNull(TestCore.alarm.at)
    }

    @Test fun theAlarmsServiceSendsWhatIsDueAndStops() {
        TestCore.install()
        val group = main { core.groupChatId() }
        main { core.setDraft(group, "Bonjour de loin"); assertTrue(core.plan(group, System.currentTimeMillis() - 1)) }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        main { PlannedReceiver().onReceive(context, null) }
        Views.waitUntil(10_000) { radio.sent.isNotEmpty() && !core.hasDuePlanned() }
        assertEquals("Bonjour de loin", core.history.lastMessage(group)!!.text)
        assertEquals("[pm1:fr] Bonjour de loin", radio.sent.single().text)
    }

    @Test fun theGuideAgainBringsBackTipsAndTheHintButKeepsSetup() {
        TestCore.install()
        main {
            core.settings.tipsShown = setOf("cancel", "listen")
            core.settings.tipShowings = mapOf("lock" to 2)
            core.settings.firstMessageSent = true
            core.settings.setupFinished = true
        }
        val radioAddress = core.settings.radioAddress
        main { core.showGuideAgain() }
        assertTrue(core.settings.tipsShown.isEmpty())
        assertTrue(core.settings.tipShowings.isEmpty())
        assertFalse("the hold-to-talk hint returns", core.settings.firstMessageSent)
        assertTrue("setup is not run again", core.settings.setupFinished)
        assertEquals(radioAddress, core.settings.radioAddress)
    }

    @Test fun aLongTranscriptIsCutAtAWordAndMarked() {
        TestCore.install()
        val group = main { core.groupChatId() }
        speech.nextTranscript = "mot ".repeat(80).trim()
        var cutSeenByScreen = false
        main {
            core.observe { change ->
                if (change == AppCore.Change.RECORDING && core.recordState == AppCore.RecordState.IDLE) {
                    cutSeenByScreen = core.draftWasCut(group)
                }
            }
        }
        main { core.startRecording(group); core.finishRecording() }
        val draft = checkNotNull(core.draft(group))
        assertEquals(DraftFit.FITS, core.draftFit(group))
        assertTrue(draft.endsWith("mot"))
        assertTrue(core.draftWasCut(group))
        assertTrue("the mark is published before synchronous observers", cutSeenByScreen)
        assertTrue(radio.sent.isEmpty())
        assertTrue(speech.spoken.isEmpty())
        main { core.setDraft(group, "Bonjour") }
        assertFalse("an edit clears the mark", core.draftWasCut(group))
        main { core.startRecording(group); core.finishRecording(); core.setDraft(group, null) }
        assertFalse("clearing clears the mark", core.draftWasCut(group))
        main { core.startRecording(group); core.finishRecording(); assertTrue(core.send(group)) }
        assertFalse("sending clears the mark", core.draftWasCut(group))
        speech.nextTranscript = "Bonjour"
        main { core.startRecording(group); core.finishRecording() }
        assertFalse("a fitting transcript is not marked", core.draftWasCut(group))
    }

    @Test fun theRecordingLimitDefaultsToFifteenSeconds() {
        TestCore.install()
        assertEquals(15_000L, main { core.recordLimitMs })
    }

    @Test fun groupMessagesGoToTheGroupAndPrivateOnesToTheirChat() {
        TestCore.install()
        main {
            radio.receive("!0000aaaa", "[pm1:fr] Bonjour le groupe", direct = false)
            radio.receive("!0000bbbb", "[pm1:fr] Salut toi", direct = true)
        }
        val group = core.groupChatId()
        assertEquals("Bonjour le groupe", core.history.lastMessage(group)!!.text)
        val direct = core.history.chats().single { it.kind == ChatKind.DIRECT }
        assertEquals("!0000bbbb", direct.address)
        assertEquals("Salut toi", core.history.lastMessage(direct.id)!!.text)
        // Old radio-addressed chats still receive, but sending now requires a person's session.
        main { core.setDraft(direct.id, "Pour toi"); assertFalse(core.send(direct.id)) }
        assertEquals("Pour toi", core.draft(direct.id))
        val (bea, person) = PersonFixture.meet("Bea")
        sendIn(group, "Pour tous")
        sendIn(person, "Pour toi")
        assertEquals(listOf(303, 301), radio.broadcasts.map { it.port })
        assertEquals(listOf("^all", "^all"), radio.broadcasts.map { it.destinationId })
        assertEquals(listOf(0, 0), radio.broadcasts.map { it.channel })
        assertEquals("Pour tous", org.phonemesh.core.wire.SharedPost.decode(radio.broadcasts[0].payload)!!.text)
        val opened = bea.postman.receive(radio.broadcasts[1].payload, 1_000)!!.body as org.phonemesh.core.wire.Body.Text
        assertEquals("Pour toi", opened.text)
        assertEquals("fr", opened.language)
        assertEquals("Salut toi", core.history.lastMessage(direct.id)!!.text)
        assertEquals("Pour toi", core.history.lastMessage(person)!!.text)
    }

    @Test fun otherChannelsAndBrokenTextAreIgnored() {
        TestCore.install()
        main {
            radio.receive("!0000aaaa", "[pm1:fr] Canal deux", direct = false, channel = 2)
            radio.receive("!0000aaaa", "\u0000", direct = false)
        }
        assertNull(core.history.lastMessage(core.groupChatId()))
    }

    @Test fun groupShowsSentAndOnlyPrivateGetsATick() {
        TestCore.install()
        val group = core.groupChatId()
        val (bea, direct) = PersonFixture.meet("Kofi")
        val inGroup = sendIn(group, "Au groupe")
        val inPrivate = sendIn(direct, "A Kofi")
        val groupPacket = radio.sent.single().packetId
        main { radio.deliver(groupPacket, DeliveryState.SUBMITTING); radio.deliver(groupPacket, DeliveryState.QUEUED) }
        Views.waitUntil { core.history.message(inPrivate.id)!!.status == DeliveryStatus.SENT }
        assertEquals(DeliveryStatus.SENT, core.history.message(inGroup.id)!!.status)
        main { radio.deliver(groupPacket, DeliveryState.ACKNOWLEDGED); radio.deliver(groupPacket, DeliveryState.UNCONFIRMED) }
        assertEquals(DeliveryStatus.SENT, core.history.message(inGroup.id)!!.status)
        assertEquals("a private message is Sent until the other phone's receipt", DeliveryStatus.SENT, core.history.message(inPrivate.id)!!.status)
        PersonFixture.acknowledge(bea, radio.broadcasts.single { it.port == 301 }.payload)
        assertEquals(DeliveryStatus.DELIVERED, core.history.message(inPrivate.id)!!.status)
    }

    /** Review Focus 1, changed by the owner on 2026-10-13: no radio means waiting, said on the bubble, then sent by itself. */
    @Test fun withoutARadioMessagesWaitSayingSoAndGoInOrderOnceOneIsReady() {
        TestCore.install()
        val (bea, direct) = PersonFixture.meet("Kofi", accept = false)
        main { radio.connected(false) }
        val failed = sendIn(direct, "Tu es là ?")
        val next = sendIn(direct, "Réponds-moi")
        assertEquals(DeliveryStatus.WAITING, failed.status)
        assertEquals(DeliveryStatus.WAITING, next.status)
        assertTrue(radio.broadcasts.isEmpty() && radio.sent.isEmpty())
        val ref = core.history.message(failed.id)!!.packetId
        assertNotNull("its reference is known from the start", ref)
        TestCore.restart()
        assertEquals("closing the app keeps it waiting, with its sealed packet", DeliveryStatus.WAITING, core.history.message(failed.id)!!.status)
        assertEquals(ref, core.history.message(failed.id)!!.packetId)
        main { radio.connected(true) }
        Views.waitUntil { radio.broadcasts.size == 1 }
        assertEquals("one at a time: the radio's queue drops a fourth write", 1, radio.broadcasts.size)
        val first = radio.broadcasts.first()
        main { radio.deliver(first.packetId, DeliveryState.SUBMITTING) }
        assertEquals("being written is not taken", 1, radio.broadcasts.size)
        main { radio.deliver(first.packetId, DeliveryState.QUEUED) }
        Views.waitUntil { radio.broadcasts.size == 2 }
        assertEquals("the next goes once the radio took the first, in the order written", DeliveryStatus.SENT, core.history.message(failed.id)!!.status)
        assertEquals(DeliveryStatus.WAITING, core.history.message(next.id)!!.status)
        // The old write's late report changes nothing; the other phone's receipt delivers both.
        main { radio.deliver(first.packetId, DeliveryState.FAILED) }
        assertEquals(DeliveryStatus.SENT, core.history.message(failed.id)!!.status)
        main { radio.deliver(radio.broadcasts[1].packetId, DeliveryState.QUEUED) }
        radio.broadcasts.forEach { bea.postman.receive(it.payload, 1_000) }
        PersonFixture.deliver(bea.postman.due(60_000).first().packet)
        assertEquals(DeliveryStatus.DELIVERED, core.history.message(failed.id)!!.status)
        assertEquals(2, core.history.messages(direct).size)
    }

    /** Review Focus 2. */
    @Test fun aStrangerGetsAPrivateChatThatTakesTheirNameLater() {
        TestCore.install()
        main { radio.receive("!0bad4a2f", "[pm1:fr] Qui suis-je ?", direct = true) }
        val chat = core.history.chats().single { it.kind == ChatKind.DIRECT }
        assertEquals("Radio 4a2f", main { core.chatTitle(chat) })
        assertEquals(1, chat.unread)
        main { radio.setPeers(listOf(RadioPeer("!0bad4a2f", "Kofi"))) }
        assertEquals("Kofi", main { core.chatTitle(core.history.chat(chat.id)!!) })
        main { radio.receive("!0bad4a2f", "[pm1:fr] C'est Kofi", direct = true) }
        assertEquals(1, core.history.chats().count { it.kind == ChatKind.DIRECT })
        assertEquals(2, core.history.messages(chat.id).size)
    }

    @Test fun theGroupIsEveryoneNearbyUntilTheRadioNamesItsChannel() {
        TestCore.install(language = "en", connected = false)
        fun title() = main { core.chatTitle(core.history.chat(core.groupChatId())!!) }
        assertEquals("Everyone nearby", title())
        main { core.settings.language = "fr" }
        assertEquals("no radio yet", "Tout le monde ici", title())
        main { core.connect("AA:BB:CC:DD:EE:01", "Kofi"); radio.channelName = "Marché"; radio.connected(true) }
        assertEquals("Marché", title())
        assertEquals("Marché", main { core.chatRows().single().title })
        main { radio.connected(false) }
        assertEquals("kept while the radio is away", "Marché", title())
        TestCore.restart()
        assertEquals("kept when the app starts without it", "Marché", title())
        // Another radio has its own channel; one that names none shows the plain title, also after it had a name.
        main { core.connect("AA:BB:CC:DD:EE:02", "Ama"); radio.channelName = null; radio.connected(true) }
        assertEquals("Tout le monde ici", title())
        main { radio.connected(false); core.connect("AA:BB:CC:DD:EE:01", "Kofi") }
        assertEquals("Marché", title())
        main { radio.channelName = null; radio.connected(true) }
        assertEquals("the owner took the name away", "Tout le monde ici", title())
    }

    /** Review Focus 3, and the history surviving a restart. */
    @Test fun waitingMessagesBecomeUnconfirmedAfterARestart() {
        TestCore.install()
        val group = core.groupChatId()
        val (bea, direct) = PersonFixture.meet("Kofi", accept = false)
        val privateWaiting = sendIn(direct, "Privé")
        val groupSent = sendIn(group, "Parti")
        main { radio.deliver(radio.sent.last().packetId, DeliveryState.QUEUED) }
        val groupWaiting = sendIn(group, "Pas encore parti")
        TestCore.restart()
        assertEquals("a person's message keeps its sealed packet: still waiting", DeliveryStatus.WAITING, core.history.message(privateWaiting.id)!!.status)
        assertEquals(DeliveryStatus.SENT, core.history.message(groupSent.id)!!.status)
        assertEquals(DeliveryStatus.UNCONFIRMED, core.history.message(groupWaiting.id)!!.status)
        assertEquals(listOf("Parti", "Pas encore parti"), core.history.messages(group).map { it.text })
        assertEquals("WAITING", core.history.outbox(bea.account).single().state)
    }

    @Test fun unreadCountsOnlyWhileTheChatIsClosed() {
        TestCore.install()
        val group = core.groupChatId()
        main { radio.receive("!0000aaaa", "[pm1:fr] Un", false); radio.receive("!0000aaaa", "[pm1:fr] Deux", false) }
        assertEquals(2, core.history.chat(group)!!.unread)
        main { core.openChat(group) }
        assertEquals(0, core.history.chat(group)!!.unread)
        main { radio.receive("!0000aaaa", "[pm1:fr] Vu", false) }
        assertEquals(0, core.history.chat(group)!!.unread)
        main { core.closeChat(group); radio.receive("!0000aaaa", "[pm1:fr] Trois", false) }
        assertEquals(1, core.history.chat(group)!!.unread)
    }

    @Test fun onlyOneMessagePlaysAtATime() {
        TestCore.install()
        val group = core.groupChatId()
        main { radio.receive("!0000aaaa", "[pm1:fr] Premier", false); radio.receive("!0000aaaa", "[pm1:fr] Second", false) }
        val (first, second) = core.history.messages(group)
        main { core.play(first.id) }
        assertEquals(first.id, core.playingId)
        main { core.play(second.id) }
        assertEquals(second.id, core.playingId)
        assertEquals(listOf("Premier", "Second"), speech.spoken)
        main { core.play(second.id) } // tapping the playing one stops it
        assertNull(core.playingId)
        assertFalse(speech.playing)
    }

    @Test fun aMissingVoiceFallsBackToMineWithANote() {
        TestCore.install()
        main { radio.receive("!0000aaaa", "[pm1:en-US] Hello there", false) }
        val message = core.history.lastMessage(core.groupChatId())!!
        main { core.play(message.id) }
        assertEquals(listOf("Hello there"), speech.spoken)
        assertEquals(UiText(R.string.voice_fallback, listOf("English")), core.lastNote)
    }

    @Test fun recordingMakesADraftAndNothingIsSentOrPlayed() {
        TestCore.install()
        val group = core.groupChatId()
        assertTrue(main { core.startRecording(group) })
        assertTrue(speech.recording)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        main { core.finishRecording() }
        assertEquals("Bonjour à tous", core.draft(group))
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertTrue(radio.sent.isEmpty())
        assertTrue(speech.spoken.isEmpty())
    }

    @Test fun cancellingDropsTheRecording() {
        TestCore.install()
        val group = core.groupChatId()
        main { core.startRecording(group); core.cancelRecording() }
        assertNull(core.draft(group))
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertFalse(speech.recording)
    }

    @Test fun recordingStopsByItselfAtTheLimit() {
        TestCore.install()
        val group = core.groupChatId()
        main { core.recordLimitMs = 200; core.startRecording(group) }
        Thread.sleep(800)
        assertEquals(AppCore.RecordState.IDLE, main { core.recordState })
        assertEquals("Bonjour à tous", core.draft(group))
    }

    @Test fun aTooLongDraftCannotBeSent() {
        TestCore.install()
        val group = core.groupChatId()
        main { core.setDraft(group, "a".repeat(220)) }
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
        assertFalse(main { core.send(group) })
        assertTrue(radio.sent.isEmpty())
        assertTrue(radio.broadcasts.isEmpty())
        assertEquals("a".repeat(220), core.draft(group))
    }

    @Test fun theRememberedRadioReconnectsByItself() {
        TestCore.install(connected = false)
        main { core.settings.radioAddress = "AA:BB:CC:DD:EE:01"; core.settings.radioName = "Kofi" }
        TestCore.access.radioMissing = listOf("android.permission.BLUETOOTH_CONNECT")
        assertFalse(main { core.autoReconnect() })
        TestCore.access.radioMissing = emptyList()
        main { core.activityStarted() }
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), radio.connects)
        main { core.activityStopped() }
    }

    @Test fun progressReportsDuringAConnectDoNotEndTheWait() {
        TestCore.install(connected = false)
        main {
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            radio.connected(false)
            radio.connected(false)
        }
        assertTrue(main { core.isConnecting("AA:BB:CC:DD:EE:01") })
    }

    @Test fun aConnectionThatFailsEndsTheWaitAndTellsTheScreen() {
        TestCore.install(connected = false)
        val changes = mutableListOf<AppCore.Change>()
        main {
            core.observe { changes += it }
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            assertTrue(core.isConnecting("AA:BB:CC:DD:EE:01"))
            changes.clear()
            radio.failConnect()
        }
        assertTrue(changes.contains(AppCore.Change.RADIO))
        assertFalse(main { core.isConnecting("AA:BB:CC:DD:EE:01") })
    }

    @Test fun aWaitThatRunsOutTellsTheScreen() {
        TestCore.install(connected = false)
        val changes = java.util.Collections.synchronizedList(mutableListOf<AppCore.Change>())
        main {
            core.connectWaitMs = 100
            core.observe { changes += it }
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            changes.clear()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !changes.contains(AppCore.Change.RADIO)) Thread.sleep(20)
        assertTrue(changes.contains(AppCore.Change.RADIO))
        assertFalse(main { core.isConnecting("AA:BB:CC:DD:EE:01") })
    }

    @Test fun pairingKeepsTheAttemptAliveWellPastTheUsualWaitThenOneTapConnects() {
        TestCore.install(connected = false)
        val changes = java.util.Collections.synchronizedList(mutableListOf<AppCore.Change>())
        main {
            core.connectWaitMs = 100
            core.observe { changes += it }
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            changes.clear()
            radio.pairing(true)
        }
        assertTrue("the screens hear that pairing began", changes.contains(AppCore.Change.RADIO))
        Thread.sleep(500)
        assertTrue("still connecting after five usual waits", main { core.isConnecting("AA:BB:CC:DD:EE:01") })
        assertFalse("the region wait must not start a second connection", main { core.reconnectForRegion(); core.autoReconnect() })
        assertEquals("one connect call", listOf("AA:BB:CC:DD:EE:01"), main { radio.connects.toList() })
        main { radio.pairing(false) }
        assertTrue("paired: the same attempt carries on", main { core.isConnecting("AA:BB:CC:DD:EE:01") })
        main { radio.connected(true) }
        assertTrue(main { core.connected })
        assertFalse(main { core.isAttempting("AA:BB:CC:DD:EE:01") })
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), main { radio.connects.toList() })
    }

    @Test fun afterPairingTheUsualWaitRunsAgainAndCanRunOut() {
        TestCore.install(connected = false)
        val changes = java.util.Collections.synchronizedList(mutableListOf<AppCore.Change>())
        main {
            core.connectWaitMs = 150
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            radio.pairing(true)
        }
        Thread.sleep(400)
        main {
            radio.pairing(false)
            core.observe { changes += it }
            assertTrue(core.isConnecting("AA:BB:CC:DD:EE:01"))
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !changes.contains(AppCore.Change.RADIO)) Thread.sleep(20)
        assertTrue("the screens are told when the wait runs out", changes.contains(AppCore.Change.RADIO))
        assertFalse(main { core.isConnecting("AA:BB:CC:DD:EE:01") })
    }

    @Test fun refusedPairingEndsTheAttemptOnce() {
        TestCore.install(connected = false)
        main {
            core.connectWaitMs = 60_000
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            radio.pairing(true)
            radio.pairing(false)
            radio.failConnect()
        }
        assertFalse(main { core.isAttempting("AA:BB:CC:DD:EE:01") })
        // A late report about the attempt that ended brings nothing back.
        main { radio.pairing(true) }
        assertFalse(main { core.isAttempting("AA:BB:CC:DD:EE:01") })
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), main { radio.connects.toList() })
        // The next attempt starts with the usual wait, not a pairing one.
        main { core.connectWaitMs = 100; core.connect("AA:BB:CC:DD:EE:01", "Kofi") }
        Thread.sleep(400)
        assertFalse(main { core.isConnecting("AA:BB:CC:DD:EE:01") })
    }

    @Test fun diagnosticReportsWhileReadyAreNotNewConnections() {
        TestCore.install(connected = true)
        val before = main { core.readyReports }
        main { radio.stopScan(); radio.scan(); radio.connected(true) }
        assertEquals(before, main { core.readyReports })
        main { radio.connected(false); radio.connected(true) }
        assertEquals(before + 1, main { core.readyReports })
    }

    @Test fun aScanThatStopsByItselfClearsTheSearch() {
        TestCore.install(connected = false)
        val changes = mutableListOf<AppCore.Change>()
        main {
            core.observe { changes += it }
            core.startScan()
            assertTrue(core.scanning)
            changes.clear()
            radio.failScan()
        }
        assertFalse(main { core.scanning })
        assertTrue(changes.contains(AppCore.Change.RADIO))
    }

    @Test fun aScanThatNeverEndsIsStoppedAfterAWhile() {
        TestCore.install(connected = false)
        main {
            core.scanWaitMs = 100
            core.startScan()
            assertTrue(core.scanning)
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && main { core.scanning }) Thread.sleep(20)
        assertFalse(main { core.scanning })
        assertTrue(radio.stopScans >= 1)
    }

    @Test fun theFirstSentMessageIsRemembered() {
        val core = TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        assertFalse(core.settings.firstMessageSent)
        TestCore.onMain { core.setDraft(group, "Ceci est un très long message ".repeat(8).trim()) }
        assertFalse(TestCore.onMain { core.send(group) })
        assertFalse(core.settings.firstMessageSent)
        TestCore.onMain { core.setDraft(group, "Bonjour") }
        assertTrue(TestCore.onMain { core.send(group) })
        assertTrue(core.settings.firstMessageSent)
        assertTrue(TestCore.restart().settings.firstMessageSent)
    }

    @Test fun howToSentencesUseTheirClipOrTheVoice() {
        TestCore.install(voices = emptyList())
        assertTrue(main { core.canSayHowTo("download") })
        assertTrue(main { core.canSayHowTo("friend") })
        assertFalse("no voice yet, no clip", main { core.canSayHowTo("power") })
        main { core.sayHowTo("power") }
        assertTrue(speech.spoken.isEmpty())
        main { core.sayHowTo("download"); core.sayHowTo("friend") }
        assertEquals(listOf(R.raw.howto_fr_download, R.raw.howto_fr_friend), speech.clips)
        assertTrue(speech.spoken.isEmpty())

        TestCore.install()
        assertTrue(main { core.canSayHowTo("power") })
        main { core.sayHowTo("power") }
        assertEquals(listOf("Allumez la radio."), speech.spoken)
        assertEquals(AppCore.HOWTO_PLAYBACK, core.playingId)
        main { speech.finishPlayback() }
        assertNull(core.playingId)
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun howToClipsFollowTheChosenLanguageEvenWithAVoice() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        main { core.sayHowTo("download"); core.sayHowTo("friend") }
        assertEquals(listOf(R.raw.howto_en_download, R.raw.howto_en_friend), speech.clips)
        assertTrue(speech.spoken.isEmpty())
        main { core.sayHowTo("power") }
        assertEquals(listOf("Turn on the radio."), speech.spoken)
        assertEquals(AppCore.HOWTO_PLAYBACK, core.playingId)
        main { core.stopPlayback() }
        assertNull(core.playingId)
        assertFalse(speech.playing)
    }

    @Test fun aHowToClipStopsTheDraftVoice() {
        TestCore.install()
        val group = core.groupChatId()
        main { core.setDraft(group, "Bonjour"); core.playDraft(group) }
        assertEquals(AppCore.DRAFT_PLAYBACK, core.playingId)
        main { core.sayHowTo("download") }
        assertNull(core.playingId)
        assertFalse(speech.playing)
        assertEquals(listOf(R.raw.howto_fr_download), speech.clips)
    }

    /** Observers render from this flag; AppCore.changed notifies synchronously, so it must already be set. */
    @Test fun theFirstMessageFlagIsSetBeforeScreensAreNotified() {
        val core = TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        var seenDuringNotification: Boolean? = null
        TestCore.onMain {
            core.observe { change ->
                if (change == AppCore.Change.MESSAGES) seenDuringNotification = core.settings.firstMessageSent
            }
            core.setDraft(group, "Bonjour")
            assertTrue(core.send(group))
        }
        assertEquals(true, seenDuringNotification)
    }
}
