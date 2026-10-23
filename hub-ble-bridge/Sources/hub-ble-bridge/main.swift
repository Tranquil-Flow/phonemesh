// The Mac hub's Bluetooth bridge, native edge (accounts decision 19). A CoreBluetooth GATT peripheral: one service (UUID derived
// from the hub pin exactly as HubGatt.serviceUuid does), one characteristic phones write frames to, one they are notified on.
// Each phone stream becomes one Network.framework TCP connection to 127.0.0.1:<--port> and the bytes go through untouched:
// the TLS handshake and everything after it is between the phone's HubClient and the hub's own HTTPS server. This process
// holds no key, no certificate and no pin check, and nothing a phone sends picks an address, a port or a file.
//
// One serial queue runs everything: CoreBluetooth delegate callbacks, Network.framework callbacks, the one-second timer and
// the signal handlers. BridgeCore (Bridge.swift) is only ever called from it.
//
// NOT RUN, NOT BUILT when staged: written without a compiler, a Mac or a radio.
import Foundation
import CoreBluetooth
import Network
import BridgeCore

func log(_ text: String) {
    FileHandle.standardError.write(Data(("hub-ble-bridge: " + text + "\n").utf8))
}

func die(_ text: String, _ code: Int32) -> Never {
    log(text)
    exit(code)
}

// MARK: loopback upstream

/// One TCP connection to the hub's HTTPS server on 127.0.0.1 (the interface is pinned to loopback). Opaque bytes only.
/// All methods and all callbacks run on [queue]. After `close()` nothing is ever delivered again.
final class LoopbackUpstream: Upstream {
    private let connection: NWConnection
    private weak var events: UpstreamEvents?
    private var closed = false

    init(port: NWEndpoint.Port, queue: DispatchQueue, events: UpstreamEvents) {
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        connection = NWConnection(host: .ipv4(.loopback), port: port, using: parameters)
        self.events = events
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self, !self.closed else { return }
            switch state {
            case .ready: self.events?.upstreamConnected()
            case .waiting, .failed: self.finish(error: true) // nothing listening, refused, or no route: do not wait
            default: break
            }
        }
        connection.start(queue: queue)
    }

    func read(max: Int) {
        guard !closed, max > 0 else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: max) { [weak self] data, _, complete, error in
            guard let self = self, !self.closed else { return }
            if let data = data, !data.isEmpty { self.events?.upstreamData(Array(data)) }
            if error != nil { self.finish(error: true) } else if complete { self.finish(error: false) }
        }
    }

    func write(_ bytes: [UInt8], done: @escaping (Bool) -> Void) {
        guard !closed else { done(false); return }
        connection.send(content: Data(bytes), completion: .contentProcessed { error in done(error == nil) })
    }

    func close() {
        guard !closed else { return }
        closed = true
        events = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
    }

    private func finish(error: Bool) {
        guard !closed else { return }
        let target = events
        close()
        target?.upstreamClosed(error: error)
    }
}

// MARK: the peripheral

final class Hub: NSObject, CBPeripheralManagerDelegate, BridgeHost {
    private let queue = DispatchQueue(label: "org.phonemesh.hub-ble-bridge")
    private let port: NWEndpoint.Port
    private let serviceUUID: CBUUID
    private let rxUUID = CBUUID(string: Gatt.rxUUID)
    private let txUUID = CBUUID(string: Gatt.txUUID)
    private var manager: CBPeripheralManager?
    private var bridge: Bridge?
    private var tx: CBMutableCharacteristic?
    private var subscribed: [String: CBCentral] = [:]
    private var published = false
    private var lastNotice = ""
    private var timer: DispatchSourceTimer?
    private var signals: [DispatchSourceSignal] = []

    init(options: Options, port: NWEndpoint.Port) {
        self.port = port
        self.serviceUUID = CBUUID(string: Gatt.serviceUUID(pin: options.pin))
        super.init()
    }

