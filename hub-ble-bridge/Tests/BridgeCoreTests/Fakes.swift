import Foundation
import BridgeCore

/// A loopback connection that does nothing by itself: the test plays the hub's HTTPS server through `events`.
final class FakeUpstream: Upstream {
    weak var events: UpstreamEvents?
    var reads: [Int] = []
    var writes: [[UInt8]] = []
    var writeDone: [(Bool) -> Void] = []
    var closed = false

    func read(max: Int) { reads.append(max) }
    func write(_ bytes: [UInt8], done: @escaping (Bool) -> Void) { writes.append(bytes); writeDone.append(done) }
    func close() { closed = true }
}

/// The radio and the loopback port, scripted. `blocked` makes every notification refuse, like a full CoreBluetooth queue.
final class FakeHost: BridgeHost {
    var upstreams: [FakeUpstream] = []
    var acceptUpstream = true
    var blocked = false
    var mtu = 20
    var sent: [String: [[UInt8]]] = [:]

    func connectUpstream(events: UpstreamEvents) -> Upstream? {
        guard acceptUpstream else { return nil }
        let up = FakeUpstream()
        up.events = events
        upstreams.append(up)
        return up
    }

    func notify(central: String, frame: [UInt8]) -> Bool {
        if blocked { return false }
        sent[central, default: []].append(frame)
        return true
    }

    func maxFrame(central: String) -> Int { mtu }

    func frames(_ central: String) -> [Frame] { (sent[central] ?? []).compactMap { Frame.decode($0) } }
}

enum Wire {
    static func open(_ s: UInt8) -> [UInt8] { Frame(.open, stream: s, payload: [Gatt.version]).encode() }
    static func data(_ s: UInt8, seq: UInt8, _ bytes: [UInt8]) -> [UInt8] { Frame.data(s, seq: seq, bytes).encode() }
    static func credit(_ s: UInt8, _ n: Int) -> [UInt8] { Frame.credit(s, n).encode() }
    static func close(_ s: UInt8) -> [UInt8] { Frame.close(s).encode() }
}

final class Clock {
    var t: TimeInterval = 1_000
}
