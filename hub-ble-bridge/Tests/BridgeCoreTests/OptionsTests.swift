import XCTest
import BridgeCore

final class OptionsTests: XCTestCase {
    private let pin = String(repeating: "A", count: 43)

    private func parse(_ args: [String]) -> Result<Options, Options.Failure> { Options.parse(args) }

    func testAcceptsBothSpellingsInAnyOrder() {
        XCTAssertEqual(parse(["--pin", pin, "--port", "8443"]), .success(Options(pin: pin, port: 8443)))
        XCTAssertEqual(parse(["--port=8443", "--pin=\(pin)"]), .success(Options(pin: pin, port: 8443)))
        XCTAssertEqual(parse(["--port", "1", "--pin=\(pin)"]), .success(Options(pin: pin, port: 1)))
        XCTAssertEqual(parse(["--port", "65535", "--pin", pin]), .success(Options(pin: pin, port: 65535)))
    }

    func testPinMayStartWithDash() {
        let dashed = "-" + String(repeating: "b", count: 42)
        XCTAssertEqual(parse(["--pin", dashed, "--port", "9"]), .success(Options(pin: dashed, port: 9)))
    }

    func testBothAreRequiredWithNoDefault() {
        XCTAssertEqual(parse([]), .failure(.missing("--pin")))
        XCTAssertEqual(parse(["--pin", pin]), .failure(.missing("--port")))
        XCTAssertEqual(parse(["--port", "8443"]), .failure(.missing("--pin")))
    }

    func testRejectsBadValues() {
        XCTAssertEqual(parse(["--pin", "short", "--port", "8443"]), .failure(.badPin))
        XCTAssertEqual(parse(["--pin", pin + "A", "--port", "8443"]), .failure(.badPin))
        for port in ["0", "65536", "-1", "+80", "80a", "", " 80", "0x50", "123456", "99999999999999999999"] {
            XCTAssertEqual(parse(["--pin", pin, "--port", port]), .failure(.badPort), port)
        }
    }

    func testRejectsDuplicatesUnknownAndMissingValues() {
        XCTAssertEqual(parse(["--pin", pin, "--pin", pin, "--port", "1"]), .failure(.duplicate("--pin")))
        XCTAssertEqual(parse(["--pin", pin, "--port", "1", "--port=2"]), .failure(.duplicate("--port")))
        XCTAssertEqual(parse(["--pin", pin, "--port", "1", "--host", "10.0.0.5"]), .failure(.unknown("--host")))
        XCTAssertEqual(parse([pin, "--port", "1"]), .failure(.unknown(pin)))
        XCTAssertEqual(parse(["--pin"]), .failure(.missingValue("--pin")))
        XCTAssertEqual(parse(["--pin", pin, "--port"]), .failure(.missingValue("--port")))
    }

    func testHelp() {
        XCTAssertEqual(parse(["--help"]), .failure(.help))
        XCTAssertEqual(parse(["--pin", pin, "-h"]), .failure(.help))
    }

    func testMessagesNeverRepeatAPin() {
        for failure in [Options.Failure.unknown(pin), .badPin, .duplicate("--pin"), .missing("--pin"), .missingValue("--pin")] {
            XCTAssertFalse(Options.message(for: failure).contains(pin))
        }
    }
}
