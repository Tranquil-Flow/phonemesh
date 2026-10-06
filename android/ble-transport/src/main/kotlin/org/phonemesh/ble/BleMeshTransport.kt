package org.phonemesh.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Android BLE transport for one Meshtastic radio.
 *
 * The app owns the radio connection while this object is connected; Meshtastic radios generally allow one
 * phone/app BLE connection at a time. Public methods are intended to be invoked from explicit foreground UI
 * actions after the parent app has requested runtime permissions and let the user choose a device.
 */
class BleMeshTransport(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onState(connected: Boolean, detail: String)
        fun onText(sender: String, text: String, channel: Int)
        fun onDevice(address: String, name: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("PhonemeshMeshtasticBle").also { it.start() }
    private val workHandler = Handler(workThread.looper)
    private val closed = AtomicBoolean(false)
    private val serviceUuid = UUID.fromString(MESHTASTIC_SERVICE_UUID)
    private val toRadioUuid = UUID.fromString(TORADIO_UUID)
    private val fromRadioUuid = UUID.fromString(FROMRADIO_UUID)
    private val fromNumUuid = UUID.fromString(FROMNUM_UUID)
    private val cccdUuid = UUID.fromString(CCCD_UUID)
    private val peersById = LinkedHashMap<String, RadioPeer>()
    private val opQueue = GattOperationQueue()

    @Volatile private var scanning = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var toRadio: BluetoothGattCharacteristic? = null
    @Volatile private var fromRadio: BluetoothGattCharacteristic? = null
    @Volatile private var fromNum: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var wantedConfigId: Int? = null
    @Volatile private var negotiatedMtu = DEFAULT_ATT_MTU
    @Volatile private var localNodeNum: Int? = null
    private var drainInProgress = false
    private var drainAgain = false
    private var drainReadsRemaining = 0
    private var heartbeatNonce = MeshtasticProtocol.FIRST_HEARTBEAT_NONCE

    private val bluetoothManager: BluetoothManager?
        get() = appContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            postDevice(device.address, safeDeviceName(device, result.scanRecord?.deviceName))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            postState(false, "BLE scan failed: code=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            workHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            disconnectAndCleanup("BLE connect failed: status=$status")
                            return@post
                        }
                        this@BleMeshTransport.gatt = gatt
                        ready = false
                        negotiatedMtu = DEFAULT_ATT_MTU
                        postState(false, "GATT connected; negotiating MTU")
                        opQueue.clear()
                        enqueueMtuRequest(gatt)
                        enqueueServiceDiscovery(gatt)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val detail = if (status == BluetoothGatt.GATT_SUCCESS) {
                            "BLE radio disconnected"
                        } else {
                            "BLE radio disconnected: status=$status"
                        }
                        disconnectAndCleanup(detail)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            workHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
                opQueue.complete("requestMtu")
                postState(false, "BLE MTU ${if (status == BluetoothGatt.GATT_SUCCESS) "negotiated" else "request failed"}: $negotiatedMtu")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            workHandler.post {
                opQueue.complete("discoverServices")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndCleanup("GATT service discovery failed: status=$status")
                    return@post
                }
                setupMeshtasticService(gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            workHandler.post {
                opQueue.complete("writeDescriptor:${descriptor.uuid}")
                if (descriptor.uuid == cccdUuid && descriptor.characteristic?.uuid == fromNumUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        postState(false, "FROMNUM notifications enabled; requesting radio config")
                        requestInitialConfig()
                    } else {
                        disconnectAndCleanup("Failed to enable FROMNUM notifications: status=$status")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            workHandler.post {
                opQueue.complete("writeCharacteristic:${characteristic.uuid}")
                if (characteristic.uuid == toRadioUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        drainFromRadio("after toRadio write")
                    } else {
                        postState(false, "toRadio write failed: status=$status")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            onCharacteristicReadValue(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onCharacteristicReadValue(characteristic, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChangedValue(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onCharacteristicChangedValue(characteristic, value)
        }
    }

    fun scan() {
        checkOpen()
        val missing = missingScanPermission()
        if (missing != null) {
            postState(false, "Missing permission for BLE scan: $missing")
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            postState(false, "Bluetooth is disabled or unavailable")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            postState(false, "BLE scanner is unavailable")
            return
        }
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            postState(false, "Scanning for Meshtastic BLE radios")
        } catch (security: SecurityException) {
            postState(false, "BLE scan denied: ${security.message}")
        } catch (runtime: RuntimeException) {
            postState(false, "BLE scan failed: ${runtime.message}")
        }
    }

    fun stopScan() {
        if (!scanning) return
        val scanner = adapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission may have been revoked while scanning. State below is still correct for this object.
        } finally {
            scanning = false
            postState(false, "BLE scan stopped")
        }
    }

    fun connect(address: String) {
        checkOpen()
        val normalized = address.trim()
        require(normalized.isNotEmpty()) { "address must not be blank" }
        val missing = missingConnectPermission()
        if (missing != null) {
            postState(false, "Missing permission for BLE connect: $missing")
            return
        }
        stopScan()
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            postState(false, "Bluetooth is disabled or unavailable")
            return
        }
        workHandler.post {
            disconnectAndCleanup("Starting new BLE connection")
            try {
                val device = adapter.getRemoteDevice(normalized)
                postState(false, "Connecting to Meshtastic BLE radio $normalized")
                @Suppress("MissingPermission")
                gatt = if (Build.VERSION.SDK_INT >= 26) {
                    device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
                } else {
                    device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
                if (gatt == null) postState(false, "Android returned null BluetoothGatt for $normalized")
            } catch (security: SecurityException) {
                postState(false, "BLE connect denied: ${security.message}")
            } catch (bad: IllegalArgumentException) {
                postState(false, "Invalid BLE device address: $normalized")
            } catch (runtime: RuntimeException) {
                postState(false, "BLE connect failed: ${runtime.message}")
            }
        }
    }

    fun peers(): List<RadioPeer> = synchronized(peersById) { peersById.values.toList() }

    fun send(text: String, destinationId: String, channel: Int) {
        checkOpen()
        val payload = MeshtasticProtocol.buildTextToRadio(text, destinationId, channel)
        val maxWrite = maxWriteBytes()
        require(payload.size <= maxWrite) {
            "Encoded ToRadio is ${payload.size} bytes but negotiated BLE write limit is $maxWrite bytes"
        }
        workHandler.post {
            if (!ready || gatt == null || toRadio == null) {
                postState(false, "Cannot send: Meshtastic BLE radio is not ready")
                return@post
            }
            if (writeToRadio(payload, "text channel=$channel destination=$destinationId")) {
                postState(true, "Submitted text to radio GATT queue; this is not a peer ACK")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopScan()
        workHandler.post {
            val disconnectPacket = runCatching { MeshtasticProtocol.buildDisconnect() }.getOrNull()
            if (disconnectPacket != null && gatt != null && toRadio != null && disconnectPacket.size <= maxWriteBytes()) {
                writeToRadio(disconnectPacket, "disconnect")
            }
            disconnectAndCleanup("BleMeshTransport closed")
            workThread.quitSafely()
        }
    }

    private fun enqueueMtuRequest(gatt: BluetoothGatt) {
        opQueue.enqueue("requestMtu") {
            @Suppress("MissingPermission")
            gatt.requestMtu(TARGET_MTU)
        }
    }

    private fun enqueueServiceDiscovery(gatt: BluetoothGatt) {
        opQueue.enqueue("discoverServices") {
            @Suppress("MissingPermission")
            gatt.discoverServices()
        }
    }

    private fun setupMeshtasticService(gatt: BluetoothGatt) {
        val service: BluetoothGattService? = gatt.getService(serviceUuid)
        if (service == null) {
            disconnectAndCleanup("Meshtastic BLE service $serviceUuid not found")
            return
        }
        toRadio = service.getCharacteristic(toRadioUuid)
        fromRadio = service.getCharacteristic(fromRadioUuid)
        fromNum = service.getCharacteristic(fromNumUuid)
        val missing = buildList {
            if (toRadio == null) add("toRadio")
            if (fromRadio == null) add("fromRadio")
            if (fromNum == null) add("fromNum")
        }
        if (missing.isNotEmpty()) {
            disconnectAndCleanup("Meshtastic BLE service missing characteristic(s): ${missing.joinToString()}")
            return
        }
        enableFromNumNotifications(gatt, fromNum ?: return)
    }

    private fun enableFromNumNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(cccdUuid)
        if (descriptor == null) {
            disconnectAndCleanup("FROMNUM CCCD descriptor missing")
            return
        }
        opQueue.enqueue("writeDescriptor:$cccdUuid") {
            @Suppress("MissingPermission")
            val localSet = gatt.setCharacteristicNotification(characteristic, true)
            if (!localSet) return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun requestInitialConfig() {
        val nonce = MeshtasticProtocol.newNonce()
        wantedConfigId = nonce
        ready = false
        val packet = MeshtasticProtocol.buildWantConfig(nonce)
        if (packet.size > maxWriteBytes()) {
            disconnectAndCleanup("want_config_id packet exceeds negotiated BLE write limit")
            return
        }
        writeToRadio(packet, "want_config_id=$nonce")
        drainFromRadio("initial config")
    }

    private fun writeToRadio(payload: ByteArray, detail: String): Boolean {
        val gatt = gatt ?: return false.also { postState(false, "Cannot write toRadio: GATT disconnected") }
        val characteristic = toRadio ?: return false.also { postState(false, "Cannot write toRadio: characteristic missing") }
        if (payload.size > maxWriteBytes()) {
            postState(false, "Cannot write $detail: ${payload.size} bytes exceeds BLE write limit ${maxWriteBytes()}")
            return false
        }
        return opQueue.enqueue("writeCharacteristic:$toRadioUuid") {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("MissingPermission")
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }.also { accepted ->
            if (!accepted) postState(false, "BLE GATT operation queue full; dropped $detail")
        }
    }

    private fun drainFromRadio(reason: String) {
        workHandler.post {
            if (gatt == null || fromRadio == null) return@post
            if (drainInProgress) {
                drainAgain = true
                return@post
            }
            drainInProgress = true
            drainAgain = false
            drainReadsRemaining = MAX_DRAIN_READS
            postState(ready, "Draining fromRadio ($reason)")
            enqueueFromRadioRead()
        }
    }

    private fun enqueueFromRadioRead() {
        val gatt = gatt
        val characteristic = fromRadio
        if (gatt == null || characteristic == null) {
            drainInProgress = false
            return
        }
        if (drainReadsRemaining-- <= 0) {
            drainInProgress = false
            postState(ready, "fromRadio drain stopped after $MAX_DRAIN_READS packets; waiting for next FROMNUM")
            return
        }
        val accepted = opQueue.enqueue("readCharacteristic:$fromRadioUuid") {
            @Suppress("MissingPermission")
            gatt.readCharacteristic(characteristic)
        }
        if (!accepted) {
            drainInProgress = false
            postState(false, "BLE GATT operation queue full while draining fromRadio")
        }
    }

    private fun onCharacteristicReadValue(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        workHandler.post {
            opQueue.complete("readCharacteristic:${characteristic.uuid}")
            if (characteristic.uuid != fromRadioUuid) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                drainInProgress = false
                postState(false, "fromRadio read failed: status=$status")
                return@post
            }
            if (value.isEmpty()) {
                drainInProgress = false
                if (drainAgain) drainFromRadio("coalesced trigger")
                return@post
            }
            handleFromRadio(value)
            enqueueFromRadioRead()
        }
    }

    private fun onCharacteristicChangedValue(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        workHandler.post {
            if (characteristic.uuid == fromNumUuid) {
                val count = MeshtasticProtocol.fromNumLittleEndian(value)
                postState(ready, "FROMNUM notified${count?.let { " id=$it" } ?: ""}; draining fromRadio")
                drainFromRadio("FROMNUM notification")
            }
        }
    }

    private fun handleFromRadio(bytes: ByteArray) {
        val event = try {
            MeshtasticProtocol.parseFromRadio(bytes)
        } catch (ex: RuntimeException) {
            postState(ready, "Ignored invalid FromRadio protobuf (${bytes.size} bytes): ${ex.message}")
            return
        }
        when (event) {
            is RadioEvent.ConfigComplete -> {
                if (event.id == wantedConfigId) {
                    ready = true
                    postState(true, "Meshtastic BLE config complete id=${event.id}; radio ready")
                } else {
                    postState(false, "Ignored stale config_complete_id=${event.id}; wanted=$wantedConfigId")
                }
            }
            is RadioEvent.MyInfo -> {
                localNodeNum = event.nodeNum
                postState(ready, "Local Meshtastic node ${MeshtasticProtocol.nodeId(event.nodeNum.toLong())}")
            }
            is RadioEvent.NodeDiscovered -> {
                val peer = RadioPeer(event.id, event.name)
                synchronized(peersById) { peersById[event.id] = peer }
                postState(ready, "Node discovered ${event.id} ${event.name}")
            }
            is RadioEvent.Text -> postText(event.sender, event.text, event.channel)
            is RadioEvent.QueueStatus -> {
                val ok = event.res == 0
                postState(
                    ready,
                    "Radio queue status res=${event.res} free=${event.free}/${event.maxlen} packet=${event.meshPacketId}; " +
                        if (ok) "radio accepted queueing, not a peer ACK" else "radio queue rejected packet",
                )
            }
            null -> Unit
        }
    }

    private fun disconnectAndCleanup(detail: String) {
        ready = false
        wantedConfigId = null
        drainInProgress = false
        drainAgain = false
        opQueue.clear()
        toRadio = null
        fromRadio = null
        fromNum = null
        val old = gatt
        gatt = null
        if (old != null) {
            try {
                @Suppress("MissingPermission")
                old.disconnect()
            } catch (_: SecurityException) {
                // Permission may have been revoked; still release Java-side resources.
            } catch (_: RuntimeException) {
                // Device disappeared mid-cleanup.
            }
            try {
                old.close()
            } catch (_: RuntimeException) {
                // Android stack already dropped the handle.
            }
        }
        postState(false, detail)
    }

    private fun maxWriteBytes(): Int = (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(DEFAULT_ATT_MTU - ATT_HEADER_BYTES)

    private fun safeDeviceName(device: BluetoothDevice, advertisedName: String?): String {
        advertisedName?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            @Suppress("MissingPermission")
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    private fun missingScanPermission(): String? = when {
        Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_SCAN) -> Manifest.permission.BLUETOOTH_SCAN
        Build.VERSION.SDK_INT in 23..30 && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    private fun missingConnectPermission(): String? = when {
        Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT) -> Manifest.permission.BLUETOOTH_CONNECT
        else -> null
    }

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun checkOpen() = check(!closed.get()) { "BleMeshTransport is closed" }

    private fun postState(connected: Boolean, detail: String) {
        mainHandler.post { listener.onState(connected, detail) }
    }

    private fun postText(sender: String, text: String, channel: Int) {
        mainHandler.post { listener.onText(sender, text, channel) }
    }

    private fun postDevice(address: String, name: String) {
        mainHandler.post { listener.onDevice(address, name) }
    }

    private inner class GattOperationQueue : Closeable {
        private val pending = ArrayDeque<GattOperation>()
        private var active: GattOperation? = null

        fun enqueue(name: String, start: () -> Boolean): Boolean {
            if (closed.get()) return false
            if (pending.size >= MAX_PENDING_GATT_OPS) return false
            pending.add(GattOperation(name, start))
            pump()
            return true
        }

        fun complete(name: String) {
            val current = active ?: return
            if (current.name != name) return
            workHandler.removeCallbacks(current.timeoutRunnable)
            active = null
            pump()
        }

        fun clear() {
            active?.let { workHandler.removeCallbacks(it.timeoutRunnable) }
            active = null
            pending.clear()
        }

        override fun close() = clear()

        private fun pump() {
            if (active != null) return
            val next = pending.poll() ?: return
            active = next
            next.timeoutRunnable = Runnable {
                if (active === next) {
                    active = null
                    postState(false, "GATT operation timed out: ${next.name}")
                    pump()
                }
            }
            try {
                val started = next.start()
                if (!started) {
                    workHandler.removeCallbacks(next.timeoutRunnable)
                    active = null
                    postState(false, "GATT operation failed to start: ${next.name}")
                    pump()
                } else {
                    workHandler.postDelayed(next.timeoutRunnable, GATT_OPERATION_TIMEOUT_MS)
                }
            } catch (security: SecurityException) {
                active = null
                postState(false, "GATT operation denied (${next.name}): ${security.message}")
                pump()
            } catch (runtime: RuntimeException) {
                active = null
                postState(false, "GATT operation failed (${next.name}): ${runtime.message}")
                pump()
            }
        }
    }

    private data class GattOperation(
        val name: String,
        val start: () -> Boolean,
        var timeoutRunnable: Runnable = Runnable {},
    )

    companion object {
        private const val TARGET_MTU = 512
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
        private const val MAX_DRAIN_READS = 64
        private const val MAX_PENDING_GATT_OPS = 32
    }
}
