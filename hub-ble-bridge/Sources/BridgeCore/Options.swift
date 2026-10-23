import Foundation

/// The bridge's whole configuration, from the command line only: the hub card's pin (it names the Bluetooth service) and the
/// loopback port of the hub's HTTPS server. Nothing a phone sends can change either, and there is no host option: the
/// upstream is always 127.0.0.1.
public struct Options: Equatable {
    public let pin: String
    public let port: UInt16

    public init(pin: String, port: UInt16) { self.pin = pin; self.port = port }

    public enum Failure: Error, Equatable {
        case help
        case unknown(String)
        case missingValue(String)
        case duplicate(String)
        case missing(String)
        case badPin
        case badPort
    }

    public static let usage = "usage: hub-ble-bridge --pin <43-character pin from the hub card> --port <hub HTTPS port on 127.0.0.1>"

    /// A message for the person at the terminal. It never repeats a pin.
    public static func message(for failure: Failure) -> String {
        switch failure {
        case .help: return usage
        case .unknown(let a): return "unexpected argument" + (a.hasPrefix("--") && a.utf8.count <= 32 ? " \(a)" : "")
        case .missingValue(let name): return "\(name) needs a value"
        case .duplicate(let name): return "\(name) was given twice"
        case .missing(let name): return "\(name) is required"
        case .badPin: return "--pin must be the 43 characters of the hub card's pin (letters, digits, - and _)"
        case .badPort: return "--port must be a number from 1 to 65535"
        }
    }

    /// [args] is the command line without the program name. `--pin X`, `--pin=X`, `--port N`, `--port=N`, in any order, each once;
    /// both are required (there is no default and no environment fallback). A pin may start with "-", so a value is never mistaken for an option.
    public static func parse(_ args: [String]) -> Result<Options, Failure> {
        var pin: String?
        var port: UInt16?
        var i = 0
        while i < args.count {
            let arg = args[i]
            i += 1
            if arg == "--help" || arg == "-h" { return .failure(.help) }
            var name = arg
            var value: String?
            if arg.hasPrefix("--"), let eq = arg.firstIndex(of: "=") {
                name = String(arg[..<eq])
                value = String(arg[arg.index(after: eq)...])
            }
            guard name == "--pin" || name == "--port" else { return .failure(.unknown(arg)) }
            if value == nil {
                guard i < args.count else { return .failure(.missingValue(name)) }
                value = args[i]
                i += 1
            }
            let text = value ?? ""
            if name == "--pin" {
                guard pin == nil else { return .failure(.duplicate(name)) }
                guard Gatt.isPin(text) else { return .failure(.badPin) }
                pin = text
            } else {
                guard port == nil else { return .failure(.duplicate(name)) }
                guard let p = parsePort(text) else { return .failure(.badPort) }
                port = p
            }
        }
        guard let pin = pin else { return .failure(.missing("--pin")) }
        guard let port = port else { return .failure(.missing("--port")) }
        return .success(Options(pin: pin, port: port))
    }

    /// Plain decimal digits only (no sign, no spaces, no 0x), 1...65535.
    static func parsePort(_ text: String) -> UInt16? {
        guard !text.isEmpty, text.utf8.count <= 5, text.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }), let n = UInt16(text), n >= 1 else { return nil }
        return n
    }
}
