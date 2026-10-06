# Meshtastic GPL provenance

This module vendors/adapts the legacy Meshtastic Android AIDL service interface and the minimum Parcelable model surface required to compile a client.

Source repository: https://github.com/meshtastic/Meshtastic-Android
Pinned commit: `d4879ceea9322bcbc1568ef27e461d34c1795887`
License in source repository: GPL-3.0-or-later / GPL-3.0 (repository `LICENSE`).

Vendored/adapted source paths:

- `app/src/main/aidl/com/geeksville/mesh/IMeshService.aidl`
- `app/src/main/java/com/geeksville/mesh/DataPacket.kt`
- `app/src/main/java/com/geeksville/mesh/NodeInfo.kt`
- `app/src/main/java/com/geeksville/mesh/MyNodeInfo.kt`
- `app/src/main/java/com/geeksville/mesh/service/Constants.kt`
- `app/src/main/java/com/geeksville/mesh/service/MeshServiceBroadcasts.kt`

Current-source compatibility note: official `main` commit `21031efe4244e575dff1b879ed555982d94da54d` no longer exports an AIDL service, so this bridge is a fail-closed legacy compatibility bridge, not proof that current Meshtastic Android supports third-party AIDL sends.
