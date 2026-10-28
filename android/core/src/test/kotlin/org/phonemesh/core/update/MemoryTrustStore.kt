package org.phonemesh.core.update

/** A [TrustStore] in memory for tests: two slots, a log of every save, and hooks to fail or crash a save. */
class MemoryTrustStore(initial: List<ByteArray?> = listOf(null, null)) : TrustStore {
    class Crash : Error("simulated crash")

    val slots: MutableList<ByteArray?> = initial.map { it?.copyOf() }.toMutableList()
    val saves = ArrayList<Pair<Int, ByteArray>>()
    var failNext = false
    /** Declares the store's writes atomic (like the file store), so a damaged slot is damage and not a torn write. */
    var atomic = false
    override val atomicSlotWrites: Boolean get() = atomic
    /** When set, the next save writes [tear] of its bytes (a prefix, over the old content) and then crashes. */
    var tear: ((slot: Int, bytes: ByteArray, old: ByteArray?) -> ByteArray)? = null

    override fun load(slot: Int): ByteArray? = slots[slot]?.copyOf()

    override fun save(slot: Int, bytes: ByteArray) {
        if (failNext) { failNext = false; throw java.io.IOException("disk full") }
        tear?.let { t -> tear = null; slots[slot] = t(slot, bytes, slots[slot]); throw Crash() }
        saves += slot to bytes.copyOf()
        slots[slot] = bytes.copyOf()
    }

    fun copy() = MemoryTrustStore(slots).also { it.atomic = atomic }
}
