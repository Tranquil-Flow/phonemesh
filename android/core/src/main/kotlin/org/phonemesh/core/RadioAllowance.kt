package org.phonemesh.core

/**
 * The phone's own radio allowance (radio airtime decision 53, AE4 in-app backstop): at most [budgetMs] of private
 * mail on air in any [windowMs], so one phone on its own radio leaves room in the 10 % legal ceiling for relays and
 * other people. A hub enforces the shared share; this only stops one phone running away. Kept across restarts
 * ([encode]); an airing dated after "now" (a clock set back) still counts. Not thread-safe: one caller.
 */
class RadioAllowance(val budgetMs: Double = 180_000.0, val windowMs: Long = 3_600_000L) {
    private val airings = ArrayList<Pair<Long, Double>>()

    /** Milliseconds on air in the window ending at [now]. */
    fun used(now: Long): Double { prune(now); return airings.sumOf { it.second } }

    /** Whether [ms] more fits now. One airing longer than the whole budget still goes when nothing else did. */
    fun allows(now: Long, ms: Double): Boolean { val u = used(now); return u == 0.0 || u + ms <= budgetMs }

    fun record(now: Long, ms: Double) {
        prune(now)
        airings += now to ms
        // Bounded: fold the two oldest together, dated at the later one, so nothing is ever counted for less time.
        while (airings.size > MAX_ENTRIES) {
            val (a, b) = airings[0] to airings[1]
            airings.subList(0, 2).clear()
            airings.add(0, b.first to a.second + b.second)
        }
    }

    /** When [ms] fits again, or null when it fits now. */
    fun nextRoom(now: Long, ms: Double): Long? {
        if (allows(now, ms)) return null
        var left = used(now)
        for ((at, spent) in airings.sortedBy { it.first }) {
            left -= spent
            if (left <= 0.0 || left + ms <= budgetMs) return at + windowMs + 1
        }
        return now + windowMs + 1
    }

    fun encode(): String = airings.joinToString(";") { "${it.first}:${it.second}" }

    private fun prune(now: Long) { airings.removeAll { it.first <= now - windowMs } }

    companion object {
        const val MAX_ENTRIES = 512

        /** Reads [encode]'s text; anything malformed starts empty rather than guessing. */
        fun decode(text: String?): RadioAllowance {
            val a = RadioAllowance()
            if (text.isNullOrEmpty()) return a
            val parsed = text.split(';').map { part ->
                val f = part.split(':')
                if (f.size != 2) return a
                val at = f[0].toLongOrNull() ?: return a
                val ms = f[1].toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() } ?: return a
                at to ms
            }
            a.airings += parsed.sortedBy { it.first }.takeLast(MAX_ENTRIES)
            return a
        }
    }
}
