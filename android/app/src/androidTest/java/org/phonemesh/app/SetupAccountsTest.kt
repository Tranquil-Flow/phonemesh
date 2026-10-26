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
import org.phonemesh.core.SetupSteps
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SoftKeys

/** Guide migration and real add-screen navigation; fake radio and speech only. */
@RunWith(AndroidJUnit4::class)
class SetupAccountsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private var chats: Activity? = null

    @After fun tearDown() {
        Views.finish(Views.resumed().takeIf { it !== chats })
        Views.finish(chats)
        TestCore.uninstall()
    }

    private fun start() = Views.start(ChatsActivity::class.java).also { chats = it }

    @Test fun helpIncludesTheFourApprovedAccountPictures() {
        TestCore.install()
        val screen = Views.start(HelpActivity::class.java).also { chats = it }
        listOf("add_qr", "add_link", "add_accept", "same_picture").forEach {
            assertNotNull("Help must include $it", Views.tagged(screen, "help:$it"))
        }
        assertTrue(TestCore.speech.spoken.isEmpty())
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
    }

    @Test fun englishNameStepAsksForANameNotAGreeting() = nameInstruction("en-US", false)
    @Test fun frenchNameStepAsksForANameNotAGreeting() = nameInstruction("fr", false)
    @Test fun englishMissingNameCardAsksForANameNotAGreeting() = nameInstruction("en-US", true)
    @Test fun frenchMissingNameCardAsksForANameNotAGreeting() = nameInstruction("fr", true)

    private fun nameInstruction(language: String, finished: Boolean) {
        TestCore.install(language = language, voices = listOf(language), named = false)
        TestCore.onMain { core.settings.setupFinished = finished }
        val screen = start()
        assertNotNull(Views.tagged(screen, "name_open"))
        val expected = screen.getString(R.string.name_say)
        assertEquals("the name card must not instruct the user to say hello", expected,
            Views.textOf(Views.tagged(screen, "howto_sentence")!!))
        val picture = Views.tagged(screen, "howto:hello")!!
        assertEquals(expected, TestCore.onMain { picture.contentDescription.toString() })
        assertTrue("opening the guide is silent", TestCore.speech.spoken.isEmpty())
        Views.tap(screen, "howto_say")
        Views.waitUntil { TestCore.speech.spoken == listOf(expected) }
    }

    @Test fun addingSomeoneComesBeforeHello() {
        assertEquals(listOf("VOICE", "NAME", "RADIO", "ADD", "HELLO"), SetupSteps.ORDER.map { it.name })
    }

    @Test fun newReadyPhoneOffersAddSomeoneAndOpeningItDoesNotFinishTheStep() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), added = false)
        val screen = start()
        assertNotNull("new users must add a person before hello", Views.tagged(screen, "card_add"))
        assertEquals("STEP 4 OF 5", Views.textOf(Views.tagged(screen, "step_of")!!))
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
        assertTrue(TestCore.onMain { screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
        Views.tap(screen, "setup_add")
        Views.waitUntil { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AddSomeoneActivity } }
        val add = Views.resumed()!!
        assertNotNull(Views.tagged(add, "add_scan"))
        Views.finish(add)
        Views.waitUntil { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it === screen } }
        assertNotNull(Views.tagged(screen, "card_add"))
        assertFalse(core.settings.setupFinished)
        assertFalse(core.settings.firstPersonAdded)
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun finishedPhoneGetsASlimAddCardWithoutReopeningTheGuide() {
        TestCore.install(added = false)
        TestCore.onMain { core.settings.setupFinished = true; core.settings.firstMessageSent = true }
        val screen = start()
        assertNotNull("old installations still discover accounts", Views.tagged(screen, "card_add"))
        assertNull(Views.tagged(screen, "step_of"))
        assertNotNull(Views.tagged(screen, "chats"))
        assertEquals("Ajouter quelqu’un", Views.textOf(Views.tagged(screen, "setup_add")!!))
        assertTrue(core.settings.setupFinished)
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
    }

    @Test fun anExistingPersonCompletesTheAddStepAfterRestart() {
        TestCore.install(added = false)
        val keys = SoftKeys.generate()
        val qr = QrCode(ContactCard.create(keys, org.phonemesh.core.account.Profile("Bea", 0x8E24AA)), KeyPairBytes.generate().public)
        TestCore.onMain {
            core.accountsFlow.showCode()
            assertTrue(core.accountsFlow.scanned(AddCode.of(qr)) is Accounts.Added.Contact)
        }
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        val screen = start()
        assertNotNull(Views.tagged(screen, "card_hello"))
        assertNotNull(Views.tagged(screen, "step_done:4"))
        assertEquals("ÉTAPE 5 SUR 5", Views.textOf(Views.tagged(screen, "step_of")!!))
        assertFalse(core.settings.setupFinished)
        assertTrue(core.settings.firstPersonAdded)
        Views.finish(screen)
        chats = null
        TestCore.onMain { core.history.writableDatabase.delete("contacts", null, null) }
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        val reopened = start()
        assertNotNull("a completed step stays done after contact removal/restart", Views.tagged(reopened, "card_hello"))
        assertTrue(core.settings.firstPersonAdded)
    }
}