    func start() {
        queue.async {
            self.bridge = Bridge(host: self)
            // Creating the manager is what makes macOS ask for Bluetooth permission the first time. Nothing here answers it.
            self.manager = CBPeripheralManager(delegate: self, queue: self.queue, options: nil)
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 1, repeating: 1, leeway: .milliseconds(100))
            timer.setEventHandler { [weak self] in self?.bridge?.tick() }
            timer.resume()
            self.timer = timer
            for sig in [SIGINT, SIGTERM] {
                signal(sig, SIG_IGN)
                let source = DispatchSource.makeSignalSource(signal: sig, queue: self.queue)
                source.setEventHandler { [weak self] in self?.shutdown() }
                source.resume()
                self.signals.append(source)
            }
        }
    }

    private func shutdown() {
        log("stopping")
        manager?.stopAdvertising()
        for id in Array(subscribed.keys) { bridge?.centralGone(id) } // closes every loopback connection
        subscribed = [:]
        exit(0)
    }

    // MARK: state

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            publish(peripheral)
        case .poweredOff:
            suspend(peripheral, "Bluetooth is turned off on this Mac; not advertising. Turn it on and this program continues by itself.")
        case .resetting:
            suspend(peripheral, "Bluetooth is resetting; not advertising until it is back.")
        case .unauthorized:
            die("Bluetooth access was not granted to this program (\(describe(CBManager.authorization))). Allow it in System Settings > Privacy & Security > Bluetooth and start it again.", 77)
        case .unsupported:
            die("This Mac cannot act as a Bluetooth LE peripheral.", 69)
        case .unknown:
            break // the real state follows
        @unknown default:
            suspend(peripheral, "Bluetooth is in a state this program does not know; not advertising.")
        }
    }

    private func describe(_ authorization: CBManagerAuthorization) -> String {
        switch authorization {
        case .denied: return "denied"
        case .restricted: return "restricted by a profile or parental control"
        case .allowedAlways: return "allowed"
        case .notDetermined: return "not decided"
        @unknown default: return "unknown"
        }
    }

    /// Bluetooth went away: every phone is gone, nothing is advertised, and the service is built again on the next power-on.
    private func suspend(_ peripheral: CBPeripheralManager, _ message: String) {
        if message != lastNotice { log(message) } // said once per kind of outage, also when Bluetooth was never on
        lastNotice = message
        published = false
        tx = nil
        for id in Array(subscribed.keys) { bridge?.centralGone(id) }
        subscribed = [:]
    }

    private func publish(_ peripheral: CBPeripheralManager) {
        guard !published else { return }
        published = true
        lastNotice = ""
        peripheral.removeAllServices()
        let rx = CBMutableCharacteristic(type: rxUUID, properties: [.write], value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: txUUID, properties: [.notify], value: nil, permissions: [.readable])
        self.tx = tx
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [rx, tx]
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error { die("Bluetooth refused the hub service: \(error.localizedDescription)", 70) }
        // The one service UUID only: no local name, no pin, no address of this hub beyond what the radio itself shows.
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error { die("Bluetooth would not advertise: \(error.localizedDescription)", 70) }
        log("advertising service \(serviceUUID.uuidString.lowercased()); streams go to 127.0.0.1:\(port.rawValue)")
    }

    // MARK: phones

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == txUUID else { return }
        let id = central.identifier.uuidString
        subscribed[id] = central
        bridge?.centralConnected(id)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == txUUID else { return }
        let id = central.identifier.uuidString
        subscribed[id] = nil
        bridge?.centralGone(id)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        peripheral.respond(to: request, withResult: .readNotPermitted)
    }

    /// Each write is exactly one frame. A write from a phone that has not subscribed to the notifications is refused: it could
    /// not be answered. Junk that decodes to nothing is dropped by BridgeCore (a peripheral cannot disconnect a central).
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        var result = CBATTError.Code.success
        for request in requests {
            let id = request.central.identifier.uuidString
            guard request.characteristic.uuid == rxUUID else { result = .attributeNotFound; continue }
            guard request.offset == 0 else { result = .invalidOffset; continue }
            guard let value = request.value, value.count <= Gatt.maxFrame else { result = .invalidAttributeValueLength; continue }
            guard subscribed[id] != nil else { result = .writeNotPermitted; continue }
            bridge?.received(central: id, bytes: Array(value))
        }
        peripheral.respond(to: first, withResult: result)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for id in Array(subscribed.keys) { bridge?.ready(central: id) }
    }

    // MARK: BridgeHost

    func connectUpstream(events: UpstreamEvents) -> Upstream? {
        LoopbackUpstream(port: port, queue: queue, events: events)
    }

    func notify(central: String, frame: [UInt8]) -> Bool {
        // A phone that already left has no state in the bridge either (centralGone ran first): consuming the frame is right.
        guard let manager = manager, let tx = tx, let target = subscribed[central] else { return true }
        return manager.updateValue(Data(frame), for: tx, onSubscribedCentrals: [target])
    }

    func maxFrame(central: String) -> Int {
        subscribed[central]?.maximumUpdateValueLength ?? Gatt.minFrame
    }
}

// MARK: start

let options: Options = {
    switch Options.parse(Array(CommandLine.arguments.dropFirst())) {
    case .success(let options):
        return options
    case .failure(.help):
        print(Options.usage)
        exit(0)
    case .failure(let failure):
        FileHandle.standardError.write(Data(("hub-ble-bridge: " + Options.message(for: failure) + "\n" + Options.usage + "\n").utf8))
        exit(64)
    }
}()

guard let upstreamPort = NWEndpoint.Port(rawValue: options.port) else { die("--port is not a usable port", 64) }

switch CBManager.authorization {
case .denied:
    die("Bluetooth access is denied for this program. Allow it in System Settings > Privacy & Security > Bluetooth, then start it again.", 77)
case .restricted:
    die("Bluetooth is restricted on this Mac (a profile or parental control), so this program cannot use it.", 77)
default:
    break // allowed, or not decided yet: macOS asks once when the manager is created, and the answer is the user's
}

let hub = Hub(options: options, port: upstreamPort)
hub.start()
dispatchMain()
