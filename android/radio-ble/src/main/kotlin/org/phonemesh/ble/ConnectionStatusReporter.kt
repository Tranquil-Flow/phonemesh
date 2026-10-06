package org.phonemesh.ble

/**
 * Decides what the app is told about connection readiness versus routine diagnostics.
 *
 * Pure logic, no Android dependency, so the readiness rules are unit tested rather than asserted by reading
 * transport source. Rules:
 * - readiness only changes on a real, connection-bearing outcome (radio ready, GATT lost, connect attempt);
 * - scanning, draining, node discovery, queue status and MTU/service steps are diagnostics and must not
 *   overwrite the connection label, in particular stopScan() must not report "not ready" while connected;
 * - a scan failure is a diagnostic about scanning only, not proof the connection was lost.
 */
internal class ConnectionStatusReporter {
    data class Status(val ready: Boolean, val detail: String, val changesReadiness: Boolean)

    @Volatile private var ready: Boolean = false

    val isReady: Boolean get() = ready

    fun radioReady(detail: String): Status = set(true, detail, true)

    fun connectionLost(detail: String): Status = set(false, detail, true)

    fun connecting(detail: String): Status = set(false, detail, true)

    fun diagnostic(detail: String): Status = Status(ready, detail, false)

    fun scanStopped(): Status = Status(ready, if (ready) "Scan stopped; radio connection still ready" else "BLE scan stopped", false)

    fun scanFailed(code: Int): Status = Status(
        ready,
        if (ready) "BLE scan failed: code=$code; radio connection still ready" else "BLE scan failed: code=$code",
        false,
    )

    private fun set(value: Boolean, detail: String, changes: Boolean): Status {
        ready = value
        return Status(value, detail, changes)
    }
}
