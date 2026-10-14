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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Android BLE transport for one Meshtastic radio.
 *
 * The app owns the radio connection while this object is connected; Meshtastic radios generally allow one
 * phone/app BLE connection at a time. A radio that Android has not paired with yet is paired first and connected
 * when pairing ends, inside the same [connect] ([PairingWait]). Public methods are intended to be invoked from explicit foreground UI
 * actions after the parent app has requested runtime permissions and let the user choose a device.
 */
class BleMeshTransport(
    context: Context,
    private val listener: MeshRadio.Listener,
) : MeshRadio {

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
    private val session = SessionFence<BluetoothGatt>()
    private val opQueue = SafeGattQueue(
        { runnable, delay -> workHandler.postDelayed(runnable, delay); Unit },
        { runnable -> workHandler.removeCallbacks(runnable) },
        { reason -> pairing.linkFailed(reason) },
    )
    private var connectionDeadline: Runnable? = null
    /** The radio of the connection being made or held; its bond reports are watched until that connection ends. */
    private var target: BluetoothDevice? = null
    private var watchingBond = false
    private val pairing: PairingWait = PairingWait(
        later = { task, delay -> workHandler.postDelayed(task, delay); Unit },
        cancel = { task -> workHandler.removeCallbacks(task) },
        bond = {
            try {
                @Suppress("MissingPermission")
                target?.bondState ?: BluetoothDevice.BOND_NONE
            } catch (_: SecurityException) {
                BluetoothDevice.BOND_NONE
            }
        },
        pair = {
            try {
                @Suppress("MissingPermission")
                target?.createBond() == true
            } catch (_: SecurityException) {
                false
            }
        },
        open = { openGatt() },
        fail = { reason ->
            if (!ready && target != null && !closed.get() && setupRetry.failed()) {
                disconnectAndCleanup("$reason; trying again", announceFailure = false, keepAttempt = true)
            } else {
                disconnectAndCleanup(reason)
            }
        },
        told = { waiting -> mainHandler.post { if (!closed.get()) listener.onPairing(waiting) }; Unit },
    )
    private val setupRetry = SetupRetry(
        later = { task, delay -> workHandler.postDelayed(task, delay); Unit },
        cancel = { task -> workHandler.removeCallbacks(task) },
        again = { if (!closed.get() && !ready && target != null) openGatt() },
    )

    /** Delivered on the radio worker. A ready connection is never disturbed by a bond report. */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val wanted = target ?: return
            if (ready || device?.address != wanted.address) return
            pairing.onBond(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
        }
    }

    @Volatile private var scanning = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var toRadio: BluetoothGattCharacteristic? = null
    @Volatile private var fromRadio: BluetoothGattCharacteristic? = null
    @Volatile private var fromNum: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var wantedConfigId: Int? = null
    @Volatile private var negotiatedMtu = DEFAULT_ATT_MTU
    @Volatile private var localNodeNum: Int? = null
    @Volatile private var ownName: String? = null
    @Volatile private var channelName: String? = null
    @Volatile private var configuredHopLimit: Int? = null
    @Volatile private var modemPreset: String? = null
    private var drainInProgress = false
    private var drainAgain = false
    private var drainReadsRemaining = 0
    private val status = ConnectionStatusReporter()
    private val delivery = DeliveryTracker(now = { android.os.SystemClock.elapsedRealtime() })
    private var expiryTick: Runnable? = null
    private val regions = RegionSession(
        ready = { ready },
        local = { localNodeNum },
        write = { admin, node, wantResponse ->
            writeToRadio(MeshtasticProtocol.buildAdmin(admin, node, wantResponse = wantResponse), "admin ${admin.payloadVariantCase}")
        },
        // Every refusal writeToRadio can give for space or size, checked for the whole edit before its first message.
        room = { messages, node ->
            gatt != null && toRadio != null && opQueue.room(messages.size) &&
                messages.all { MeshtasticProtocol.buildAdmin(it, node, wantResponse = false).size <= maxWriteBytes() }
        },
        later = { task, delay -> workHandler.postDelayed(task, delay); Unit },
        cancel = { task -> workHandler.removeCallbacks(task) },
        finish = { done, result ->
            post(status.diagnostic("Region write: $result"))
            mainHandler.post { if (!closed.get()) done(result) }
            Unit
        },
    )


    private val bluetoothManager: BluetoothManager?
        get() = appContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            postDevice(device.address, safeDeviceName(device, result.scanRecord?.deviceName), result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            post(status.scanFailed(errorCode))
            postScanStopped()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            workHandler.post {
                if (!acceptsCallback(gatt)) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            pairing.linkFailed("BLE connect failed: status=$status")
                            return@post
                        }
                        this@BleMeshTransport.gatt = gatt
                        ready = false
                        negotiatedMtu = DEFAULT_ATT_MTU
                        post(this@BleMeshTransport.status.connecting("GATT connected; negotiating MTU"))
                        opQueue.clear()
                        // Android 10 reports the link before its Bluetooth service has registered it, and silently drops
                        // a request made in that gap ("configureMTU() - No connection"); so wait a moment first.
                        workHandler.postDelayed({
                            if (acceptsCallback(gatt) && this@BleMeshTransport.gatt === gatt && !ready) {
                                enqueueMtuRequest(gatt)
                                enqueueServiceDiscovery(gatt)
                            }
                        }, FIRST_REQUEST_DELAY_MS)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val detail = if (status == BluetoothGatt.GATT_SUCCESS) {
                            "BLE radio disconnected"
                        } else {
                            "BLE radio disconnected: status=$status"
                        }
                        pairing.linkFailed(detail)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            workHandler.post {
                if (!acceptsCallback(gatt)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
                opQueue.complete("requestMtu")
                post(this@BleMeshTransport.status.diagnostic("BLE MTU ${if (status == BluetoothGatt.GATT_SUCCESS) "negotiated" else "request failed"}: $negotiatedMtu"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            workHandler.post {
                if (!acceptsCallback(gatt)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pairing.linkFailed("GATT service discovery failed: status=$status")
                    return@post
                }
                opQueue.complete("discoverServices")
                setupMeshtasticService(gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            workHandler.post {
                if (!acceptsCallback(gatt)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pairing.linkFailed("GATT descriptor failed: status=$status; check radio pairing")
                    return@post
                }
                opQueue.complete("writeDescriptor:${descriptor.uuid}")
                if (descriptor.uuid == cccdUuid && descriptor.characteristic?.uuid == fromNumUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        post(this@BleMeshTransport.status.diagnostic("FROMNUM notifications enabled; requesting radio config"))
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
                if (!acceptsCallback(gatt)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pairing.linkFailed("GATT write failed: status=$status; check radio pairing")
                    return@post
                }
                opQueue.complete("writeCharacteristic:${characteristic.uuid}")
                if (characteristic.uuid == toRadioUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        drainFromRadio("after toRadio write")
                    } else {
                        post(this@BleMeshTransport.status.diagnostic("toRadio write failed: status=$status"))
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
            onCharacteristicReadValue(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onCharacteristicReadValue(gatt, characteristic, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChangedValue(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onCharacteristicChangedValue(gatt, characteristic, value)
        }
    }

    override fun scan() {
        checkOpen()
        val missing = missingScanPermission()
        if (missing != null) {
            post(status.diagnostic("Missing permission for BLE scan: $missing"))
            postScanStopped()
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            workHandler.post { disconnectAndCleanup("Bluetooth is disabled or unavailable") }
            postScanStopped()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            post(status.diagnostic("BLE scanner is unavailable"))
            postScanStopped()
            return
        }
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            post(status.diagnostic("Scanning for Meshtastic BLE radios"))
        } catch (security: SecurityException) {
            post(status.diagnostic("BLE scan denied: ${security.message}"))
            postScanStopped()
        } catch (runtime: RuntimeException) {
            post(status.diagnostic("BLE scan failed: ${runtime.message}"))
            postScanStopped()
        }
    }

    override fun stopScan() {
        if (!scanning) return
        try {
            val scanner = adapter?.bluetoothLeScanner
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission may have been revoked while scanning. State below is still correct for this object.
        } finally {
            scanning = false
            // Stopping a scan is not a connection event: readiness is preserved on purpose.
            post(status.scanStopped())
            postScanStopped()
        }
    }

    override fun connect(address: String) {
        checkOpen()
        val normalized = address.trim()
        require(normalized.isNotEmpty()) { "address must not be blank" }
        val missing = missingConnectPermission()
        if (missing != null) {
            workHandler.post { disconnectAndCleanup("Missing permission for BLE connect: $missing"); postConnectFailed() }
            return
        }
        stopScan()
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            workHandler.post { disconnectAndCleanup("Bluetooth is disabled or unavailable"); postConnectFailed() }
            return
        }
        workHandler.post {
            disconnectAndCleanup("Starting new BLE connection", announceFailure = false)
            try {
                target = adapter.getRemoteDevice(normalized)
                post(this@BleMeshTransport.status.connecting("Connecting to Meshtastic BLE radio $normalized"))
                watchBond()
                setupRetry.begin()
                pairing.begin()
            } catch (security: SecurityException) {
                disconnectAndCleanup("BLE connect denied: ${security.message}")
            } catch (bad: IllegalArgumentException) {
                disconnectAndCleanup("Invalid BLE device address: $normalized")
            } catch (runtime: RuntimeException) {
                disconnectAndCleanup("BLE connect failed: ${runtime.message}")
            }
        }
    }

    /** Opens the GATT link to [target]. After pairing that began inside a connection, that connection is replaced. */
    private fun openGatt() {
        val device = target ?: return
        if (gatt != null) disconnectAndCleanup("Reconnecting after pairing", announceFailure = false, keepAttempt = true)
        try {
            @Suppress("MissingPermission")
            gatt = if (Build.VERSION.SDK_INT >= 26) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
            } else {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            session.replace(gatt)
            val opened = gatt
            if (opened == null) {
                disconnectAndCleanup("Android returned null BluetoothGatt for ${device.address}")
                return
            }
            val deadline = Runnable {
                if (acceptsCallback(opened) && !ready) pairing.linkFailed("Radio connection/config timed out; disconnect the other app and retry")
            }
            connectionDeadline = deadline
            workHandler.postDelayed(deadline, 30_000L)
        } catch (security: SecurityException) {
            disconnectAndCleanup("BLE connect denied: ${security.message}")
        } catch (runtime: RuntimeException) {
            disconnectAndCleanup("BLE connect failed: ${runtime.message}")
        }
    }

    /** Bond reports come from Android's Bluetooth app, which is not the system user: the receiver must be exported. */
    private fun watchBond() {
        if (watchingBond) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(bondReceiver, filter, null, workHandler, Context.RECEIVER_EXPORTED)
        else appContext.registerReceiver(bondReceiver, filter, null, workHandler)
        watchingBond = true
    }

    private fun unwatchBond() {
        if (!watchingBond) return
        watchingBond = false
        try {
            appContext.unregisterReceiver(bondReceiver)
        } catch (_: RuntimeException) {
            // Already gone with its context.
        }
    }

    override fun peers(): List<RadioPeer> = synchronized(peersById) { peersById.values.toList() }

    override fun ownName(): String? = ownName

    override fun channelName(): String? = channelName

    override fun disconnect() {
        checkOpen()
        stopScan()
        workHandler.post { disconnectAndCleanup("Disconnected by user", announceFailure = false) }
    }

    /**
     * Submits one text packet and returns its unsigned mesh packet id, the correlation key for
     * [Listener.onDelivery]. Never resends and never infers acknowledgement from frontend behaviour.
     * Callers that ignore the return value remain source compatible.
     */
    override fun send(text: String, destinationId: String, channel: Int, replyTo: Long, reaction: Boolean): Long =
        submit(destinationId, "text channel=$channel destination=$destinationId") { packetId, hops ->
            MeshtasticProtocol.buildTextToRadio(
                text,
                destinationId,
                channel,
                packetId = packetId,
                hopLimit = hops,
                replyTo = replyTo,
                reaction = reaction,
            )
        }

    /** Like [send], for one private-port packet (pictures). */
    override fun sendData(port: Int, payload: ByteArray, destinationId: String, channel: Int): Long =
        submit(destinationId, "data port=$port channel=$channel destination=$destinationId") { packetId, hops ->
            MeshtasticProtocol.buildDataToRadio(port, payload, destinationId, channel, packetId = packetId, hopLimit = hops)
        }

    override fun sendBroadcastData(port: Int, payload: ByteArray, channel: Int): Long =
        submit("^all", "broadcast data port=$port channel=$channel") { packetId, hops ->
            MeshtasticProtocol.buildBroadcastDataToRadio(port, payload, channel, packetId = packetId, hopLimit = hops)
        }

    override fun modemPreset(): String? = modemPreset

    private fun submit(destinationId: String, label: String, build: (packetId: Int, hops: Int) -> ByteArray): Long {
        checkOpen()
        val target = gatt
        check(ready && target != null && toRadio != null) { "Meshtastic radio is not ready" }
        val hops = checkNotNull(configuredHopLimit) { "Radio LoRa configuration has not arrived" }
        val packetId = MeshtasticProtocol.newPacketId().toLong() and MeshtasticProtocol.BROADCAST_NUM
        val payload = build(packetId.toInt(), hops)
        val maxWrite = maxWriteBytes()
        require(payload.size <= maxWrite) {
            "Encoded ToRadio is ${payload.size} bytes but negotiated BLE write limit is $maxWrite bytes"
        }
        workHandler.post {
            if (!ready || gatt !== target || toRadio == null || closed.get()) {
                postEmissions(delivery.rejectUnsubmitted(packetId, "radio was not ready when the write was attempted"))
                return@post
            }
            val destination = MeshtasticProtocol.parseDestinationId(destinationId)
            val local = localNodeNum?.toLong()?.and(MeshtasticProtocol.BROADCAST_NUM)
            if (local == null) {
                postEmissions(delivery.rejectUnsubmitted(packetId, "local node number is unknown, so no acknowledgement can be correlated"))
                return@post
            }
            if (!delivery.track(packetId, destination, local)) {
                postEmissions(delivery.rejectUnsubmitted(packetId, "pending-send limit reached or packet id already tracked"))
                return@post
            }
            postEmissions(listOf(DeliveryTracker.Emission(packetId, DeliveryState.SUBMITTING, "Writing packet $packetId to the radio")))
            if (!writeToRadio(payload, label)) {
                postEmissions(delivery.onQueueRejected(packetId, "BLE GATT queue rejected the write"))
            } else {
                post(status.diagnostic("Submitted packet $packetId to the radio GATT queue; a recipient ACK has not arrived"))
            }
            scheduleExpiry()
        }
        return packetId
    }

    override fun region(): String? = regions.region()

    override fun setRegion(region: String, done: (RegionResult) -> Unit) {
        checkOpen()
        // Refused here, on the caller's thread, before anything is handed to the radio.
        RegionSession.writable(region)
        workHandler.post { regions.start(region, done) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopScan()
        workHandler.post {
            disconnectAndCleanup("BleMeshTransport closed", announceFailure = false)
            workThread.quitSafely()
        }
    }

    private fun enqueueMtuRequest(gatt: BluetoothGatt) {
        opQueue.enqueue("requestMtu", timeoutMs = MTU_TIMEOUT_MS) {
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
        // A new handshake: only the settings it delivers count from here on.
        configuredHopLimit = null
        modemPreset = null
        regions.reset()
        val packet = MeshtasticProtocol.buildWantConfig(nonce)
        if (packet.size > maxWriteBytes()) {
            disconnectAndCleanup("want_config_id packet exceeds negotiated BLE write limit")
            return
        }
        writeToRadio(packet, "want_config_id=$nonce")
        drainFromRadio("initial config")
    }

    private fun writeToRadio(payload: ByteArray, detail: String): Boolean {
        val gatt = gatt ?: return false.also { post(status.diagnostic("Cannot write toRadio: GATT disconnected")) }
        val characteristic = toRadio ?: return false.also { post(status.diagnostic("Cannot write toRadio: characteristic missing")) }
        if (payload.size > maxWriteBytes()) {
            post(status.diagnostic("Cannot write $detail: ${payload.size} bytes exceeds BLE write limit ${maxWriteBytes()}"))
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
            if (!accepted) post(status.diagnostic("BLE GATT operation queue full; dropped $detail"))
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
            post(status.diagnostic("Draining fromRadio ($reason)"))
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
            // Yield the worker, then continue: config dumps can exceed one batch.
            workHandler.postDelayed({
                if (!closed.get() && this@BleMeshTransport.gatt === gatt) drainFromRadio("continued bounded batch")
            }, 20L)
            return
        }
        val accepted = opQueue.enqueue("readCharacteristic:$fromRadioUuid") {
            @Suppress("MissingPermission")
            gatt.readCharacteristic(characteristic)
        }
        if (!accepted) {
            drainInProgress = false
            post(status.diagnostic("BLE GATT operation queue full while draining fromRadio"))
        }
    }

    private fun onCharacteristicReadValue(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        workHandler.post {
            if (!acceptsCallback(callbackGatt)) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pairing.linkFailed("fromRadio read failed: status=$status; check radio pairing")
                return@post
            }
            opQueue.complete("readCharacteristic:${characteristic.uuid}")
            if (characteristic.uuid != fromRadioUuid) return@post
            if (value.isEmpty()) {
                drainInProgress = false
                if (drainAgain) drainFromRadio("coalesced trigger")
                return@post
            }
            handleFromRadio(value)
            enqueueFromRadioRead()
        }
    }

    private fun onCharacteristicChangedValue(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        workHandler.post {
            if (!acceptsCallback(callbackGatt)) return@post
            if (characteristic.uuid == fromNumUuid) {
                val count = MeshtasticProtocol.fromNumLittleEndian(value)
                post(status.diagnostic("FROMNUM notified${count?.let { " id=$it" } ?: ""}; draining fromRadio"))
                drainFromRadio("FROMNUM notification")
            }
        }
    }

    private fun handleFromRadio(bytes: ByteArray) {
        if (bytes.size > 4096) return
        val event = try {
            MeshtasticProtocol.parseFromRadio(bytes)
        } catch (ex: Exception) {
            post(status.diagnostic("Ignored invalid FromRadio protobuf (${bytes.size} bytes)"))
            return
        }
        expirePending()
        when (event) {
            is RadioEvent.ConfigComplete -> {
                if (event.id == wantedConfigId) {
                    if (configuredHopLimit == null || localNodeNum == null || localNodeNum == 0 || localNodeNum == -1) {
                        post(status.diagnostic("Radio did not supply a valid local node ID and hop limit; configuration incomplete"))
                        return
                    }
                    ready = true
                    // A link that became ready is never ended by a pairing wait still counting, nor tried again.
                    pairing.end()
                    setupRetry.end()
                    connectionDeadline?.let { workHandler.removeCallbacks(it) }
                    connectionDeadline = null
                    post(status.radioReady("Meshtastic BLE config complete id=${event.id}; radio ready"))
                } else {
                    post(status.diagnostic("Ignored stale config_complete_id=${event.id}; wanted=$wantedConfigId"))
                }
            }
            is RadioEvent.MyInfo -> {
                localNodeNum = event.nodeNum
                post(status.diagnostic("Local Meshtastic node ${MeshtasticProtocol.nodeId(event.nodeNum.toLong())}"))
            }
            is RadioEvent.LoraSettings -> {
                val hops = event.lora.hopLimit
                if (hops in 0..7) {
                    configuredHopLimit = hops
                    modemPreset = if (event.lora.usePreset) event.lora.modemPreset.name else null
                    regions.onSettings(event.lora)
                } else {
                    disconnectAndCleanup("Radio supplied an invalid hop limit")
                }
            }
            is RadioEvent.AdminLora -> regions.onReply(event)
            is RadioEvent.PrimaryChannel -> channelName = event.name
            is RadioEvent.NodeDiscovered -> {
                val peer = RadioPeer(event.id, event.name)
                ownName = MeshtasticProtocol.ownName(event, localNodeNum?.toLong()?.and(MeshtasticProtocol.BROADCAST_NUM), ownName)
                synchronized(peersById) {
                    if (event.id !in peersById && peersById.size >= 512) peersById.remove(peersById.keys.first())
                    peersById[event.id] = peer
                }
                postPeersChanged()
                post(status.diagnostic("Node discovered ${event.id} ${event.name}"))
            }
            is RadioEvent.Text -> {
                val local = localNodeNum?.toLong()?.and(MeshtasticProtocol.BROADCAST_NUM)
                val ownId = local?.let { MeshtasticProtocol.nodeId(it) }
                val direct = MeshtasticProtocol.textAudience(event.to, local)
                if (event.sender != ownId && direct != null) {
                    postText(event.sender, event.text, event.channel, direct, TextMark(event.packetId, event.replyTo, event.reaction))
                }
            }
            is RadioEvent.Data -> {
                val local = localNodeNum?.toLong()?.and(MeshtasticProtocol.BROADCAST_NUM)
                val ownId = local?.let { MeshtasticProtocol.nodeId(it) }
                if (local != null && event.to == local && event.sender != ownId) postData(event.sender, event.port, event.payload, event.channel, event.packetId)
                else if (event.to == MeshtasticProtocol.BROADCAST_NUM && event.port == MeshtasticProtocol.ACCOUNT_PORT && event.sender != ownId) {
                    val e = event
                    mainHandler.post { if (!closed.get()) listener.onBroadcastData(e.sender, e.port, e.payload, e.channel, e.packetId, e.signal) }
                }
            }
            is RadioEvent.QueueStatus -> {
                val ok = event.res == 0
                val packetId = event.meshPacketId.toLong() and MeshtasticProtocol.BROADCAST_NUM
                postEmissions(
                    if (ok) delivery.onQueued(packetId)
                    else delivery.onQueueRejected(packetId, "radio queue res=${event.res}"),
                )
                post(
                    status.diagnostic(
                        "Radio queue status res=${event.res} free=${event.free}/${event.maxlen} packet=${event.meshPacketId}; " +
                            if (ok) "radio accepted queueing, not a recipient ACK" else "radio queue rejected packet",
                    ),
                )
                expirePending()
            }
            is RadioEvent.Routing -> {
                val local = localNodeNum?.toLong()?.and(MeshtasticProtocol.BROADCAST_NUM)
                if (local == null) {
                    post(status.diagnostic("Ignored routing packet ${event.requestId}; local node number unknown"))
                } else {
                    postEmissions(
                        delivery.onRouting(
                            RoutingOutcome(from = event.from, to = event.to, requestId = event.requestId, error = event.error),
                            local,
                        ),
                    )
                    expirePending()
                }
            }
            null -> Unit
        }
    }

    private fun scheduleExpiry() {
        if (expiryTick != null) return
        val delay = delivery.nextExpiryDelay() ?: return
        val tick = Runnable {
            expiryTick = null
            expirePending()
        }
        expiryTick = tick
        workHandler.postDelayed(tick, delay)
    }

    private fun expirePending() {
        val expired = delivery.expire()
        postEmissions(expired)
        if (delivery.size > 0) scheduleExpiry()
    }

    /** Ends the link and, unless [keepAttempt], the attempt with it: the pairing wait and the watch on the radio's bond. */
    private fun disconnectAndCleanup(detail: String, announceFailure: Boolean = true, keepAttempt: Boolean = false) {
        if (!keepAttempt) {
            setupRetry.end()
            pairing.end()
            unwatchBond()
            target = null
        }
        val wasReady = ready
        ready = false
        session.replace(null)
        expiryTick?.let { workHandler.removeCallbacks(it) }
        expiryTick = null
        postEmissions(delivery.failPending(detail))
        connectionDeadline?.let { workHandler.removeCallbacks(it) }
        connectionDeadline = null
        regions.reset()
        localNodeNum = null
        ownName = null
        channelName = null
        configuredHopLimit = null
        modemPreset = null
        synchronized(peersById) { peersById.clear() }
        postPeersChanged()
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
        post(status.connectionLost(detail))
        if (announceFailure && !wasReady) postConnectFailed()
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

    private fun acceptsCallback(candidate: BluetoothGatt): Boolean = !closed.get() && session.accepts(candidate)
    private fun checkOpen() = check(!closed.get()) { "BleMeshTransport is closed" }

    private fun postConnectFailed() {
        mainHandler.post { if (!closed.get()) listener.onConnectFailed() }
    }

    private fun postScanStopped() {
        mainHandler.post { if (!closed.get()) listener.onScanStopped() }
    }

    private fun postState(connected: Boolean, detail: String) {
        mainHandler.post { if (!closed.get()) listener.onState(connected, detail) }
    }

    /** Emits a status; only connection-bearing outcomes may change the ready flag the UI displays. */
    private fun post(status: ConnectionStatusReporter.Status) = postState(status.ready, status.detail)

    private fun postEmissions(emissions: List<DeliveryTracker.Emission>) {
        if (emissions.isEmpty()) return
        mainHandler.post {
            if (closed.get()) return@post
            emissions.forEach { listener.onDelivery(it.packetId, it.state, it.detail) }
        }
    }

    private fun postText(sender: String, text: String, channel: Int, direct: Boolean, mark: TextMark) {
        mainHandler.post { if (!closed.get()) listener.onText(sender, text, channel, direct, mark) }
    }

    private fun postData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long) {
        mainHandler.post { if (!closed.get()) listener.onData(sender, port, payload, channel, packetId) }
    }

    private fun postDevice(address: String, name: String, rssi: Int) {
        mainHandler.post { if (!closed.get() && scanning) listener.onDevice(address, name, rssi) }
    }

    private val peersChangePosted = AtomicBoolean(false)

    /** A radio sends hundreds of node records while configuring; the app hears about them twice a second at most. */
    private fun postPeersChanged() {
        if (!peersChangePosted.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            peersChangePosted.set(false)
            if (!closed.get()) listener.onPeersChanged()
        }, PEERS_COALESCE_MS)
    }

    companion object {
        private const val TARGET_MTU = 512
        /** Wait after the link comes up before the first request (Android 10, see onConnectionStateChange). */
        private const val FIRST_REQUEST_DELAY_MS = 500L
        /** The MTU answer takes well under a second; a stalled one ends the try so the next can start. */
        private const val MTU_TIMEOUT_MS = 4_000L
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val MAX_DRAIN_READS = 64
        private const val PEERS_COALESCE_MS = 500L
    }
}
