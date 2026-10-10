package org.phonemesh.ble

import java.util.ArrayDeque

/** Confined to one worker. An uncertain GATT timeout is terminal, never a licence to overlap operations. */
internal class SafeGattQueue(
    private val schedule:(Runnable,Long)->Unit,
    private val cancel:(Runnable)->Unit,
    private val fatal:(String)->Unit,
    private val timeoutMs:Long=10000,
    private val maxPending:Int=32,
) {
    private class Operation(val name:String,val start:()->Boolean) {
        var rejected=false
        var timer:Runnable=Runnable {}
    }
    private val pending=ArrayDeque<Operation>()
    private var active:Operation?=null
    private fun held():Int = pending.size+(if(active==null) 0 else 1)
    fun enqueue(name:String,start:()->Boolean):Boolean {
        if(!room(1)) return false
        val op=Operation(name,start)
        pending.add(op)
        pump()
        return !op.rejected
    }
    /** True when [count] more operations fit now, so none of them can be refused for space. */
    fun room(count:Int):Boolean = held()+count<=maxPending
    fun complete(name:String) {
        val op=active ?: return
        if(op.name!=name) return
        cancel(op.timer)
        active=null
        pump()
    }
    fun clear() {
        active?.let { cancel(it.timer); it.rejected=true }
        active=null
        pending.forEach { it.rejected=true }
        pending.clear()
    }
    private fun fail(reason:String) {
        clear()
        fatal(reason)
    }
    private fun pump() {
        if(active!=null) return
        val op=pending.poll() ?: return
        active=op
        op.timer=Runnable { if(active===op) fail("GATT operation timed out: ${op.name}") }
        try {
            if(!op.start()) { fail("GATT operation failed to start: ${op.name}"); return }
            schedule(op.timer,timeoutMs)
        } catch(error:Exception) {
            fail("GATT operation failed: ${op.name} (${error.javaClass.simpleName})")
        }
    }
}
