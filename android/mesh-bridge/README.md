# Phonemesh Android Meshtastic mesh bridge

`android/mesh-bridge` is an isolated Android library module with namespace `org.phonemesh.mesh`, `compileSdk = 35`, and `minSdk = 26`.

## Public seam

```kotlin
data class MeshPeer(val id: String, val name: String)

class MeshBridge(context: Context, listener: MeshBridge.Listener) {
    interface Listener {
        fun onState(connected: Boolean, detail: String)
        fun onText(sender: String, text: String, channel: Int)
    }

    fun connect()
    fun peers(): List<MeshPeer>
    fun send(text: String, destinationId: String, channel: Int)
    fun close()
}
```

The parent should call `send()` and `peers()` from a background executor. Listener callbacks are always posted to the Android main thread.

## Source-verified Meshtastic API status

Current official Meshtastic Android source checked on 2026-10-01:

- `meshtastic/Meshtastic-Android` `main` commit `21031efe4244e575dff1b879ed555982d94da54d`.
- `config.properties` still sets `APPLICATION_ID=com.geeksville.mesh`.
- Current `androidApp/src/main/AndroidManifest.xml` declares `org.meshtastic.core.service.MeshService` as `android:exported="false"`.
- No verified official `core/api` reusable AIDL module exists on current `main` (`core/api/README.md` returned 404).

This means current official Meshtastic Android does **not** expose a safe third-party AIDL service. `MeshBridge.connect()` therefore fails closed against current app builds rather than pretending to send.

## Legacy AIDL pin used for compile-time compatibility

The AIDL and parcelable shapes are pinned to official Meshtastic source commit `d4879ceea9322bcbc1568ef27e461d34c1795887` (Android app versionName `2.1.16`):

- `app/src/main/aidl/com/geeksville/mesh/IMeshService.aidl`
- `app/src/main/java/com/geeksville/mesh/DataPacket.kt`
- `app/src/main/java/com/geeksville/mesh/NodeInfo.kt`
- `app/src/main/java/com/geeksville/mesh/MyNodeInfo.kt`
- `app/src/main/java/com/geeksville/mesh/service/Constants.kt`
- `app/src/main/java/com/geeksville/mesh/service/MeshServiceBroadcasts.kt`

That legacy manifest exported `com.geeksville.mesh.service.MeshService` with action `com.geeksville.mesh.Service`. This bridge binds only that explicit package/class/action.

## Parent manifest requirements

The library manifest contributes package visibility queries, but apps may also declare them explicitly:

```xml
<queries>
    <package android:name="com.geeksville.mesh" />
    <intent>
        <action android:name="com.geeksville.mesh.Service" />
    </intent>
</queries>
```

No BLE, location, microphone, or radio permissions are required by this bridge. The Meshtastic app owns device/radio permissions and any selected radio connection. Phonemesh must not call `setDeviceAddress()` or otherwise auto-connect BLE.

## Send semantics

`send(text, destinationId, channel)`:

- validates non-empty text, `<= 200` UTF-8 bytes, non-blank destination, and non-negative channel;
- creates a legacy `DataPacket` with port `TEXT_MESSAGE_APP = 1`;
- calls `IMeshService.send(inout DataPacket)`;
- reports the mutated packet id/status as **submitted to the Meshtastic app service only**.

Submitted/queued/enroute is not RF delivery, peer delivery, read status, or speech playback.

## Receive semantics and spoofing boundary

Legacy Meshtastic broadcasts are plain `sendBroadcast()` calls with no signature permission. The bridge only accepts:

- action `com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP`,
- `DataPacket.dataType == 1`,
- valid UTF-8 text payload of at most 200 bytes,
- sender package/uid authenticated as `com.geeksville.mesh` using Android 14+ `BroadcastReceiver.getSentFromPackage()` / `getSentFromUid()`.

On Android < 14, receive callbacks are disabled because dynamic receivers cannot authenticate the sender of those legacy broadcasts. This is intentional; accepting them would allow spoofed text events from another local app.

## License/provenance

The vendored AIDL/Parcelable compatibility surface is adapted from Meshtastic Android GPL-3.0 source. Keep this module's Meshtastic-derived files and provenance together if redistributed.

Full research notes: `.local/android-mesh-research.md`.
