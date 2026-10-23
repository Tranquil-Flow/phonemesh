import Foundation

/// What the bridge needs from the hub's side: one loopback TCP connection per stream. It only ever carries opaque bytes.
public protocol UpstreamEvents: AnyObject {
    func upstreamConnected()
    func upstreamData(_ bytes: [UInt8])
    func upstreamClosed(error: Bool)
}

public protocol Upstream: AnyObject {
    /// Asks for the next chunk, at most [max] bytes; at most one ask is outstanding. The answer comes as `upstreamData`.
    func read(max: Int)
    func write(_ bytes: [UInt8], done: @escaping (Bool) -> Void)
    func close()
}

public protocol BridgeHost: AnyObject {
    /// A new connection to the hub's HTTPS server: 127.0.0.1 and the one configured port, never anything a phone chose.
    func connectUpstream(events: UpstreamEvents) -> Upstream?
    /// Sends one notification to one phone. False: the radio's queue is full; `Bridge.ready(central:)` is called when it has room.
    func notify(central: String, frame: [UInt8]) -> Bool
    /// The largest notification this phone can take (CBCentral.maximumUpdateValueLength).
    func maxFrame(central: String) -> Int
}

/// The stream table and flow control of the Mac bridge, with no Bluetooth and no sockets in it (the host provides both).
/// Everything must be called on ONE serial queue. It never parses, terminates or logs what it carries: TLS runs between
/// the phone and the hub's JVM, and an authentication failure there is the phone's to see. Bounds: 4 phones, 2 streams
/// each, 8192 bytes of credit per stream and direction, 32 queued notifications per phone, 5 s to reach the loopback port,
/// 90 s per stream.
public final class Bridge {
    public static let maxCentrals = 4
    public static let maxOutbox = 32
    public static let connectSeconds = 5.0
    public static let laneSeconds = 90.0
    /// DATA frames are queued only below this; the last `Gatt.maxStreams` slots stay free so each stream can still be told RESET.
    public static let dataLimit = Bridge.maxOutbox - Gatt.maxStreams

    private enum State { case connecting, open, ended }

    private final class Lane: UpstreamEvents {
        let id: UInt8
        let central: String
        let started: TimeInterval
        var state = State.connecting
        var upstream: Upstream?
        var credit = Gatt.window      // DATA bytes the phone still lets us send
        var txSeq: UInt8 = 0
        var rxSeq: UInt8 = 0
        var owed = 0                  // bytes written upstream whose CREDIT the phone has not been given yet
        var pendingWrites = 0         // bytes accepted from the phone, not yet written upstream
        var reading = false
        weak var bridge: Bridge?
        weak var owner: Central?      // the phone incarnation that opened this lane: a replaced phone's callbacks never touch its successor

        init(id: UInt8, central: String, started: TimeInterval, bridge: Bridge, owner: Central) {
            self.id = id; self.central = central; self.started = started; self.bridge = bridge; self.owner = owner
        }

        func upstreamConnected() { bridge?.connected(self) }
        func upstreamData(_ bytes: [UInt8]) { bridge?.received(self, bytes) }
        func upstreamClosed(error: Bool) { bridge?.closedUpstream(self, error: error) }
    }

    private final class Central {
        let id: String
        var lanes: [UInt8: Lane] = [:]
        var outbox: [[UInt8]] = []
        var blocked = false
        var failed = false            // its queue overflowed: every stream ended, its writes are dropped until it is gone or the radio is ready
        var lost: [UInt8] = []        // streams ended by that overflow, told RESET once the radio is ready (at most maxStreams + 1)
        init(id: String) { self.id = id }
    }

    private weak var host: BridgeHost?
    private let now: () -> TimeInterval
    private var centrals: [String: Central] = [:]

    public init(host: BridgeHost, now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.host = host
        self.now = now
    }

    public var activeStreams: Int { centrals.values.reduce(0) { $0 + $1.lanes.count } }

    /// For tests and the host's log: notifications waiting for the radio, and whether the phone was cut off by an overflow.
    public func queuedNotifications(central id: String) -> Int { centrals[id]?.outbox.count ?? 0 }
    public func isQuarantined(central id: String) -> Bool { centrals[id]?.failed ?? false }

