import XCTest
import BridgeCore

/// BridgeCore with no Bluetooth and no sockets: FakeHost plays the radio and the hub's loopback port.
final class BridgeTests: XCTestCase {
    private var host = FakeHost()
    private var clock = Clock()
    private var bridge: Bridge!
    private let A = "A"

    override func setUp() {
        host = FakeHost()
        clock = Clock()
        let clock = self.clock
        bridge = Bridge(host: host, now: { clock.t })
    }

    /// OPEN from the phone, then the loopback connection comes up. Returns the fake connection.
    @discardableResult
    private func openStream(_ s: UInt8, central: String = "A") -> FakeUpstream {
        bridge.received(central: central, bytes: Wire.open(s))
        let up = host.upstreams.last!
        up.events!.upstreamConnected()
        return up
    }

    /// The hub's server answers every read the bridge asks for, `chunk` bytes at most; returns the bytes delivered.
    @discardableResult
    private func serve(_ up: FakeUpstream, from index: inout Int, chunk: Int = .max) -> Int {
        var total = 0
        while up.reads.count > index {
            let ask = min(up.reads[index], chunk)
            index += 1
            up.events!.upstreamData([UInt8](repeating: 1, count: ask))
            total += ask
        }
        return total
    }

    private func dataBytes(_ frames: [Frame], stream: UInt8) -> Int {
        frames.filter { $0.kind == .data && $0.stream == stream }.reduce(0) { $0 + $1.payload.count }
    }

    // MARK: opening

    func testOpenedIsAnnouncedOnlyAfterTheLoopbackConnects() {
        bridge.received(central: A, bytes: Wire.open(1))
        XCTAssertEqual(host.upstreams.count, 1)
        XCTAssertEqual(host.frames(A), [])
        host.upstreams[0].events!.upstreamConnected()
        XCTAssertEqual(host.frames(A), [.opened(1)])
        XCTAssertEqual(host.upstreams[0].reads, [17]) // first read: one frame's worth at mtu 20
    }

