package org.phonemesh.app.demo

/** The demo world's time. Android runs it on the demo's own thread; tests advance it by hand. */
interface DemoClock {
    fun now(): Long
    fun after(millis: Long, task: () -> Unit)
}

/** Small durable values (the demo radio's region, the world's progress). */
interface DemoState {
    fun string(key: String): String?
    fun put(key: String, value: String?)
}
