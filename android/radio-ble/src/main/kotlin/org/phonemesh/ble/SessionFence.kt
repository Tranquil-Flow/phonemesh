package org.phonemesh.ble

/** Identity, not address/equality: callbacks from a retired GATT must never affect its replacement. */
internal class SessionFence<T:Any> {
    @Volatile private var current:T?=null
    fun replace(value:T?) { current=value }
    fun accepts(value:T):Boolean = current===value
}
