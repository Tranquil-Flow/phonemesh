package org.phonemesh.app

import android.content.Context

/**
 * A hardware probe (accounts plan T1/T2). The probes live in src/probe, compiled only into builds made with
 * -Pphonemesh.t1probe=true or -Pphonemesh.t2probe=true, so normal builds carry none of their code or permissions.
 */
interface RadioProbe {
    fun start()
    fun got(sender: String, payload: ByteArray, channel: Int, packetId: Long, signal: String) {}

    companion object {
        /** The probe class [name] if this build has it: built with (Context, send) when it takes a sender, else (Context). */
        fun load(name: String, app: Context, send: ((ByteArray) -> Long)? = null): RadioProbe? = runCatching {
            val type = Class.forName("org.phonemesh.app.$name")
            (if (send != null) type.getConstructor(Context::class.java, Function1::class.java).newInstance(app, send)
            else type.getConstructor(Context::class.java).newInstance(app)) as RadioProbe
        }.getOrNull()
    }
}
