package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleHeardTest {
    @Test fun distinctSendersPlusMe() = assertEquals(3, PeopleHeard.count(listOf("!a", "!b", "!a"), null))
    @Test fun myOwnRadioIsNotCountedTwice() = assertEquals(2, PeopleHeard.count(listOf("!a", "!me"), "!me"))
    @Test fun aloneIsOne() = assertEquals(1, PeopleHeard.count(emptyList(), null))
}
