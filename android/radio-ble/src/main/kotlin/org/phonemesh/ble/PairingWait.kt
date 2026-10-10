package org.phonemesh.ble

import android.bluetooth.BluetoothDevice

/**
 * One connection attempt's wait for Android to pair the phone with the radio, so one tap is enough for a radio
 * that was never paired. Confined to the radio worker; pure logic behind seams, so it is tested without Bluetooth.
 *
 * - [begin]: a paired radio is opened at once ([open]). An unpaired one is paired first ([pair]) and opened only
 *   when Android reports it paired; when pairing cannot be asked for, it is opened as before.
 * - [onBond]: Android's reports for this radio. Pairing that Android starts by itself, in the middle of a
 *   connection, is waited for too, and the radio is then opened afresh, once.
 * - While [waiting], [linkFailed] swallows what would end the attempt on a paired radio (an operation or
 *   connection timeout, a refused step, a dropped link). Pairing that is refused or cancelled, or that takes
 *   longer than [limitMs], ends the attempt once through [fail].
 */
internal class PairingWait(
    private val later: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val bond: () -> Int,
    private val pair: () -> Boolean,
    private val open: () -> Unit,
    private val fail: (String) -> Unit,
    private val told: (Boolean) -> Unit,
    private val limitMs: Long = LIMIT_MS,
) {
    var waiting = false
        private set
    private val limit = Runnable { if (waiting) { leave(); fail("Pairing took too long") } }

    fun begin() {
        when (bond()) {
            BluetoothDevice.BOND_BONDED -> open()
            BluetoothDevice.BOND_BONDING -> hold()
            else -> if (pair()) hold() else open()
        }
    }

    fun onBond(state: Int) {
        when (state) {
            BluetoothDevice.BOND_BONDING -> hold()
            BluetoothDevice.BOND_BONDED -> if (waiting) { leave(); open() }
            BluetoothDevice.BOND_NONE -> if (waiting) { leave(); fail("Pairing was refused or cancelled") }
        }
    }

    /** A failure of the link: nothing while Android is pairing, the end of the attempt otherwise. */
    fun linkFailed(detail: String) {
        if (!waiting) fail(detail)
    }

    /** The attempt is over for another reason (a new connection, the user, the transport closing). */
    fun end() {
        if (waiting) leave()
    }

    private fun hold() {
        if (waiting) return
        waiting = true
        later(limit, limitMs)
        told(true)
    }

    private fun leave() {
        waiting = false
        cancel(limit)
        told(false)
    }

    companion object {
        /** Time to find the code and type it. */
        const val LIMIT_MS = 90_000L
    }
}
