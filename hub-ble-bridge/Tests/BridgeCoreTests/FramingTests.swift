import XCTest
import BridgeCore

final class FramingTests: XCTestCase {
    func testKnownEncodings() {
        // Worked out from the layout (type, stream, seq, payload), not copied from any generator.
        XCTAssertEqual(Frame.credit(5, 0x1234).encode(), [4, 5, 0, 0x12, 0x34])
        XCTAssertEqual(Frame.opened(7).encode(), [2, 7, 0])
        XCTAssertEqual(Frame.close(9).encode(), [5, 9, 0])
        XCTAssertEqual(Frame.reset(3, .busy).encode(), [6, 3, 0, 3])
        XCTAssertEqual(Frame.data(1, seq: 255, [0xAA, 0xBB]).encode(), [3, 1, 255, 0xAA, 0xBB])
        XCTAssertEqual(Wire.open(2), [1, 2, 0, 1])
    }

    func testRoundTrip() {
        for frame in [Frame.opened(1), .close(2), .reset(255, .flow), .credit(4, 1), .credit(4, Gatt.window),
                      .data(9, seq: 200, [1, 2, 3]), Frame(.open, stream: 1, payload: [Gatt.version])] {
            XCTAssertEqual(Frame.decode(frame.encode()), frame)
        }
        XCTAssertEqual(Frame.credit(1, Gatt.window).creditAmount, Gatt.window)
    }

    func testMalformedFramesAreRejectedWithoutTrapping() {
        let bad: [[UInt8]] = [
            [], [1], [1, 1],                                   // shorter than the header
            [0, 1, 0, 1], [7, 1, 0, 1], [255, 1, 0, 1],        // unknown kinds
            [1, 0, 0, 1],                                      // stream 0
            [1, 1, 0], [1, 1, 0, 2], [1, 1, 0, 1, 1],          // OPEN: wrong payload or version
            [1, 1, 1, 1],                                      // seq on a non-DATA frame
            [2, 1, 0, 0], [5, 1, 0, 0],                        // OPENED / CLOSE with a payload
            [3, 1, 0],                                         // empty DATA
            [4, 1, 0], [4, 1, 0, 0], [4, 1, 0, 0, 0], [4, 1, 0, 0x20, 0x01], [4, 1, 0, 1], [4, 1, 0, 0, 1, 0], // CREDIT: 0, over the window, wrong size
            [6, 1, 0], [6, 1, 0, 1, 2],                        // RESET without exactly one reason
            [UInt8](repeating: 3, count: Gatt.maxFrame + 1),   // longer than any frame
        ]
        for bytes in bad { XCTAssertNil(Frame.decode(bytes), "\(bytes)") }
        XCTAssertNotNil(Frame.decode([3, 1, 0] + [UInt8](repeating: 9, count: Gatt.maxFrame - Gatt.header)))
    }

    func testMaxPayloadClamps() {
        XCTAssertEqual(Gatt.maxPayload(frame: 0), Gatt.minFrame - Gatt.header)
        XCTAssertEqual(Gatt.maxPayload(frame: 20), 17)
        XCTAssertEqual(Gatt.maxPayload(frame: 100_000), Gatt.maxFrame - Gatt.header)
    }

    func testPinShape() {
        XCTAssertTrue(Gatt.isPin(String(repeating: "A", count: 43)))
        XCTAssertTrue(Gatt.isPin("-" + String(repeating: "z", count: 41) + "_"))
        XCTAssertFalse(Gatt.isPin(String(repeating: "A", count: 42)))
        XCTAssertFalse(Gatt.isPin(String(repeating: "A", count: 44)))
        XCTAssertFalse(Gatt.isPin(String(repeating: "A", count: 42) + "="))
        XCTAssertFalse(Gatt.isPin(String(repeating: "A", count: 42) + " "))
    }

    func testServiceUUIDShape() {
        let a = Gatt.serviceUUID(pin: String(repeating: "A", count: 43))
        let b = Gatt.serviceUUID(pin: String(repeating: "B", count: 43))
        XCTAssertEqual(a, Gatt.serviceUUID(pin: String(repeating: "A", count: 43)))
        XCTAssertNotEqual(a, b)
        for uuid in [a, b] {
            XCTAssertNotNil(uuid.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", options: .regularExpression), uuid)
        }
        XCTAssertNotEqual(a, Gatt.rxUUID)
        XCTAssertNotEqual(a, Gatt.txUUID)
    }

    /// The service UUID must match HubGatt.serviceUuid byte for byte. The expected values come from the Kotlin implementation:
    /// run android's GattVectorsExportTest, then set PM_GATT_VECTORS to core/build/gatt-vectors/service-uuids.txt (lines "pin uuid").
    /// Without that file this test is skipped, never satisfied by a copied constant.
    func testServiceUUIDMatchesKotlinVectors() throws {
        guard let path = ProcessInfo.processInfo.environment["PM_GATT_VECTORS"], !path.isEmpty else {
            throw XCTSkip("PM_GATT_VECTORS is not set; run the Kotlin GattVectorsExportTest first")
        }
        let text = try String(contentsOfFile: path, encoding: .utf8)
        var checked = 0
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: " ").map(String.init)
            XCTAssertEqual(parts.count, 2, "bad line: \(line)")
            guard parts.count == 2 else { continue }
            XCTAssertTrue(Gatt.isPin(parts[0]), parts[0])
            XCTAssertEqual(Gatt.serviceUUID(pin: parts[0]), parts[1], "pin \(parts[0])")
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0, "the vectors file was empty")
    }
}
