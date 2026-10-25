package org.phonemesh.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import org.phonemesh.core.hub.GattPort
import org.phonemesh.core.hub.GattStreams
import org.phonemesh.core.hub.HubGatt
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's side of the Bluetooth hub link (accounts decision 19): one GATT connection from this phone to the hub's
 * Bluetooth bridge, for one attempt. It finds the hub by the service UUID that the hub QR's pin derives
 * ([HubGatt.serviceUuid]) and connects to the first device advertising it. That is only a hint: the device's name and
 * address are never trusted or stored, and nothing is sent that is not inside TLS pinned to the QR's key (HubClient does
 * that over [GattStreams]); a device that merely advertises the UUID learns nothing.
 *
 * Lessons of [BleMeshTransport] kept: nothing is asked of GATT directly from the connected callback (a short delay first,
 * Android 10 drops requests made in that gap), every GATT operation goes through [SafeGattQueue] (one at a time; a
 * timeout is final, never a licence to overlap), a setup that fails before the link is ready is retried by [SetupRetry],
 * and every Android callback is checked against the [SessionFence] before it does anything. One total deadline covers scan,
 * connection, retries and setup. Only permissions the app already holds are used; none is requested here.
 *
 * Threading: everything runs on this object's own worker thread. [Listener] callbacks come from that thread and must not
 * block. After [close], or after [Listener.onEnded], no callback is made. Not shared between attempts: a new join makes a
 * new central, so a late callback of an old one can only reach a dead object.
 */
