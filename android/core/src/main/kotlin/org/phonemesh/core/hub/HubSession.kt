package org.phonemesh.core.hub

/**
 * The published state of one hub link (accounts plan step 16), made so Android's late callbacks cannot resurrect it.
 * Every join gets a generation from [begin]; every callback must present its generation, and one that is no longer
 * current publishes nothing (and is not even asked to build a client). [end] bumps the generation and closes the
 * published client, which aborts its sockets. Pure JVM: WifiHubLink routes all four callbacks through it.
 */
class HubSession {
    private var generation = 0L
    private var client: HubClient? = null

    /** Ends any earlier link and returns the generation the new join's callbacks must present. */
    @Synchronized fun begin(): Long {
        endNow()
        return ++generation
    }

    /** Leaves: later callbacks of any earlier join are ignored, and the published client's sockets are closed. */
    @Synchronized fun end() = endNow()

    /** Publishes the client [made] builds, only if [gen] is still current. A stale callback builds nothing. */
    @Synchronized fun available(gen: Long, made: () -> HubClient): Boolean {
        if (gen != generation) return false
        client?.close()
        client = made()
        return true
    }

    /** The network went away or was never found. True only if [gen] was current (and so state was cleared). */
    @Synchronized fun lost(gen: Long): Boolean {
        if (gen != generation) return false
        client?.close()
        client = null
        return true
    }

    @Synchronized fun wire(): HubWire? = client

    private fun endNow() {
        generation++
        client?.close()
        client = null
    }
}
