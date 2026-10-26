"""Ported from the Mac bridge's FramingTests.swift and BridgeTests.swift, plus the Linux one-phone
rule.
"""

import os
import unittest

from phonemesh_bridge import core
from phonemesh_bridge.core import WINDOW, Bridge, Frame


class FakeUpstream:
    def __init__(self, events):
        self.events, self.reads, self.writes, self.write_done, self.closed = (
            events,
            [],
            [],
            [],
            False,
        )

    def read(self, n):
        self.reads.append(n)

    def write(self, data, done):
        self.writes.append(bytes(data))
        self.write_done.append(done)

    def close(self):
        self.closed = True


class FakeHost:
    def __init__(self):
        self.upstreams, self.accept, self.blocked, self.mtu, self.sent = [], True, False, 20, {}

    def connect_upstream(self, lane):
        if not self.accept:
            return None
        up = FakeUpstream(lane)
        self.upstreams.append(up)
        return up

    def notify(self, central, frame):
        if self.blocked:
            return False
        self.sent.setdefault(central, []).append(frame)
        return True

    def max_frame(self, central):
        return self.mtu

    def frames(self, central):
        return [Frame.decode(f) for f in self.sent.get(central, [])]


def w_open(s):
    return Frame(core.OPEN, s, 0, bytes((core.VERSION,))).encode()


A = "/org/bluez/hci0/dev_AA"