    // MARK: phones

    public func centralConnected(_ id: String) {
        if let old = centrals[id] { for lane in Array(old.lanes.values) { end(lane, tell: nil) } }
        if centrals[id] == nil && centrals.count >= Bridge.maxCentrals { return }
        centrals[id] = Central(id: id)
    }

    public func centralGone(_ id: String) {
        guard let c = centrals[id] else { return }
        for lane in Array(c.lanes.values) { end(lane, tell: nil) }
        centrals[id] = nil
    }

    /// One write from a phone: exactly one frame. Junk is dropped (CoreBluetooth gives a peripheral no way to disconnect a central).
    public func received(central id: String, bytes: [UInt8]) {
        guard let frame = Frame.decode(bytes) else { return }
        if centrals[id] == nil { centralConnected(id) }
        guard let c = centrals[id], !c.failed else { return } // a phone that overflowed its queue is heard again only after `ready`
        switch frame.kind {
        case .open: open(c, id, frame.stream)
        case .data: data(c, frame)
        case .credit:
            guard let lane = c.lanes[frame.stream] else { return }
            if lane.credit + frame.creditAmount > Gatt.window { end(lane, tell: .reset(lane.id, .flow)); return }
            lane.credit += frame.creditAmount
            pump(lane)
        case .close, .reset:
            if let lane = c.lanes[frame.stream] { end(lane, tell: nil) }
        case .opened: break // only the hub says this
        }
    }

    /// The radio can take notifications again.
    public func ready(central id: String) {
        guard let c = centrals[id] else { return }
        c.blocked = false
        if c.failed {
            c.failed = false
            let lost = c.lost
            c.lost = []
            for stream in lost { c.outbox.append(Frame.reset(stream, .flow).encode()) } // the queue is empty; lost has at most maxStreams + 1 entries
        }
        flush(c)
    }

    /// Call about once a second: ends streams that never connected or lived too long.
    public func tick() {
        let t = now()
        for c in centrals.values {
            for lane in Array(c.lanes.values) {
                if lane.state == .connecting && t - lane.started > Bridge.connectSeconds { end(lane, tell: .reset(lane.id, .unavailable)) }
                else if t - lane.started > Bridge.laneSeconds { end(lane, tell: .reset(lane.id, .timeout)) }
            }
        }
    }

    // MARK: streams

    private func open(_ c: Central, _ central: String, _ id: UInt8) {
        if c.lanes[id] != nil || c.lanes.count >= Gatt.maxStreams { queue(c, .reset(id, .busy)); return }
        let lane = Lane(id: id, central: central, started: now(), bridge: self, owner: c)
        c.lanes[id] = lane
        guard let up = host?.connectUpstream(events: lane) else {
            c.lanes[id] = nil
            queue(c, .reset(id, .unavailable))
            return
        }
        lane.upstream = up
    }

    private func connected(_ lane: Lane) {
        guard lane.state == .connecting, let c = owner(of: lane) else { return }
        lane.state = .open
        queue(c, .opened(lane.id))
        pump(lane)
    }

    private func data(_ c: Central, _ frame: Frame) {
        guard let lane = c.lanes[frame.stream] else { return } // a stream already closed: stale, ignored
        guard lane.state == .open, frame.seq == lane.rxSeq else { end(lane, tell: .reset(lane.id, .protocolError)); return }
        lane.rxSeq &+= 1
        let n = frame.payload.count
        guard lane.pendingWrites + n <= Gatt.window else { end(lane, tell: .reset(lane.id, .flow)); return }
        lane.pendingWrites += n
        lane.upstream?.write(frame.payload) { [weak self, weak lane] ok in
            guard let self = self, let lane = lane, lane.state == .open, let c = self.owner(of: lane) else { return }
            lane.pendingWrites -= n
            if ok { lane.owed += n; self.flush(c) } else { self.end(lane, tell: .reset(lane.id, .unavailable)) }
        }
    }

