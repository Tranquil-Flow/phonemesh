package org.phonemesh.core

/**
 * One animated number over a scene's loop. Keys are (time 0..1, value); smoothstep eases
 * between keys, close to the mockup's CSS ease-in-out. Repeated times make a step.
 */
class Track(keys: List<Pair<Float, Float>>) {
    private val times = FloatArray(keys.size) { keys[it].first }
    private val values = FloatArray(keys.size) { keys[it].second }

    init {
        require(keys.size >= 2) { "A track needs at least two keys" }
        require(times.all { it.isFinite() } && values.all { it.isFinite() }) { "Keys must be finite" }
        require(times.first() == 0f && times.last() == 1f) { "A track runs from 0 to 1" }
        require((1 until times.size).all { times[it] >= times[it - 1] }) { "Keys go forward in time" }
    }

    fun at(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        var i = 1
        while (i < times.size - 1 && times[i] < x) i++
        // At a repeated time, the last key wins; otherwise interpolate into the next key.
        while (i < times.size - 1 && times[i] == x && times[i + 1] == x) i++
        val t0 = times[i - 1]
        val t1 = times[i]
        if (x <= t0 || t1 <= t0) return if (x < t1) values[i - 1] else values[i]
        val u = ((x - t0) / (t1 - t0)).coerceIn(0f, 1f)
        val eased = u * u * (3f - 2f * u)
        return values[i - 1] + (values[i] - values[i - 1]) * eased
    }

    companion object {
        /** Time:value pairs, with time as a fraction of the loop (CSS 22% is .22). */
        fun of(spec: String): Track = Track(spec.trim().split(Regex("\\s+")).map { pair ->
            val parts = pair.split(':')
            require(parts.size == 2) { "Bad key \"$pair\"" }
            val time = requireNotNull(parts[0].toFloatOrNull()) { "Bad time in \"$pair\"" }
            val value = requireNotNull(parts[1].toFloatOrNull()) { "Bad value in \"$pair\"" }
            time to value
        })
    }
}

/**
 * A picture's length, reduced-motion still and named timings read by its drawing.
 * [stillShows] is checked at construction so timing edits cannot silently lose the action.
 */
class HowToScene(
    val name: String,
    val durationMs: Long,
    val still: Float,
    tracks: Map<String, String>,
    val stillShows: Map<String, Float>,
) {
    val names: List<String> = tracks.keys.toList()
    private val parsed: List<Track> = tracks.values.map { Track.of(it) }

    init {
        require(still in 0f..1f) { "$name: still must be in 0..1" }
        stillShows.forEach { (track, expected) ->
            val actual = value(track, still)
            require(kotlin.math.abs(actual - expected) <= .05f) { "$name: still frame shows $track=$actual, not $expected" }
        }
    }

    fun index(track: String): Int = names.indexOf(track).also { require(it >= 0) { "$name has no track $track" } }

    /** Writes every value in [names] order to the reusable frame buffer. Allocates nothing. */
    fun at(t: Float, out: FloatArray) {
        for (i in parsed.indices) out[i] = parsed[i].at(t)
    }

    fun value(track: String, t: Float): Float = parsed[index(track)].at(t)
}
