package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile

/** Review B2: a contact's profile frozen at the top revision is repaired by scanning their card again in person. */
@RunWith(AndroidJUnit4::class)
class ProfileRepairTest {
    private val core get() = TestCore.core

    @After fun tearDown() = TestCore.uninstall()

    @Test fun scanningTheirCardAgainRepairsAFrozenProfile() {
        TestCore.install()
        val (bea, _) = PersonFixture.meet()
        val row = core.history.contactRow(bea.account)!!
        val frozen = Profile("Frozen", 0x000000, Avatar.Letter, Profile.MAX_REVISION)
        core.history.putContact(History.ContactRow(row.accountId, row.card, frozen.name, frozen.colour, row.met, row.voice, row.added, frozen.encode()))
        assertEquals("Frozen", core.history.contactRow(bea.account)!!.name)
        fun scan(): Accounts.Added = TestCore.onMain { core.accountsFlow.showCode(); core.accountsFlow.scanned(AddCode.of(bea.qr)) }
        var added = scan()
        if (added is Accounts.Added.ShowYoursFirst) added = scan()
        assertTrue(added.toString(), added is Accounts.Added.Contact)
        val repaired = core.history.contactRow(bea.account)!!
        assertEquals("Bea", repaired.name)
        assertEquals(1L, Profile.decode(repaired.profile!!)!!.revision)
    }
}
