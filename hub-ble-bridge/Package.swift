// swift-tools-version:5.7
// The Mac hub's Bluetooth bridge (accounts decision 19): a CoreBluetooth GATT peripheral that forwards the opaque bytes
// of each phone's stream to the hub's own HTTPS server on 127.0.0.1. No third-party packages: Foundation, CryptoKit
// (SHA-256 of the pin, for the service UUID), CoreBluetooth and Network are all system frameworks.
//
//   swift build -c release            (from this directory)
//   swift test                        (BridgeCore tests need no Bluetooth; see Tests/ for PM_GATT_VECTORS)
//   .build/release/hub-ble-bridge --pin <43-character pin from the hub card> --port 8443
import PackageDescription

let package = Package(
    name: "hub-ble-bridge",
    platforms: [.macOS(.v12)],
    targets: [
        // Pure Swift: frames, flow control, stream table. Testable without Bluetooth or sockets.
        .target(name: "BridgeCore", path: "Sources/BridgeCore"),
        .executableTarget(
            name: "hub-ble-bridge",
            dependencies: ["BridgeCore"],
            path: "Sources/hub-ble-bridge",
            exclude: ["Info.plist"],
            // macOS 11+ stops a Bluetooth-using tool that carries no usage text; this embeds it in the binary.
            linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", "Sources/hub-ble-bridge/Info.plist"])]
        ),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"], path: "Tests/BridgeCoreTests"),
    ]
)