class FramingTest(unittest.TestCase):
    def test_known_encodings(self):
        self.assertEqual(Frame.credit(5, 0x1234).encode(), bytes([4, 5, 0, 0x12, 0x34]))
        self.assertEqual(Frame.opened(7).encode(), bytes([2, 7, 0]))
        self.assertEqual(Frame.close(9).encode(), bytes([5, 9, 0]))
        self.assertEqual(Frame.reset(3, core.R_BUSY).encode(), bytes([6, 3, 0, 3]))
        self.assertEqual(Frame.data(1, 255, b"\xaa\xbb").encode(), bytes([3, 1, 255, 0xAA, 0xBB]))
        self.assertEqual(w_open(2), bytes([1, 2, 0, 1]))

    def test_malformed_frames_are_rejected(self):
        bad = [
            [],
            [1],
            [1, 1],
            [0, 1, 0, 1],
            [7, 1, 0, 1],
            [255, 1, 0, 1],
            [1, 0, 0, 1],
            [1, 1, 0],
            [1, 1, 0, 2],
            [1, 1, 0, 1, 1],
            [1, 1, 1, 1],
            [2, 1, 0, 0],
            [5, 1, 0, 0],
            [3, 1, 0],
            [4, 1, 0],
            [4, 1, 0, 0],
            [4, 1, 0, 0, 0],
            [4, 1, 0, 0x20, 0x01],
            [4, 1, 0, 1],
            [4, 1, 0, 0, 1, 0],
            [6, 1, 0],
            [6, 1, 0, 1, 2],
            [3] * (core.MAX_FRAME + 1),
        ]
        for b in bad:
            self.assertIsNone(Frame.decode(bytes(b)), b)
        self.assertIsNotNone(Frame.decode(bytes([3, 1, 0] + [9] * (core.MAX_FRAME - core.HEADER))))

    def test_round_trip_and_clamps(self):
        for f in [
            Frame.opened(1),
            Frame.close(2),
            Frame.reset(255, core.R_FLOW),
            Frame.credit(4, 1),
            Frame.credit(4, WINDOW),
            Frame.data(9, 200, b"\x01\x02\x03"),
            Frame(core.OPEN, 1, 0, b"\x01"),
        ]:
            self.assertEqual(Frame.decode(f.encode()), f)
        self.assertEqual(core.max_payload(0), core.MIN_FRAME - core.HEADER)
        self.assertEqual(core.max_payload(20), 17)
        self.assertEqual(core.max_payload(100000), core.MAX_FRAME - core.HEADER)

    def test_pin_and_service_uuid_shape(self):
        self.assertTrue(core.is_pin("A" * 43))
        self.assertTrue(core.is_pin("-" + "z" * 41 + "_"))
        for bad in ["A" * 42, "A" * 44, "A" * 42 + "=", "A" * 42 + " "]:
            self.assertFalse(core.is_pin(bad))
        a, b = core.service_uuid("A" * 43), core.service_uuid("B" * 43)
        self.assertNotEqual(a, b)
        for u in (a, b):
            self.assertRegex(
                u, r"^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
            )

    def test_service_uuid_matches_kotlin_vectors(self):
        """Set PM_GATT_VECTORS to android/core/build/gatt-vectors/service-uuids.txt
        (GattVectorsExportTest).
        """
        path = os.environ.get("PM_GATT_VECTORS")
        if not path:
            self.skipTest("PM_GATT_VECTORS not set")
        with open(path) as f:
            lines = [ln.split() for ln in f if ln.strip()]
        self.assertTrue(lines)
        for pin, uuid in lines:
            self.assertEqual(core.service_uuid(pin), uuid)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.t = 1000.0
        self.host = FakeHost()
        self.bridge = Bridge(self.host, now=lambda: self.t)

    def open_stream(self, s, central=A):
        self.bridge.received(central, w_open(s))
        up = self.host.upstreams[-1]
        up.events.upstream_connected()
        return up

    def data_bytes(self, central, s):
        return sum(
            len(f.payload)
            for f in self.host.frames(central)
            if f.kind == core.DATA and f.stream == s
        )

    def test_opened_only_after_the_loopback_connects(self):
        self.bridge.received(A, w_open(1))
        self.assertEqual(self.host.frames(A), [])
        self.host.upstreams[0].events.upstream_connected()
        self.assertEqual(self.host.frames(A), [Frame.opened(1)])
        self.assertEqual(self.host.upstreams[0].reads, [17])

    def test_no_loopback_means_unavailable(self):
        self.host.accept = False
        self.bridge.received(A, w_open(1))
        self.assertEqual(self.host.frames(A), [Frame.reset(1, core.R_UNAVAILABLE)])
        self.assertEqual(self.bridge.active_streams, 0)

    def test_third_and_duplicate_streams_are_busy(self):
        self.open_stream(1)
        self.open_stream(2)
        self.bridge.received(A, w_open(3))
        self.bridge.received(A, w_open(1))
        self.assertEqual(
            self.host.frames(A)[-2:], [Frame.reset(3, core.R_BUSY), Frame.reset(1, core.R_BUSY)]
        )
        self.assertEqual(len(self.host.upstreams), 2)

    def test_at_most_four_phones_by_default(self):
        for name in "abcd":
            self.bridge.received(name, w_open(1))
        self.bridge.received("e", w_open(1))
        self.assertEqual(len(self.host.upstreams), 4)
        self.assertEqual(self.host.frames("e"), [])

    def test_linux_serves_one_phone_at_a_time(self):
        bridge = Bridge(self.host, max_centrals=1, now=lambda: self.t)
        bridge.received("a", w_open(1))
        bridge.received("b", w_open(1))
        self.assertEqual(len(self.host.upstreams), 1)
        self.assertFalse(bridge.is_active("b"))
        bridge.central_gone("a")
        self.assertTrue(self.host.upstreams[0].closed)
        bridge.received("b", w_open(1))
        self.assertEqual(len(self.host.upstreams), 2)
        self.assertTrue(bridge.is_active("b"))

    def test_junk_is_dropped(self):
        for raw in (b"", bytes([9, 9, 9, 9]), bytes([1, 0, 0, 1])):
            self.bridge.received(A, raw)
        self.assertEqual(self.host.upstreams, [])
        self.assertEqual(self.host.frames(A), [])

    def test_upstream_bytes_are_fragmented_in_order(self):
        up = self.open_stream(1)
        payload = bytes(range(40))
        up.events.upstream_data(payload)
        data = [f for f in self.host.frames(A) if f.kind == core.DATA]
        self.assertEqual([len(f.payload) for f in data], [17, 17, 6])
        self.assertEqual([f.seq for f in data], [0, 1, 2])
        self.assertEqual(b"".join(f.payload for f in data), payload)

    def test_sequence_numbers_wrap(self):
        up = self.open_stream(1)
        for _ in range(258):
            up.events.upstream_data(b"\x01")
        seqs = [f.seq for f in self.host.frames(A) if f.kind == core.DATA]
        self.assertEqual(seqs, [i % 256 for i in range(258)])

    def test_hub_to_phone_stops_at_the_window_and_resumes_on_credit(self):
        self.host.mtu = 512
        up = self.open_stream(1)
        delivered = 0
        while len(up.reads) > delivered:
            n = up.reads[delivered]
            delivered += 1
            up.events.upstream_data(b"\x05" * n)
        self.assertEqual(self.data_bytes(A, 1), WINDOW)
        self.bridge.received(A, Frame.credit(1, 100).encode())
        self.assertEqual(up.reads[-1], 100)

    def test_credit_beyond_the_window_resets(self):
        up = self.open_stream(1)
        self.bridge.received(A, Frame.credit(1, 1).encode())
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(1, core.R_FLOW))
        self.assertTrue(up.closed)

    def test_more_than_credited_is_never_forwarded(self):
        self.host.mtu = 512
        up = self.open_stream(1)
        up.events.upstream_data(b"\x01" * (WINDOW + 1))
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(1, core.R_FLOW))
        self.assertLessEqual(self.data_bytes(A, 1), WINDOW)

    def test_phone_data_is_written_and_credited_after_the_write(self):
        up = self.open_stream(1)
        self.bridge.received(A, Frame.data(1, 0, b"\x07" * 100).encode())
        self.assertEqual(up.writes, [b"\x07" * 100])
        self.assertFalse(any(f.kind == core.CREDIT for f in self.host.frames(A)))
        up.write_done[0](True)
        self.assertEqual(self.host.frames(A)[-1], Frame.credit(1, 100))

    def test_credit_waits_for_a_radio_that_can_take_it(self):
        up = self.open_stream(1)
        self.host.blocked = True
        self.bridge.received(A, Frame.data(1, 0, b"\x07" * 10).encode())
        up.write_done[0](True)
        self.assertEqual(self.host.frames(A), [Frame.opened(1)])
        self.host.blocked = False
        self.bridge.ready(A)
        self.assertEqual(self.host.frames(A), [Frame.opened(1), Frame.credit(1, 10)])

    def test_failed_write_and_protocol_errors_reset(self):
        up = self.open_stream(1)
        self.bridge.received(A, Frame.data(1, 0, b"\x01").encode())
        up.write_done[0](False)
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(1, core.R_UNAVAILABLE))
        up2 = self.open_stream(2)
        self.bridge.received(A, Frame.data(2, 5, b"\x01").encode())
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(2, core.R_PROTOCOL))
        self.assertTrue(up2.closed)

    def test_phone_may_not_outrun_the_window(self):
        up = self.open_stream(1)
        for seq in range(16):
            self.bridge.received(A, Frame.data(1, seq, b"\x01" * 500).encode())
        self.assertFalse(up.closed)
        self.bridge.received(A, Frame.data(1, 16, b"\x01" * 500).encode())
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(1, core.R_FLOW))

    def test_ending_from_either_side(self):
        up = self.open_stream(1)
        self.bridge.received(A, Frame.close(1).encode())
        self.assertTrue(up.closed)
        self.assertEqual(self.host.frames(A), [Frame.opened(1)])
        up2 = self.open_stream(2)
        up2.events.upstream_closed(False)
        self.assertEqual(self.host.frames(A)[-1], Frame.close(2))

    def test_disconnect_ends_everything_and_late_callbacks_change_nothing(self):
        up = self.open_stream(1)
        self.bridge.received(A, Frame.data(1, 0, b"\x01\x02").encode())
        self.bridge.central_gone(A)
        self.assertTrue(up.closed)
        up.write_done[0](True)
        up.events.upstream_data(b"late")
        up.events.upstream_closed(True)
        self.assertEqual(self.host.frames(A), [Frame.opened(1)])
        self.assertEqual(self.bridge.active_streams, 0)

    def test_deadlines(self):
        self.bridge.received(A, w_open(1))
        self.t += 6
        self.bridge.tick()
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(1, core.R_UNAVAILABLE))
        self.open_stream(2)
        self.t += 91
        self.bridge.tick()
        self.assertEqual(self.host.frames(A)[-1], Frame.reset(2, core.R_TIMEOUT))

    def test_overflow_cuts_the_phone_off_until_ready(self):
        self.open_stream(1)
        self.host.blocked = True
        for s in range(3, 3 + Bridge.MAX_OUTBOX + 2):
            self.bridge.received(A, w_open(s))  # each refused OPEN queues a RESET busy
        self.assertTrue(self.bridge.is_quarantined(A))
        self.assertLessEqual(self.bridge.queued_notifications(A), Bridge.MAX_OUTBOX)
        self.host.blocked = False
        self.bridge.ready(A)
        self.assertFalse(self.bridge.is_quarantined(A))
        self.assertTrue(all(f.kind == core.RESET for f in self.host.frames(A)[1:]))


if __name__ == "__main__":
    unittest.main()
