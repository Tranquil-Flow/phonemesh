package org.phonemesh.core.wire

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.Names

/**
 * How a shared-chat sender sits in History's sender column: `~`, the claimed account id (16 lower-case hex digits), then the
 * claimed name. A radio id always starts with `!`, so the two never meet. The name is claimed, not proven (review F9):
 * nothing here looks a contact up, so a saved contact's name or colour is never shown for a claim.
 */
object ClaimedSender {
    private const val MARK = '~'
    private const val ID_DIGITS = AccountId.SIZE * 2

    class Claim(val account: AccountId, val name: String)

    private fun claimable(name: String) = Names.valid(name) && name.toByteArray(Charsets.UTF_8).size <= SharedChatWire.MAX_NAME_BYTES

    fun key(account: AccountId, name: String): String {
        require(claimable(name)) { "not a claimable name" }
        return "$MARK$account$name"
    }

    /** The claim a [key] holds, or null for a radio id and for anything that is not a well-formed claim. */
    fun parse(key: String): Claim? {
        if (key.length <= 1 + ID_DIGITS || key[0] != MARK) return null
        val digits = key.substring(1, 1 + ID_DIGITS)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val name = key.substring(1 + ID_DIGITS)
        if (!claimable(name)) return null
        return Claim(AccountId.fromBytes(ByteArray(AccountId.SIZE) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }), name)
    }

    /** Who counts as one person in a group: the claimed account whatever name it claims (also a reaction's name-less `~id`), or the old radio id. */
    fun person(key: String): String = parse(key)?.let { "$MARK${it.account}" } ?: key
}