    /// Bytes from the loopback socket, at most what was asked for: framed, counted against the phone's credit, queued.
    private func received(_ lane: Lane, _ bytes: [UInt8]) {
        lane.reading = false
        guard lane.state == .open, let c = owner(of: lane) else { return }
        let step = Gatt.maxPayload(frame: host?.maxFrame(central: lane.central) ?? Gatt.minFrame)
        var at = 0
        while at < bytes.count {
            let n = min(bytes.count - at, step, lane.credit)
            // more than we asked for, or more than the radio queue may hold: never forwarded, never queued past the cap
            if n <= 0 || c.outbox.count >= Bridge.dataLimit { end(lane, tell: .reset(lane.id, .flow)); return }
            c.outbox.append(Frame.data(lane.id, seq: lane.txSeq, Array(bytes[at..<(at + n)])).encode())
            lane.txSeq &+= 1
            lane.credit -= n
            at += n
        }
        flush(c)
    }

    private func closedUpstream(_ lane: Lane, error: Bool) {
        guard lane.state != .ended else { return }
        end(lane, tell: error ? .reset(lane.id, .unavailable) : .close(lane.id))
    }

    /// Asks the loopback socket for more only while the phone has granted credit and the radio queue has room.
    private func pump(_ lane: Lane) {
        // every read already asked for may still deliver one frame, so those slots count as taken
        guard lane.state == .open, !lane.reading, lane.credit > 0, let c = owner(of: lane), !c.blocked,
              c.outbox.count + c.lanes.values.filter({ $0.reading }).count < Bridge.dataLimit else { return }
        lane.reading = true
        let step = Gatt.maxPayload(frame: host?.maxFrame(central: lane.central) ?? Gatt.minFrame)
        lane.upstream?.read(max: min(lane.credit, step))
    }

    private func end(_ lane: Lane, tell frame: Frame?) {
        guard lane.state != .ended else { return }
        lane.state = .ended
        let upstream = lane.upstream
        lane.upstream = nil
        upstream?.close()
        guard let c = centrals[lane.central], c === lane.owner else { return } // a replaced phone's lane has nothing left to update
        if c.lanes[lane.id] === lane { c.lanes[lane.id] = nil }
        if let frame = frame { queue(c, frame, terminal: true) }
    }

    /// The lane as the current incarnation of its phone knows it, or nil for an ended lane, a replaced phone or a replaced lane.
    private func owner(of lane: Lane) -> Central? {
        guard lane.state != .ended, let c = centrals[lane.central], c === lane.owner, c.lanes[lane.id] === lane else { return nil }
        return c
    }

    /// The only way into a phone's outbox. A frame that would pass the cap is never queued and never dropped silently: the
    /// phone is cut off (`fail`), so its queue stays bounded however many refused OPENs it sends while notifications block.
    private func queue(_ c: Central, _ frame: Frame, terminal: Bool = false) {
        guard !c.failed else { return }
        if c.outbox.count >= (terminal ? Bridge.maxOutbox : Bridge.dataLimit) { fail(c, also: frame.stream); return }
        c.outbox.append(frame.encode())
        flush(c)
    }

    /// Ends every stream of [c] without a word (the radio cannot take one), empties its queue and remembers which streams to
    /// RESET once the radio is ready. CoreBluetooth cannot disconnect a central, so this is the strongest thing a peripheral can do.
    private func fail(_ c: Central, also stream: UInt8) {
        guard !c.failed else { return }
        c.failed = true
        var lost = c.lanes.keys.sorted()
        if !lost.contains(stream) { lost.append(stream) }
        c.lost = lost
        for lane in Array(c.lanes.values) { end(lane, tell: nil) }
        c.outbox.removeAll()
    }

    private func flush(_ c: Central) {
        guard !c.blocked, let host = host else { return }
        let id = c.id
        for lane in Array(c.lanes.values) where lane.owed > 0 {
            let n = min(lane.owed, Gatt.window)
            if host.notify(central: id, frame: Frame.credit(lane.id, n).encode()) { lane.owed -= n } else { c.blocked = true; return }
        }
        while let first = c.outbox.first {
            if host.notify(central: id, frame: first) { c.outbox.removeFirst() } else { c.blocked = true; return }
        }
        for lane in Array(c.lanes.values) { pump(lane) }
    }
}
