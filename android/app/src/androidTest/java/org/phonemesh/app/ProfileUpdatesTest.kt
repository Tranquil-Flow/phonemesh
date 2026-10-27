package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile
import org.phonemesh.core.wire.Body

/**
 * Profile changes reach contacts (profile spec PF5, decisions 42 and 48-49): one update behind the next message to
 * each contact, at most hourly, never for nothing; a received update replaces only an older profile, with no bubble.
 */
@RunWith(AndroidJUnit4::class)
class ProfileUpdatesTest {
    private val core get() = TestCore.core

    @After fun tearDown() = TestCore.uninstall()

    private fun send(chatId: Long, text: String, expected: Int) {
        TestCore.onMain { core.setDraft(chatId, text); assertTrue(core.send(chatId)) }
        Views.waitUntil { TestCore.radio.broadcasts.size >= expected }
        Thread.sleep(300)
        assertEquals("packets after \"$text\"", expected, TestCore.radio.broadcasts.size)
    }

    /** What Bea's phone makes of every packet the app sent from [from] on. */
    private fun opened(bea: PersonFixture.Bea, from: Int): List<Body> =
        TestCore.radio.broadcasts.drop(from).mapNotNull { bea.postman.receive(it.payload, 1_000)?.body }

    @Test fun aChangedProfileFollowsTheNextMessageOnceAndAtMostHourly() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        send(chatId, "met in person: she has my card", 1)
        assertTrue(opened(bea, 0).single() is Body.Text)

        TestCore.onMain { core.saveProfile("Awa", 0x1E88E5, Avatar.Emoji("🐟")) }
        assertEquals("saving sends nothing by itself", 1, TestCore.radio.broadcasts.size)
        send(chatId, "after the change", 3)
        val after = opened(bea, 1)
        assertTrue(after[0] is Body.Text)
        val update = (after[1] as Body.ProfileUpdate).profile
        assertEquals(Profile("Awa", 0x1E88E5, Avatar.Emoji("🐟"), revision = 2), update)

        send(chatId, "nothing changed since", 4)
        TestCore.onMain { core.saveProfile("Awa B", 0x1E88E5, Avatar.Emoji("🐟")) }
        send(chatId, "changed again within the hour", 5)
        assertTrue("one update an hour at most", opened(bea, 3).all { it is Body.Text })
    }

    @Test fun aContactsUpdateReplacesTheirOlderProfileOnlyAndShowsNoBubble() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val newer = Profile("Bea Mensah", 0xEC407A, Avatar.Emoji("🐱"), revision = 3)
        PersonFixture.deliver(PersonFixture.send(bea, Body.ProfileUpdate(newer)))
        val row = TestCore.onMain { core.history.contactRow(bea.account)!! }
        assertEquals("Bea Mensah", row.name)
        assertEquals(0xEC407A, row.colour)
        assertEquals(newer, Profile.decode(row.profile!!))
        assertEquals(Face.of(newer), TestCore.onMain { core.chatFace(core.history.chat(chatId)!!) })
        assertNull("a profile update is not a message", TestCore.onMain { core.history.lastMessage(chatId) })

        PersonFixture.deliver(PersonFixture.send(bea, Body.ProfileUpdate(Profile("Old Bea", 0x000000, revision = 2))))
        assertEquals("an older update never wins", newer, TestCore.onMain { Profile.decode(core.history.contactRow(bea.account)!!.profile!!) })
        assertNull(TestCore.onMain { core.history.lastMessage(chatId) })
    }
}