    func testNoLoopbackMeansUnavailable() {
        host.acceptUpstream = false
        bridge.received(central: A, bytes: Wire.open(1))
        XCTAssertEqual(host.frames(A), [.reset(1, .unavailable)])
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testLoopbackRefusalAfterOpenIsAResetNotACleartextRetry() {
        bridge.received(central: A, bytes: Wire.open(1))
        host.upstreams[0].events!.upstreamClosed(error: true)
        XCTAssertEqual(host.frames(A), [.reset(1, .unavailable)])
        XCTAssertTrue(host.upstreams[0].closed)
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testThirdStreamAndDuplicateStreamAreBusy() {
        openStream(1); openStream(2)
        bridge.received(central: A, bytes: Wire.open(3))
        bridge.received(central: A, bytes: Wire.open(1))
        XCTAssertEqual(Array(host.frames(A).suffix(2)), [.reset(3, .busy), .reset(1, .busy)])
        XCTAssertEqual(host.upstreams.count, 2)
    }

    func testAtMostFourPhones() {
        for name in ["a", "b", "c", "d"] { bridge.received(central: name, bytes: Wire.open(1)) }
        bridge.received(central: "e", bytes: Wire.open(1))
        XCTAssertEqual(host.upstreams.count, 4)
        XCTAssertEqual(host.frames("e"), [])
    }

    func testJunkIsDropped() {
        bridge.received(central: A, bytes: [])
        bridge.received(central: A, bytes: [9, 9, 9, 9])
        bridge.received(central: A, bytes: [1, 0, 0, 1])
        XCTAssertEqual(host.upstreams.count, 0)
        XCTAssertEqual(host.frames(A), [])
    }

    // MARK: fragmentation

    func testUpstreamBytesAreFragmentedToTheLinkAndKeepOrder() {
        let up = openStream(1)
        let payload = (0..<40).map { UInt8($0) }
        up.events!.upstreamData(payload)
        let data = host.frames(A).filter { $0.kind == .data }
        XCTAssertEqual(data.map { $0.payload.count }, [17, 17, 6])
        XCTAssertEqual(data.map { $0.seq }, [0, 1, 2])
        XCTAssertEqual(data.flatMap { $0.payload }, payload)
        XCTAssertTrue((host.sent[A] ?? []).allSatisfy { $0.count <= host.mtu })
    }

    func testSequenceNumbersWrapAt256() {
        let up = openStream(1)
        for _ in 0..<258 { up.events!.upstreamData([1]) } // 258 one-byte frames, well inside the 8192-byte window
        let seqs = host.frames(A).filter { $0.kind == .data }.map { Int($0.seq) }
        XCTAssertEqual(seqs.count, 258)
        XCTAssertEqual(seqs, (0..<258).map { $0 % 256 })
    }

    // MARK: flow control

    func testHubToPhoneStopsAtTheWindowAndResumesOnCredit() {
        host.mtu = 512
        let up = openStream(1)
        var index = 0
        let delivered = serve(up, from: &index)
        XCTAssertEqual(delivered, Gatt.window)
        XCTAssertEqual(dataBytes(host.frames(A), stream: 1), Gatt.window)
        XCTAssertEqual(up.reads.count, index, "no read is outstanding once the credit is spent")

        bridge.received(central: A, bytes: Wire.credit(1, 100))
        XCTAssertEqual(up.reads.last, 100)
        XCTAssertEqual(up.reads.count, index + 1)
    }

    func testCreditBeyondTheWindowResetsTheStream() {
        let up = openStream(1)
        bridge.received(central: A, bytes: Wire.credit(1, 1)) // the phone never spent anything: 8192 + 1 is not allowed
        XCTAssertEqual(host.frames(A).last, .reset(1, .flow))
        XCTAssertTrue(up.closed)
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testMoreBytesThanAskedOrCreditedIsNeverForwarded() {
        host.mtu = 512
        let up = openStream(1)
        // 8192 credit: a misbehaving loopback side hands over more than that in one go.
        up.events!.upstreamData([UInt8](repeating: 1, count: Gatt.window + 1))
        XCTAssertEqual(host.frames(A).last, .reset(1, .flow))
        XCTAssertLessThanOrEqual(dataBytes(host.frames(A), stream: 1), Gatt.window)
        XCTAssertTrue(up.closed)
    }

    func testPhoneDataIsWrittenUpstreamAndCreditIsGivenBackOnlyAfterTheWrite() {
        let up = openStream(1)
        bridge.received(central: A, bytes: Wire.data(1, seq: 0, [UInt8](repeating: 7, count: 100)))
        XCTAssertEqual(up.writes, [[UInt8](repeating: 7, count: 100)])
        XCTAssertFalse(host.frames(A).contains { $0.kind == .credit })
        up.writeDone[0](true)
        XCTAssertEqual(host.frames(A).last, .credit(1, 100))
        bridge.received(central: A, bytes: Wire.data(1, seq: 1, [1, 2, 3]))
        up.writeDone[1](true)
        XCTAssertEqual(host.frames(A).last, .credit(1, 3))
    }

    func testCreditWaitsForARadioThatCanTakeIt() {
        let up = openStream(1)
        host.blocked = true
        bridge.received(central: A, bytes: Wire.data(1, seq: 0, [UInt8](repeating: 7, count: 10)))
        up.writeDone[0](true)
        XCTAssertEqual(host.frames(A), [.opened(1)])
        host.blocked = false
        bridge.ready(central: A)
        XCTAssertEqual(host.frames(A), [.opened(1), .credit(1, 10)])
    }

    func testFailedUpstreamWriteResets() {
        let up = openStream(1)
        bridge.received(central: A, bytes: Wire.data(1, seq: 0, [1]))
        up.writeDone[0](false)
        XCTAssertEqual(host.frames(A).last, .reset(1, .unavailable))
        XCTAssertTrue(up.closed)
    }

    func testPhoneMayNotOutrunTheWindow() {
        let up = openStream(1)
        let chunk = [UInt8](repeating: 1, count: 500)
        for seq in 0..<16 { bridge.received(central: A, bytes: Wire.data(1, seq: UInt8(seq), chunk)) }
        XCTAssertEqual(up.writes.count, 16)
        XCTAssertFalse(up.closed)
        bridge.received(central: A, bytes: Wire.data(1, seq: 16, chunk)) // 8500 bytes unwritten: over the window
        XCTAssertEqual(host.frames(A).last, .reset(1, .flow))
        XCTAssertTrue(up.closed)
    }

    func testOutOfOrderOrEarlyDataIsAProtocolError() {
        let up = openStream(1)
        bridge.received(central: A, bytes: Wire.data(1, seq: 5, [1]))
        XCTAssertEqual(host.frames(A).last, .reset(1, .protocolError))
        XCTAssertTrue(up.closed)

        bridge.received(central: A, bytes: Wire.open(2)) // not connected yet
        bridge.received(central: A, bytes: Wire.data(2, seq: 0, [1]))
        XCTAssertEqual(host.frames(A).last, .reset(2, .protocolError))
        XCTAssertTrue(host.upstreams[1].writes.isEmpty)
    }

    func testDataForAClosedStreamIsIgnored() {
        bridge.received(central: A, bytes: Wire.data(9, seq: 0, [1]))
        XCTAssertEqual(host.frames(A), [])
        XCTAssertEqual(host.upstreams.count, 0)
    }

    func testEndingFromEitherSide() {
        let up = openStream(1)
        bridge.received(central: A, bytes: Wire.close(1))
        XCTAssertTrue(up.closed)
        XCTAssertEqual(bridge.activeStreams, 0)
        XCTAssertEqual(host.frames(A), [.opened(1)]) // the phone ended it: nothing to tell it

        let up2 = openStream(2)
        up2.events!.upstreamClosed(error: false)
        XCTAssertEqual(host.frames(A).last, .close(2))
        XCTAssertTrue(up2.closed)
    }

    func testBlockedRadioPausesReadsUntilReady() {
        let up1 = openStream(1), up2 = openStream(2)
        host.blocked = true
        up1.events!.upstreamData([1, 2, 3])
        up2.events!.upstreamData([4, 5, 6])
        XCTAssertEqual(up1.reads.count, 1)
        XCTAssertEqual(up2.reads.count, 1)
        XCTAssertEqual(bridge.queuedNotifications(central: A), 2)
        host.blocked = false
        bridge.ready(central: A)
        XCTAssertEqual(bridge.queuedNotifications(central: A), 0)
        XCTAssertEqual(up1.reads.count, 2)
        XCTAssertEqual(up2.reads.count, 2)
    }

    // MARK: stale callbacks and disconnects

    func testDisconnectEndsEverythingAndLateCallbacksChangeNothing() {
        let up1 = openStream(1), up2 = openStream(2)
        bridge.received(central: A, bytes: Wire.data(1, seq: 0, [1, 2, 3]))
        let ev1 = up1.events!
        let sentBefore = host.sent[A]?.count ?? 0

        bridge.centralGone(A)
        XCTAssertTrue(up1.closed)
        XCTAssertTrue(up2.closed)
        XCTAssertEqual(bridge.activeStreams, 0)

        ev1.upstreamConnected()
        ev1.upstreamData([9, 9, 9])
        ev1.upstreamClosed(error: true)
        up1.writeDone[0](true)
        XCTAssertEqual(host.sent[A]?.count ?? 0, sentBefore)
        XCTAssertEqual(bridge.queuedNotifications(central: A), 0)
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testReplacedPhoneIncarnationIsNotTouchedByTheOldOnesCallbacks() {
        bridge.centralConnected(A)
        let oldUp = openStream(1)
        let oldEvents = oldUp.events!
        bridge.received(central: A, bytes: Wire.data(1, seq: 0, [1]))

        bridge.centralConnected(A) // the phone resubscribed without a disconnect in between
        XCTAssertTrue(oldUp.closed)
        let newUp = openStream(1)  // same stream number, new incarnation
        let sentBefore = host.sent[A]?.count ?? 0

        oldEvents.upstreamClosed(error: false)
        oldEvents.upstreamClosed(error: true)
        oldEvents.upstreamData([1, 2, 3])
        oldEvents.upstreamConnected()
        oldUp.writeDone[0](true)

        XCTAssertFalse(newUp.closed)
        XCTAssertEqual(bridge.activeStreams, 1)
        XCTAssertEqual(host.sent[A]?.count ?? 0, sentBefore)
        XCTAssertEqual(newUp.reads.count, 1)
        // and the new stream is fully alive
        newUp.events!.upstreamData([5])
        XCTAssertEqual(dataBytes(host.frames(A), stream: 1), 1)
    }

    func testConnectingLaneOfAReplacedPhoneNeverAnnouncesOpened() {
        bridge.received(central: A, bytes: Wire.open(1))
        let oldEvents = host.upstreams[0].events!
        bridge.centralConnected(A)
        oldEvents.upstreamConnected()
        XCTAssertEqual(host.frames(A), [])
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testReopenedStreamNumberIsNotTouchedByTheOldLane() {
        let oldUp = openStream(1)
        let oldEvents = oldUp.events!
        bridge.received(central: A, bytes: Wire.close(1))
        let newUp = openStream(1)
        oldEvents.upstreamClosed(error: false)
        oldEvents.upstreamData([1])
        XCTAssertFalse(newUp.closed)
        XCTAssertEqual(bridge.activeStreams, 1)
        XCTAssertEqual(host.frames(A).filter { $0.kind == .close || $0.kind == .reset }, [])
    }

    func testRadioReadyForAnUnknownPhoneIsIgnored() {
        bridge.ready(central: "nobody")
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    // MARK: deadlines

    func testConnectDeadline() {
        bridge.received(central: A, bytes: Wire.open(1))
        clock.t += 4.9
        bridge.tick()
        XCTAssertEqual(host.frames(A), [])
        clock.t += 0.2
        bridge.tick()
        XCTAssertEqual(host.frames(A), [.reset(1, .unavailable)])
        XCTAssertTrue(host.upstreams[0].closed)
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testLaneDeadline() {
        let up = openStream(1)
        clock.t += 89
        bridge.tick()
        XCTAssertFalse(up.closed)
        clock.t += 1.5
        bridge.tick()
        XCTAssertEqual(host.frames(A).last, .reset(1, .timeout))
        XCTAssertTrue(up.closed)
        XCTAssertEqual(bridge.activeStreams, 0)
    }

    func testDeadlineWithABlockedRadioStaysBounded() {
        let up = openStream(1)
        host.blocked = true
        clock.t += 91
        bridge.tick()
        XCTAssertTrue(up.closed)
        XCTAssertEqual(bridge.queuedNotifications(central: A), 1)
        host.blocked = false
        bridge.ready(central: A)
        XCTAssertEqual(host.frames(A).last, .reset(1, .timeout))
    }

    // MARK: bounded outbox

    func testRepeatedOpenWhileNotificationsBlockStaysBounded() {
        host.blocked = true
        bridge.centralConnected(A)
        bridge.received(central: A, bytes: Wire.open(1)) // a real lane, never connecting
        let up = host.upstreams[0]
        for _ in 0..<2_000 {
            bridge.received(central: A, bytes: Wire.open(1)) // refused: busy
            XCTAssertLessThanOrEqual(bridge.queuedNotifications(central: A), Bridge.maxOutbox)
        }
        XCTAssertTrue(bridge.isQuarantined(central: A))
        XCTAssertEqual(bridge.queuedNotifications(central: A), 0)
        XCTAssertEqual(bridge.activeStreams, 0)
        XCTAssertTrue(up.closed)
        XCTAssertEqual(host.upstreams.count, 1, "a cut-off phone opens nothing more")
        XCTAssertEqual(host.sent[A] ?? [], [])

        // The radio recovers: the stream that was ended is told so, once, and the phone is served again.
        host.blocked = false
        bridge.ready(central: A)
        XCTAssertFalse(bridge.isQuarantined(central: A))
        XCTAssertEqual(host.frames(A), [.reset(1, .flow)])
        bridge.received(central: A, bytes: Wire.open(2))
        XCTAssertEqual(host.upstreams.count, 2)
    }

    func testRepeatedOpenOfManyStreamNumbersWhileBlockedStaysBounded() {
        host.blocked = true
        bridge.centralConnected(A)
        bridge.received(central: A, bytes: Wire.open(1))
        bridge.received(central: A, bytes: Wire.open(2))
        for i in 0..<3_000 {
            bridge.received(central: A, bytes: Wire.open(UInt8(3 + i % 250))) // every one refused: two streams already open
            XCTAssertLessThanOrEqual(bridge.queuedNotifications(central: A), Bridge.maxOutbox)
        }
        XCTAssertTrue(bridge.isQuarantined(central: A))
        XCTAssertEqual(bridge.activeStreams, 0)
        XCTAssertTrue(host.upstreams.allSatisfy { $0.closed })
        host.blocked = false
        bridge.ready(central: A)
        let resets = host.frames(A)
        XCTAssertLessThanOrEqual(resets.count, Gatt.maxStreams + 1)
        XCTAssertTrue(resets.allSatisfy { $0.kind == .reset && $0.payload == [Gatt.Reason.flow.rawValue] })
        XCTAssertTrue(resets.contains(.reset(1, .flow)) && resets.contains(.reset(2, .flow)))
    }

    func testOverflowOfOnePhoneDoesNotTouchAnother() {
        host.blocked = false
        let other = openStream(1, central: "B")
        host.blocked = true
        bridge.centralConnected(A)
        bridge.received(central: A, bytes: Wire.open(1))
        for _ in 0..<200 { bridge.received(central: A, bytes: Wire.open(1)) }
        XCTAssertTrue(bridge.isQuarantined(central: A))
        XCTAssertFalse(bridge.isQuarantined(central: "B"))
        XCTAssertFalse(other.closed)
    }

    func testMisbehavingUpstreamCannotFillTheOutboxWhileBlocked() {
        let up = openStream(1)
        host.blocked = true
        // Keep the callback to inject late data after the bridge releases the ended lane.
        let events = up.events!
        for _ in 0..<100 {
            events.upstreamData([UInt8](repeating: 3, count: 17))
            XCTAssertLessThanOrEqual(bridge.queuedNotifications(central: A), Bridge.maxOutbox)
        }
        XCTAssertTrue(up.closed)
        XCTAssertEqual(bridge.activeStreams, 0)
        XCTAssertFalse(bridge.isQuarantined(central: A), "a RESET fitted into the reserved slots, so the phone is not cut off")

        host.blocked = false
        bridge.ready(central: A)
        let frames = host.frames(A)
        XCTAssertEqual(frames.first, .opened(1))
        let data = frames.filter { $0.kind == .data }
        XCTAssertEqual(data.count, Bridge.dataLimit)
        XCTAssertEqual(data.map { Int($0.seq) }, Array(0..<Bridge.dataLimit))
        XCTAssertEqual(frames.last, .reset(1, .flow))
    }

    func testRefusedOpenFramesNeverCrowdOutAStreamsReset() {
        // Fill the queue to the data limit with refusals while one stream is open, then end that stream: its RESET still fits.
        let up = openStream(1)
        host.blocked = true
        for _ in 0..<(Bridge.dataLimit - 1) { bridge.received(central: A, bytes: Wire.open(1)) }
        XCTAssertFalse(bridge.isQuarantined(central: A))
        up.events!.upstreamClosed(error: true)
        XCTAssertFalse(bridge.isQuarantined(central: A))
        XCTAssertLessThanOrEqual(bridge.queuedNotifications(central: A), Bridge.maxOutbox)
        host.blocked = false
        bridge.ready(central: A)
        XCTAssertEqual(host.frames(A).last, .reset(1, .unavailable))
    }
}
