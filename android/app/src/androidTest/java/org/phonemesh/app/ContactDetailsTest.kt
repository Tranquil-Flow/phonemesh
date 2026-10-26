package org.phonemesh.app

import android.app.Activity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SoftKeys

/** Real activities/history; all speech and radio effects are silent fakes. */
@RunWith(AndroidJUnit4::class)
class ContactDetailsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private var conversation: Activity? = null
    private var details: Activity? = null
    private lateinit var account: AccountId
    private var chatId = 0L

    @After fun tearDown() {
        Views.finish(Views.resumed().takeIf { it !== conversation && it !== details })
        Views.finish(details)
        Views.finish(conversation)
        TestCore.uninstall()
    }

    private fun prepare(language: String = "fr") {
        TestCore.install(language = language, voices = listOf("fr", "en-US"), connected = false)
        Fixtures.store(context).install(Fixtures.voice("fr", ALTERNATE))
        val keys = SoftKeys.generate()
        val qr = QrCode(ContactCard.create(keys, org.phonemesh.core.account.Profile("Bea", 0x8E24AA)), KeyPairBytes.generate().public)
        account = qr.card.accountId
        TestCore.onMain {
            core.accountsFlow.showCode()
            chatId = (core.accountsFlow.scanned(AddCode.of(qr)) as Accounts.Added.Contact).chatId
        }
    }

    private fun openDetails(): Activity {
        conversation = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chatId))
        Views.tap(conversation!!, "title")
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it.javaClass.simpleName == "ContactActivity" }?.also { details = it } != null
        }
        return details!!
    }

    private fun chooseDirectly(voice: String?) = TestCore.onMain {
        val row = checkNotNull(core.history.contactRow(account.toString()))
        core.history.putContact(History.ContactRow(row.accountId, row.card, row.name, row.colour, row.met, voice, row.added))
    }

    private fun received(language: String = "fr") = TestCore.onMain {
        core.history.addIncoming(chatId, account.toString(), "Bonjour Bea", language, 1L, false)
    }

    @Test fun headerOpensSecureAccessibleDetailsAndSafetyInEnglish() {
        prepare("en-US")
        val page = openDetails()
        assertEquals("Bea", Views.textOf(Views.tagged(page, "contact_name")!!))
        assertEquals("Met in person.", Views.textOf(Views.tagged(page, "contact_met")!!))
        assertTrue(TestCore.onMain { page.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
        assertEquals(emptyList<String>(), A11yCheck.problems(page))
        Views.tap(page, "contact_safety")
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is SafetyActivity }
        }
        val safety = Views.resumed()!!
        Views.tap(safety, "safety_differ")
        assertFalse("A mismatch must remain visible, not finish the warning screen", TestCore.onMain { safety.isFinishing })
        assertNotNull(Views.withText(safety, safety.getString(R.string.safety_differ_note)))
        Views.finish(safety)
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it === page }
        }
        assertFalse(core.accountsFlow.isMet(account))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("Not met yet", Views.textOf(Views.tagged(page, "contact_met")!!))
    }

    @Test fun voiceChoicePersistsAcrossRestartAndCanReturnToAutomatic() {
        prepare()
        val page = openDetails()
        val sessionBefore = TestCore.onMain { core.accounts.session(account)!!.encode() }
        Views.tap(page, "contact_voice:$ALTERNATE")
        assertEquals(ALTERNATE, core.history.contactRow(account.toString())!!.voice)
        assertTrue(TestCore.onMain { sessionBefore.contentEquals(core.accounts.session(account)!!.encode()) })
        assertTrue(TestCore.speech.spoken.isEmpty())
        Views.finish(page); details = null
        Views.finish(conversation); conversation = null
        TestCore.restart()
        assertEquals(ALTERNATE, core.history.contactRow(account.toString())!!.voice)
        val reopened = openDetails()
        val selected = Views.tagged(reopened, "contact_voice:$ALTERNATE")!!
        assertTrue(TestCore.onMain { selected.isSelected })
        assertEquals(emptyList<String>(), A11yCheck.problems(reopened))
        Views.tap(reopened, "contact_voice:auto")
        assertNull(core.history.contactRow(account.toString())!!.voice)
        val automatic = Views.tagged(reopened, "contact_voice:auto")!!
        assertTrue(TestCore.onMain { automatic.isSelected })
    }

    @Test fun failedVoiceSaveKeepsTheOldChoiceAndShowsFailure() {
        prepare()
        val page = openDetails()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER refuse_voice BEFORE UPDATE OF voice ON contacts BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        }
        Views.tap(page, "contact_voice:$ALTERNATE")
        assertNull(core.history.contactRow(account.toString())!!.voice)
        assertEquals(UiText(R.string.contact_voice_failed), core.lastNote)
        val automatic = Views.tagged(page, "contact_voice:auto")!!
        assertTrue(TestCore.onMain { automatic.isSelected })
        TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER refuse_voice") }
        Views.tap(page, "contact_voice:$ALTERNATE")
        assertEquals(ALTERNATE, core.history.contactRow(account.toString())!!.voice)
    }

    @Test fun incomingPersonPlaybackUsesTheSavedVoiceAfterRestart() {
        prepare()
        chooseDirectly(ALTERNATE)
        val message = received()
        TestCore.restart()
        TestCore.onMain { core.play(message.id) }
        assertEquals(listOf(ALTERNATE), TestCore.speech.spokenVoices)
        assertEquals(listOf("Bonjour Bea"), TestCore.speech.spoken)
    }

    @Test fun explicitlySelectedVoiceCanDifferFromTheMessageLanguage() {
        prepare()
        chooseDirectly(ENGLISH)
        val message = received()
        TestCore.onMain { core.play(message.id) }
        assertEquals(listOf(ENGLISH), TestCore.speech.spokenVoices)
    }

    @Test fun removedVoiceFallsBackWithoutForgettingThePreference() {
        prepare()
        chooseDirectly(ALTERNATE)
        Fixtures.store(context).remove(ALTERNATE)
        val message = received()
        TestCore.onMain { core.play(message.id) }
        assertEquals(listOf(FRENCH), TestCore.speech.spokenVoices)
        assertEquals(ALTERNATE, core.history.contactRow(account.toString())!!.voice)
        assertNotNull(core.lastNote)
    }

    @Test fun ownDraftAndClaimedSharedSenderNeverUseTheContactVoice() {
        prepare()
        chooseDirectly(ALTERNATE)
        val own = TestCore.onMain { core.history.addOutgoing(chatId, "Moi", "fr", 1L) }
        val shared = TestCore.onMain { core.history.addIncoming(core.groupChatId(), account.toString(), "Claimed", "fr", 2L, false) }
        TestCore.onMain {
            core.play(own.id)
            core.play(shared.id)
            core.setDraft(chatId, "Brouillon")
            core.playDraft(chatId)
        }
        assertEquals(listOf(FRENCH, FRENCH, FRENCH), TestCore.speech.spokenVoices)
    }

    @Test fun missingVoiceCannotReplaceTheSavedChoiceOrCreateAContact() {
        prepare()
        chooseDirectly(ALTERNATE)
        assertFalse(TestCore.onMain { core.setContactVoice(account.toString(), "voice-absent") })
        assertEquals(ALTERNATE, core.history.contactRow(account.toString())!!.voice)
        assertFalse(TestCore.onMain { core.setContactVoice("missing-contact", null) })
        assertNull(core.history.contactRow("missing-contact"))
    }

    @Test fun frenchDarkDetailsRemainAccessibleAndExplainMissingVoice() {
        TestCore.nextTheme = ThemeMode.DARK
        prepare()
        chooseDirectly(ALTERNATE)
        Fixtures.store(context).remove(ALTERNATE)
        val page = openDetails()
        assertEquals("Voix absente. Une autre voix sera utilisée.", Views.textOf(Views.tagged(page, "contact_voice_missing")!!))
        assertEquals(emptyList<String>(), A11yCheck.problems(page))
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    companion object {
        private const val ALTERNATE = "voice-fr-alternate"
        private const val FRENCH = "voice-fr-siwis"
        private const val ENGLISH = "voice-en-US-ljspeech"
    }
    @Test fun anExhaustedSessionOffersAnExplicitFreshQrWithoutResettingIt() {
        prepare("en-US")
        val before = TestCore.onMain {
            core.setDraft(chatId, "not confirmed")
            assertTrue(core.send(chatId))
            val row = core.history.outbox(account.toString()).single()
            core.history.setOutboxState(row.id, "FAILED")
            core.history.setStatus(row.messageId!!, org.phonemesh.core.DeliveryStatus.FAILED)
            core.history.sessionRow(account.toString())!!.second
        }
        val page = openDetails()
        val button = Views.tagged(page, "contact_start_again")
        assertNotNull("F6 requires an explicit recovery offer after retry exhaustion", button)
        assertTrue(TestCore.onMain { button!!.isShown })
        assertNotNull(Views.withText(page, "Start again"))
        assertNotNull(Views.withText(page, "Scan each other's new code."))
        assertEquals(emptyList<String>(), A11yCheck.problems(page))
        Views.tap(page, "contact_start_again")
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AddSomeoneActivity }
        }
        val add = Views.resumed()!!
        assertNotNull(Views.tagged(add, "my_code"))
        TestCore.onMain {
            assertTrue("showing a new QR is not consent to replace a session", before.contentEquals(core.history.sessionRow(account.toString())!!.second))
            assertEquals("FAILED", core.history.outbox(account.toString()).single().state)
            assertTrue(TestCore.radio.broadcasts.isEmpty())
        }
    }

    @Test fun aHealthyContactDoesNotOfferASilentSessionReset() {
        prepare("en-US")
        val page = openDetails()
        val button = Views.tagged(page, "contact_start_again")
        assertTrue(button == null || TestCore.onMain { !button.isShown })
    }

}
