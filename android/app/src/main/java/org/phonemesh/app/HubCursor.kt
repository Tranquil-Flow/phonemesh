package org.phonemesh.app

/**
 * Per-hub mail cursor (accounts plan step 16): the newest hub seq whose letter is durably handled, keyed by the hub's
 * pin so a replaced hub never inherits another hub's position. [advance] must run inside the same History transaction
 * that stores the letter (or decides it is definitively not for this phone), so the cursor never moves past a letter
 * that was not committed.
 */
internal class HubCursor(private val history: History) {
    fun get(pin: String): Long = history.readableDatabase.rawQuery("SELECT seq FROM hub_cursor WHERE pin=?", arrayOf(pin))
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Moves forward only. */
    fun advance(pin: String, seq: Long) {
        require(seq >= 0)
        val db = history.writableDatabase
        db.execSQL("INSERT OR IGNORE INTO hub_cursor(pin, seq) VALUES(?, 0)", arrayOf<Any>(pin))
        db.execSQL("UPDATE hub_cursor SET seq=? WHERE pin=? AND seq<?", arrayOf<Any>(seq, pin, seq))
    }

    /** The hub lost its mail (its newest seq is below ours): start from the beginning. */
    fun reset(pin: String) {
        history.writableDatabase.delete("hub_cursor", "pin=?", arrayOf(pin))
        history.writableDatabase.delete("hub_custody", "pin=?", arrayOf(pin))
    }
}

/** Pinned custody, never an unsigned port-302 observation; rows survive a phone process restart. */
internal class HubCustody(private val history: History) {
    fun has(id: Long, pin: String): Boolean = history.readableDatabase.rawQuery(
        "SELECT 1 FROM hub_custody WHERE outbox_id=? AND pin=?", arrayOf(id.toString(), pin),
    ).use { it.moveToFirst() }

    /** Caller owns the transaction that also reconciles the visible bubble. */
    fun accepted(id: Long, pin: String) {
        history.writableDatabase.execSQL("INSERT OR IGNORE INTO hub_custody(outbox_id,pin) VALUES(?,?)", arrayOf<Any>(id, pin))
    }
}
