import Foundation
import CryptoKit

/// The Bluetooth hub transport's fixed facts. Mirror of `HubGatt` / `GattFrame` in
/// android/core/.../hub/HubGatt.kt: change one and the other, and the vectors in both test suites.
///
///     byte 0 type | byte 1 stream (1...255) | byte 2 seq (DATA only, else 0) | bytes 3... payload
public enum Gatt {
    public static let rxUUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b001" // phone writes here
    public static let txUUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b002" // hub notifies here

    public static let version: UInt8 = 1
    public static let header = 3
    public static let minFrame = 8
    public static let maxFrame = 512
    public static let window = 8192
    public static let maxStreams = 2

    public enum Kind: UInt8 { case open = 1, opened = 2, data = 3, credit = 4, close = 5, reset = 6 }
    public enum Reason: UInt8 { case protocolError = 1, unavailable = 2, busy = 3, timeout = 4, closed = 5, flow = 6 }

    /// SHA-256("phonemesh-hub-gatt-1:" + pin), first 16 bytes, version-5 and variant bits set, lower-case with dashes.
    /// The pin is public (it is in the hub card); this only tells a phone which hub it is looking at. It is not authentication.
    public static func serviceUUID(pin: String) -> String {
        let digest = SHA256.hash(data: Array(("phonemesh-hub-gatt-1:" + pin).utf8))
        var b = Array(digest.prefix(16))
        b[6] = (b[6] & 0x0F) | 0x50
        b[8] = (b[8] & 0x3F) | 0x80
        let hex = b.map { String(format: "%02x", $0) }.joined()
        let h = Array(hex)
        func part(_ a: Int, _ z: Int) -> String { String(h[a..<z]) }
        return [part(0, 8), part(8, 12), part(12, 16), part(16, 20), part(20, 32)].joined(separator: "-")
    }

    /// A pin as the hub card prints it: SHA-256 of the key, base64url, 43 characters, no padding.
    public static func isPin(_ text: String) -> Bool {
        text.utf8.count == 43 && text.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) || $0 == 45 || $0 == 95 }
    }

    /// Most DATA bytes one frame carries when a link takes [frame] bytes per notification.
    public static func maxPayload(frame: Int) -> Int { min(max(frame, minFrame), maxFrame) - header }
}

public struct Frame: Equatable {
    public let kind: Gatt.Kind
    public let stream: UInt8
    public let seq: UInt8
    public let payload: [UInt8]

    public init(_ kind: Gatt.Kind, stream: UInt8, seq: UInt8 = 0, payload: [UInt8] = []) {
        self.kind = kind; self.stream = stream; self.seq = seq; self.payload = payload
    }

    public static func opened(_ s: UInt8) -> Frame { Frame(.opened, stream: s) }
    public static func data(_ s: UInt8, seq: UInt8, _ bytes: [UInt8]) -> Frame { Frame(.data, stream: s, seq: seq, payload: bytes) }
    public static func credit(_ s: UInt8, _ n: Int) -> Frame { Frame(.credit, stream: s, payload: [UInt8(n >> 8), UInt8(n & 0xFF)]) }
    public static func close(_ s: UInt8) -> Frame { Frame(.close, stream: s) }
    public static func reset(_ s: UInt8, _ reason: Gatt.Reason) -> Frame { Frame(.reset, stream: s, payload: [reason.rawValue]) }

    public func encode() -> [UInt8] { [kind.rawValue, stream, seq] + payload }

    /// The CREDIT amount (only meaningful for a credit frame).
    public var creditAmount: Int { payload.count == 2 ? (Int(payload[0]) << 8) | Int(payload[1]) : 0 }

    /// The frame in [bytes], or nil unless it is exactly one well-formed frame of a known kind. Never traps.
    public static func decode(_ bytes: [UInt8]) -> Frame? {
        guard bytes.count >= Gatt.header, bytes.count <= Gatt.maxFrame else { return nil }
        guard let kind = Gatt.Kind(rawValue: bytes[0]) else { return nil }
        let stream = bytes[1], seq = bytes[2]
        let payload = Array(bytes[Gatt.header...])
        guard stream != 0 else { return nil }
        if kind != .data && seq != 0 { return nil }
        let frame = Frame(kind, stream: stream, seq: seq, payload: payload)
        switch kind {
        case .open: return payload.count == 1 && payload[0] == Gatt.version ? frame : nil
        case .opened, .close: return payload.isEmpty ? frame : nil
        case .data: return payload.isEmpty ? nil : frame
        case .credit: return payload.count == 2 && (1...Gatt.window).contains(frame.creditAmount) ? frame : nil
        case .reset: return payload.count == 1 ? frame : nil
        }
    }
}
