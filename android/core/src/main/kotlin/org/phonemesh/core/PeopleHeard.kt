package org.phonemesh.core

/** Group membership is unknowable on Meshtastic; groups show distinct senders heard plus the user. */
object PeopleHeard {
    fun count(senders: Collection<String>, ownId: String?): Int = senders.filter { it != ownId }.toSet().size + 1
}
