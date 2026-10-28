package org.phonemesh.core.update

/**
 * Where the phone keeps its [TrustState] (hub updates spec 5.3). Two slots; each state record is written whole into one slot,
 * the older one, so the newer record is never touched while it is being replaced. The store need not make a single write
 * atomic: after a crash a slot may hold the old bytes, the new bytes, a prefix of either, or nothing, and [UpdateTrust] still
 * finds the newest record that is whole (each carries its own SHA-256). The app backs this with one SQLite row per slot, or
 * two files; a store whose writes happen to be atomic loses nothing.
 */
interface TrustStore {
    /** The bytes last saved into [slot] (0 or 1), or null when that slot was never written. */
    fun load(slot: Int): ByteArray?

    /** Replaces the bytes of [slot], durably (synced) before returning; throws [java.io.IOException] if it cannot. */
    fun save(slot: Int, bytes: ByteArray)
}