class GattHubCentral(
    context: Context,
    private val pin: String,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        /** The link is ready. [streams] belongs to this one GATT connection and is dead once it ends. */
        fun onReady(streams: GattStreams)

        /** The attempt or the ready link ended for good ([streams] is the one announced by [onReady], if any). Once only. */
        fun onEnded(streams: GattStreams?, reason: String)
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("PhonemeshHubGatt").also { it.start() }
    private val worker = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val serviceUuid = UUID.fromString(HubGatt.serviceUuid(pin))
    private val rxUuid = UUID.fromString(HubGatt.RX_UUID)
    private val txUuid = UUID.fromString(HubGatt.TX_UUID)
    private val cccdUuid = UUID.fromString(CCCD_UUID)
    private val fence = SessionFence<BluetoothGatt>()
    private val queue = SafeGattQueue(
        { task, delay -> worker.postDelayed(task, delay); Unit },
        { task -> worker.removeCallbacks(task) },
        { reason -> fail(reason, retry = true) },
    )
    private val retry = SetupRetry(
        later = { task, delay -> worker.postDelayed(task, delay); Unit },
        cancel = { task -> worker.removeCallbacks(task) },
        again = { if (!closed.get() && !ended && !ready) attempt() },
    )

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var ready = false
    private var ended = false
    private var streams: GattStreams? = null
    private var overall: Runnable? = null
    private var scanTimeout: Runnable? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var open = false

    /** Starts the one attempt. Returns at once; the outcome is [Listener.onReady] or [Listener.onEnded]. */
    fun start() {
        worker.post {
            if (closed.get() || ended) return@post
            val missing = missingPermission()
            if (missing != null) return@post giveUp("Bluetooth permission missing: $missing")
            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled) return@post giveUp("Bluetooth is off or unavailable")
            val total = Runnable { if (!ready) giveUp("the Bluetooth hub was not reached in time") }
            overall = total
            worker.postDelayed(total, ATTEMPT_MS)
            retry.begin()
            attempt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.post {
            val s = streams
            streams = null
            cleanup()
            s?.linkClosed("the Bluetooth link was closed")
            thread.quitSafely()
        }
    }

    // ---- everything below runs on the worker

    private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun missingPermission(): String? = when {
        Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN) -> Manifest.permission.BLUETOOTH_SCAN
        Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT) -> Manifest.permission.BLUETOOTH_CONNECT
        Build.VERSION.SDK_INT < 31 && !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    private fun granted(permission: String) = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun attempt() {
        cleanup()
        val scanner = try {
            adapter()?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }
        if (scanner == null) return fail("the Bluetooth scanner is unavailable", retry = true)
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            @Suppress("MissingPermission")
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            return giveUp("Bluetooth scan denied")
        } catch (e: RuntimeException) {
            return fail("the Bluetooth scan failed", retry = true)
        }
        val timeout = Runnable { if (scanning && !ready) fail("no hub in Bluetooth reach", retry = true) }
        scanTimeout = timeout
        worker.postDelayed(timeout, SCAN_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            worker.post { if (scanning && !closed.get() && !ended) connect(device) }
        }

        override fun onScanFailed(errorCode: Int) {
            worker.post { if (scanning) fail("the Bluetooth scan failed ($errorCode)", retry = true) }
        }
    }

    private fun stopScan() {
        scanTimeout?.let { worker.removeCallbacks(it) }
        scanTimeout = null
        if (!scanning) return
        scanning = false
        try {
            @Suppress("MissingPermission")
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: RuntimeException) {
            // Permission revoked or Bluetooth switched off meanwhile; the scan is over either way.
        }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        try {
            @Suppress("MissingPermission")
            val opened = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
            if (opened == null) return fail("Android gave no Bluetooth connection", retry = true)
            gatt = opened
            fence.replace(opened)
        } catch (e: SecurityException) {
            giveUp("Bluetooth connect denied")
        } catch (e: RuntimeException) {
            fail("the Bluetooth connection failed", retry = true)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            worker.post {
                if (!current(g)) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    queue.clear()
                    // Android 10 reports the link before its Bluetooth service has registered it and drops a request made in that gap.
                    worker.postDelayed({
                        if (current(g) && !ready) {
                            queue.enqueue("requestMtu", timeoutMs = MTU_TIMEOUT_MS) {
                                @Suppress("MissingPermission")
                                g.requestMtu(TARGET_MTU)
                            }
                            queue.enqueue("discoverServices") {
                                @Suppress("MissingPermission")
                                g.discoverServices()
                            }
                        }
                    }, FIRST_REQUEST_DELAY_MS)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    fail("the Bluetooth link ended (status $status)", retry = !ready)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            worker.post {
                if (!current(g)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                queue.complete("requestMtu")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            worker.post {
                if (!current(g)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) return@post fail("Bluetooth service discovery failed ($status)", retry = true)
                queue.complete("discoverServices")
                val service = g.getService(serviceUuid) ?: return@post fail("that device is not this hub", retry = true)
                val writeChar = service.getCharacteristic(rxUuid)
                val notifyChar = service.getCharacteristic(txUuid)
                if (writeChar == null || notifyChar == null ||
                    (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 ||
                    (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                ) return@post fail("that device is not this hub", retry = true)
                rx = writeChar
                tx = notifyChar
                subscribe(g, notifyChar)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            worker.post {
                if (!current(g)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) return@post fail("Bluetooth notifications refused ($status)", retry = true)
                queue.complete("writeDescriptor")
                if (descriptor.characteristic?.uuid == txUuid) becomeReady()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            worker.post {
                if (!current(g)) return@post
                queue.complete("write")
                val s = streams ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    s.written(false)
                    return@post fail("a Bluetooth write failed ($status)", retry = false)
                }
                s.written(true)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // The array is copied here, on Android's thread, before a later notification can replace it.
            notified(g, characteristic, characteristic.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notified(g, characteristic, value.copyOf())
        }
    }

    private fun notified(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        worker.post {
            if (!current(g) || characteristic.uuid != txUuid) return@post
            streams?.onFrame(value)
        }
    }

    private fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: return fail("that device has no notification switch", retry = true)
        queue.enqueue("writeDescriptor") {
            @Suppress("MissingPermission")
            val local = g.setCharacteristicNotification(characteristic, true)
            if (!local) return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("MissingPermission")
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION", "MissingPermission")
                g.writeDescriptor(descriptor)
            }
        }
    }

    private fun becomeReady() {
        if (ready || ended || closed.get()) return
        ready = true
        open = true
        retry.end()
        overall?.let { worker.removeCallbacks(it) }
        overall = null
        val port = object : GattPort {
            override fun maxFrame(): Int = (mtu - ATT_HEADER).coerceIn(HubGatt.MIN_FRAME, HubGatt.MAX_FRAME)

            override fun write(frame: ByteArray): Boolean {
                if (closed.get() || !open) return false
                val bytes = frame.copyOf()
                worker.post { send(bytes) }
                return true
            }
        }
        val made = GattStreams(port) { reason -> worker.post { fail(reason, retry = false) } }
        streams = made
        listener.onReady(made)
    }

    /** One frame to the hub, through the one-at-a-time queue. A refusal ends the link: its state would be unknown. */
    private fun send(frame: ByteArray) {
        val g = gatt
        val characteristic = rx
        val s = streams
        if (g == null || characteristic == null || s == null || !ready) return
        if (frame.size > (mtu - ATT_HEADER).coerceAtLeast(HubGatt.MIN_FRAME)) {
            s.written(false)
            return
        }
        val accepted = queue.enqueue("write") {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("MissingPermission")
                g.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION", "MissingPermission")
                g.writeCharacteristic(characteristic)
            }
        }
        if (!accepted && streams === s) s.written(false)
    }

    private fun current(g: BluetoothGatt) = !closed.get() && !ended && fence.accepts(g)

    /** Drops the GATT connection and every pending thing of it, but not the attempt itself. */
    private fun cleanup() {
        open = false
        stopScan()
        queue.clear()
        fence.replace(null)
        rx = null
        tx = null
        mtu = DEFAULT_MTU
        val old = gatt
        gatt = null
        if (old != null) {
            try {
                @Suppress("MissingPermission")
                old.disconnect()
            } catch (_: RuntimeException) {
            }
            try {
                old.close()
            } catch (_: RuntimeException) {
            }
        }
    }

    /** This try failed. Before the link was ready, the setup is tried again (twice); afterwards, or with [retry] false, it is over. */
    private fun fail(reason: String, retry: Boolean) {
        if (ended || closed.get()) return
        val wasReady = ready
        ready = false
        val s = streams
        streams = null
        cleanup()
        s?.linkClosed(reason)
        if (retry && !wasReady && this.retry.failed()) return
        finish(s, reason)
    }

    private fun giveUp(reason: String) = fail(reason, retry = false)

    private fun finish(s: GattStreams?, reason: String) {
        if (ended) return
        ended = true
        retry.end()
        overall?.let { worker.removeCallbacks(it) }
        overall = null
        thread.quitSafely()
        if (!closed.get()) listener.onEnded(s, reason)
    }

    private companion object {
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val TARGET_MTU = 517
        const val DEFAULT_MTU = 23
        const val ATT_HEADER = 3
        const val FIRST_REQUEST_DELAY_MS = 500L
        const val MTU_TIMEOUT_MS = 4_000L
        const val SCAN_MS = 12_000L
        const val ATTEMPT_MS = 45_000L
    }
}
