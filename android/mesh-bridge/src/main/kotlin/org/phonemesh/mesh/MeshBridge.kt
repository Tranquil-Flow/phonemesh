package org.phonemesh.mesh

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.RemoteException
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.IMeshService
import com.geeksville.mesh.MESHTASTIC_TEXT_MESSAGE_APP_PORT
import com.geeksville.mesh.NodeInfo
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Public Phonemesh view of a Meshtastic node. */
data class MeshPeer(val id: String, val name: String)

/**
 * A fail-closed bridge to the legacy exported Meshtastic Android AIDL service.
 *
 * This does not connect to BLE, configure radios, retry, or claim radio delivery. A successful
 * [send] means the Meshtastic app's AIDL service accepted the packet object for queueing/sending.
 */
class MeshBridge(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onState(connected: Boolean, detail: String)
        fun onText(sender: String, text: String, channel: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    private var service: IMeshService? = null

    private val deathRecipient = IBinder.DeathRecipient {
        service = null
        postState(false, "Meshtastic service binder died")
        unregisterReceivers()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed.get()) return
            val bound = IMeshService.Stub.asInterface(binder)
            service = bound
            runCatching { binder.linkToDeath(deathRecipient, 0) }
            val remoteState = runCatching { bound.connectionState() }.getOrNull()
            registerReceiversIfAuthenticBroadcastsSupported()
            val receiveDetail = if (canAuthenticateBroadcastSender()) {
                "receive enabled for authenticated Meshtastic broadcasts"
            } else {
                "receive disabled: Android < 14 cannot authenticate legacy Meshtastic broadcasts"
            }
            postState(true, "Bound to legacy Meshtastic AIDL service; state=${remoteState ?: "unknown"}; $receiveDetail")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            unregisterReceivers()
            postState(false, "Meshtastic service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            unregisterReceivers()
            postState(false, "Meshtastic service binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            unregisterReceivers()
            postState(false, "Meshtastic service returned a null binding")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed.get()) return
            if (!isAuthenticMeshtasticBroadcast()) {
                postState(true, "Ignored unauthenticated Meshtastic broadcast action=${intent.action.orEmpty()}")
                return
            }
            when (intent.action) {
                ACTION_RECEIVED_TEXT -> handleText(intent)
                ACTION_NODE_CHANGE -> postState(true, "Meshtastic node table changed")
                ACTION_MESH_CONNECTED -> {
                    val state = intent.getStringExtra(EXTRA_CONNECTED).orEmpty()
                    postState(true, "Meshtastic mesh connection state broadcast: $state")
                }
                ACTION_MESSAGE_STATUS -> {
                    val id = intent.getIntExtra(EXTRA_PACKET_ID, 0)
                    val status = intent.getParcelableCompat<Parcelable>(EXTRA_STATUS)
                    postState(true, "Meshtastic message status changed id=$id status=${status ?: "unknown"}")
                }
            }
        }
    }

    fun connect() {
        check(!closed.get()) { "MeshBridge is closed" }
        if (service != null) {
            postState(true, "Already bound to Meshtastic service")
            return
        }
        val intent = Intent(SERVICE_ACTION).apply {
            setClassName(SERVICE_PACKAGE, LEGACY_SERVICE_CLASS)
        }
        try {
            val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                postState(
                    false,
                    "Meshtastic legacy AIDL service not available; current official app exports no AIDL service",
                )
            }
        } catch (security: SecurityException) {
            postState(false, "Permission/export denied binding Meshtastic service: ${security.message}")
        } catch (ex: RuntimeException) {
            postState(false, "Failed to bind Meshtastic service: ${ex.message}")
        }
    }

    fun peers(): List<MeshPeer> {
        val bound = service ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val nodes = bound.nodes as? List<Any?> ?: return emptyList()
            nodes.mapNotNull { item ->
                val node = item as? NodeInfo ?: return@mapNotNull null
                val id = node.user?.id?.takeIf { it.isNotBlank() } ?: DataPacket.nodeNumToDefaultId(node.num)
                val name = node.user?.longName?.takeIf { it.isNotBlank() }
                    ?: node.user?.shortName?.takeIf { it.isNotBlank() }
                    ?: id
                MeshPeer(id, name)
            }
        } catch (remote: RemoteException) {
            postState(false, "Failed to read Meshtastic peers: ${remote.message}")
            emptyList()
        } catch (badParcelable: RuntimeException) {
            postState(false, "Failed to decode Meshtastic peers: ${badParcelable.message}")
            emptyList()
        }
    }

    fun send(text: String, destinationId: String, channel: Int) {
        val bound = service ?: throw IllegalStateException("Meshtastic service is not connected")
        require(destinationId.isNotBlank()) { "destinationId must not be blank" }
        val bytes = text.encodeToByteArray()
        require(bytes.isNotEmpty()) { "text must not be empty" }
        require(bytes.size <= MAX_TEXT_BYTES) { "text is ${bytes.size} UTF-8 bytes; max is $MAX_TEXT_BYTES" }
        require(channel >= 0) { "channel must be non-negative" }

        val packet = DataPacket(
            to = destinationId,
            bytes = bytes,
            dataType = MESHTASTIC_TEXT_MESSAGE_APP_PORT,
            channel = channel,
        )
        try {
            bound.send(packet)
            postState(
                true,
                "Submitted text to Meshtastic service id=${packet.id} status=${packet.status}; not a delivery receipt",
            )
        } catch (remote: RemoteException) {
            postState(false, "Meshtastic send failed: ${remote.message}")
            throw IllegalStateException("Meshtastic send failed", remote)
        } catch (runtime: RuntimeException) {
            postState(false, "Meshtastic send failed: ${runtime.message}")
            throw runtime
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterReceivers()
        service?.asBinder()?.let { binder -> runCatching { binder.unlinkToDeath(deathRecipient, 0) } }
        service = null
        runCatching { appContext.unbindService(connection) }
        postState(false, "MeshBridge closed")
    }

    private fun registerReceiversIfAuthenticBroadcastsSupported() {
        if (!canAuthenticateBroadcastSender()) return
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(ACTION_RECEIVED_TEXT)
            addAction(ACTION_NODE_CHANGE)
            addAction(ACTION_MESH_CONNECTED)
            addAction(ACTION_MESSAGE_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun BroadcastReceiver.isAuthenticMeshtasticBroadcast(): Boolean {
        if (!canAuthenticateBroadcastSender()) return false
        val senderPackage = getSentFromPackage()
        val senderUid = getSentFromUid()
        if (senderPackage != SERVICE_PACKAGE) return false
        val packagesForUid = appContext.packageManager.getPackagesForUid(senderUid)?.toSet().orEmpty()
        return SERVICE_PACKAGE in packagesForUid
    }

    private fun handleText(intent: Intent) {
        val packet = intent.getParcelableCompat<DataPacket>(EXTRA_PAYLOAD)
        if (packet == null) {
            postState(true, "Ignored text broadcast without DataPacket payload")
            return
        }
        if (packet.dataType != MESHTASTIC_TEXT_MESSAGE_APP_PORT) {
            postState(true, "Ignored non-text Meshtastic packet on text action: port=${packet.dataType}")
            return
        }
        val bytes = packet.bytes
        if (bytes == null || bytes.isEmpty()) {
            postState(true, "Ignored empty Meshtastic text packet")
            return
        }
        if (bytes.size > MAX_TEXT_BYTES) {
            postState(true, "Ignored oversized Meshtastic text packet (${bytes.size} bytes)")
            return
        }
        val text = try {
            strictUtf8(bytes)
        } catch (_: CharacterCodingException) {
            postState(true, "Ignored Meshtastic text packet with invalid UTF-8")
            return
        }
        val sender = packet.from?.takeIf { it.isNotBlank() } ?: DataPacket.ID_LOCAL
        mainHandler.post { listener.onText(sender, text, packet.channel) }
    }

    private fun postState(connected: Boolean, detail: String) {
        mainHandler.post { listener.onState(connected, detail) }
    }

    private inline fun <reified T : Parcelable> Intent.getParcelableCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
        }

    companion object {
        const val SERVICE_PACKAGE = "com.geeksville.mesh"
        const val SERVICE_ACTION = "com.geeksville.mesh.Service"
        const val LEGACY_SERVICE_CLASS = "com.geeksville.mesh.service.MeshService"

        const val ACTION_RECEIVED_TEXT = "com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP"
        const val ACTION_NODE_CHANGE = "com.geeksville.mesh.NODE_CHANGE"
        const val ACTION_MESH_CONNECTED = "com.geeksville.mesh.MESH_CONNECTED"
        const val ACTION_MESSAGE_STATUS = "com.geeksville.mesh.MESSAGE_STATUS"

        const val EXTRA_PAYLOAD = "com.geeksville.mesh.Payload"
        const val EXTRA_NODEINFO = "com.geeksville.mesh.NodeInfo"
        const val EXTRA_CONNECTED = "com.geeksville.mesh.Connected"
        const val EXTRA_PACKET_ID = "com.geeksville.mesh.PacketId"
        const val EXTRA_STATUS = "com.geeksville.mesh.Status"

        const val MAX_TEXT_BYTES = 200

        fun canAuthenticateBroadcastSender(): Boolean = Build.VERSION.SDK_INT >= 34

        fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
