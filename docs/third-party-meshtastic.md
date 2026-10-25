# Meshtastic protocol provenance

## Current integration

Phonemesh uses the [Meshtastic protobuf definitions](https://github.com/meshtastic/protobufs),
not the stock Android app's legacy AIDL service:

- Vendored definitions: `android/mesh-protocol/src/main/proto/meshtastic/`.
- Import-only nanopb options: `android/mesh-protocol/src/proto-include/nanopb.proto`.
- Shared generation/runtime: `android/mesh-protocol/build.gradle.kts`, using protoc
  **3.25.5** and **protobuf-javalite 3.25.5**, with Java lite output. The full Java
  protobuf dependency is compile-only for import resolution, not packaged.
- Android transport: `android/radio-ble/`, which depends on `mesh-protocol` and talks
  directly to the radio over BLE. The JVM hub also uses `mesh-protocol` for USB serial.

The AIDL commit cited by the earlier version of this document belonged to a historical
Android-service experiment. It is **not** the provenance of these vendored protobufs
and does not establish support by the current stock Meshtastic Android app.

## Licence and source boundary

The upstream protobuf repository provides the GPL-3.0 licence text retained in
[meshtastic-LICENSE](licenses/meshtastic-LICENSE). The protobuf runtime's separate
licence is retained in [protobuf-LICENSE](licenses/protobuf-LICENSE).
[Licence provenance](licenses/provenance.json) records pinned licence URLs and hashes.

All **28 Meshtastic files plus import-only nanopb.proto** match the complete upstream
`.proto` set at observed commit `8017acbb0828703e980323bee3dd605fb7366058`, with nanopb
relocated from the upstream repository root. Every file was independently downloaded
and compared byte-for-byte and by SHA-256/Git-blob SHA-1; the complete proto path set
also matches. The distinct upstream tree is `22b6b7edba6b482343624a8505fe92224f838940`.
The [per-file manifest](licenses/meshtastic-source-matches.json) records these facts.
This identifies an **observed matching revision**, not the original checkout, a unique
historical revision, or the identity of the entire upstream repository.

The nanopb options file is the **Meshtastic-modified variant**, including formatting
and `go_package`, not an unmodified canonical nanopb release. Its initial lineage is
consistent with both nanopb 0.4.6 and 0.4.7; no unique original version is claimed.
The applicable Petteri Aimonen copyright/permission notice is retained verbatim in
[nanopb-LICENSE.txt](licenses/nanopb-LICENSE.txt). The immutable source used to fetch
that identical notice does not resolve the version ambiguity.

Actual source-export inclusion and source-to-binary/corresponding-source verification
remain separate release gates. Do not substitute the old AIDL SHA or a licence-text
hash for those checks; include the vendored files, match manifest and notices in the
reviewed corresponding-source bundle.

See [dependency intake](dependencies.md) and the [owner publication checklist](publication.md).
