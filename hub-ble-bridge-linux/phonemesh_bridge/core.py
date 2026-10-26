"""The Bluetooth hub transport and the bridge's stream table: a port of the Mac bridge's BridgeCore
(Framing.swift,
Bridge.swift), with no Bluetooth and no sockets in it. Change the wire facts only together with
HubGatt.kt and
Framing.swift, and the vectors in all three test suites.

    byte 0 type | byte 1 stream (1...255) | byte 2 seq (DATA only, else 0) | bytes 3... payload

Everything must be called on one thread (the GLib main loop). The bridge never parses, terminates or
logs what it
carries: TLS runs between the phone and the hub's JVM.
"""

import hashlib
import time

RX_UUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b001"  # phone writes here
TX_UUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b002"  # hub notifies here
VERSION = 1
HEADER = 3
MIN_FRAME = 8
MAX_FRAME = 512
WINDOW = 8192
MAX_STREAMS = 2

OPEN, OPENED, DATA, CREDIT, CLOSE, RESET = 1, 2, 3, 4, 5, 6
KINDS = (OPEN, OPENED, DATA, CREDIT, CLOSE, RESET)
R_PROTOCOL, R_UNAVAILABLE, R_BUSY, R_TIMEOUT, R_CLOSED, R_FLOW = 1, 2, 3, 4, 5, 6

_PIN_CHARS = set(b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_")


def service_uuid(pin: str) -> str:
    """SHA-256("phonemesh-hub-gatt-1:" + pin), first 16 bytes, version-5 and variant bits set. Not
    authentication.
    """
    b = bytearray(hashlib.sha256(("phonemesh-hub-gatt-1:" + pin).encode()).digest()[:16])
    b[6] = (b[6] & 0x0F) | 0x50
    b[8] = (b[8] & 0x3F) | 0x80
    h = b.hex()
    return "-".join((h[0:8], h[8:12], h[12:16], h[16:20], h[20:32]))


def is_pin(text: str) -> bool:
    """A pin as the hub card prints it: SHA-256 of the key, base64url, 43 characters, no padding."""
    raw = text.encode()
    return len(raw) == 43 and all(c in _PIN_CHARS for c in raw)


def max_payload(frame: int) -> int:
    """Most DATA bytes one frame carries when a link takes [frame] bytes per notification."""
    return min(max(frame, MIN_FRAME), MAX_FRAME) - HEADER


class Frame:
    __slots__ = ("kind", "stream", "seq", "payload")

    def __init__(self, kind, stream, seq=0, payload=b""):
        self.kind, self.stream, self.seq, self.payload = kind, stream, seq, bytes(payload)

    def __eq__(self, other):
        return isinstance(other, Frame) and (self.kind, self.stream, self.seq, self.payload) == (
            other.kind,
            other.stream,
            other.seq,
            other.payload,
        )

    def __repr__(self):
        return f"Frame({self.kind}, {self.stream}, {self.seq}, {self.payload!r})"

    @staticmethod
    def opened(s):
        return Frame(OPENED, s)

    @staticmethod
    def data(s, seq, payload):
        return Frame(DATA, s, seq, payload)

    @staticmethod
    def credit(s, n):
        return Frame(CREDIT, s, 0, bytes((n >> 8, n & 0xFF)))

    @staticmethod
    def close(s):
        return Frame(CLOSE, s)

    @staticmethod
    def reset(s, reason):
        return Frame(RESET, s, 0, bytes((reason,)))

    def encode(self) -> bytes:
        return bytes((self.kind, self.stream, self.seq)) + self.payload

    @property
    def credit_amount(self) -> int:
        return (self.payload[0] << 8) | self.payload[1] if len(self.payload) == 2 else 0

    @staticmethod
    def decode(raw):
        """The frame in [raw], or None unless it is exactly one well-formed frame of a known kind.
        Never raises.
        """
        raw = bytes(raw)
        if not HEADER <= len(raw) <= MAX_FRAME or raw[0] not in KINDS:
            return None
        kind, stream, seq, payload = raw[0], raw[1], raw[2], raw[HEADER:]
        if stream == 0 or (kind != DATA and seq != 0):
            return None
        f = Frame(kind, stream, seq, payload)
        if kind == OPEN:
            return f if payload == bytes((VERSION,)) else None
        if kind in (OPENED, CLOSE):
            return f if not payload else None
        if kind == DATA:
            return f if payload else None
        if kind == CREDIT:
            return f if len(payload) == 2 and 1 <= f.credit_amount <= WINDOW else None
        return f if len(payload) == 1 else None  # RESET


_CONNECTING, _OPEN, _ENDED = 0, 1, 2


class _Lane:
    def __init__(self, ident, central, started, bridge, owner):
        self.id, self.central, self.started, self.bridge, self.owner = (
            ident,
            central,
            started,
            bridge,
            owner,
        )
        self.state = _CONNECTING
        self.upstream = None
        self.credit = WINDOW  # DATA bytes the phone still lets us send
        self.tx_seq = 0
        self.rx_seq = 0
        self.owed = 0  # bytes written upstream whose CREDIT the phone has not been given yet
        self.pending_writes = 0  # bytes accepted from the phone, not yet written upstream
        self.reading = False

    # The upstream's events (one loopback TCP connection per stream).
    def upstream_connected(self):
        self.bridge._connected(self)

    def upstream_data(self, data):
        self.bridge._received_upstream(self, data)

    def upstream_closed(self, error):
        self.bridge._closed_upstream(self, error)


class _Central:
    def __init__(self, ident):
        self.id = ident
        self.lanes = {}
        self.outbox = []
        self.blocked = False
        # failed: its queue overflowed; every stream ended, its writes are dropped until ready
        self.failed = False
        self.lost = []  # streams ended by that overflow, told RESET once the radio is ready


class Bridge:
    """The stream table and flow control. The host provides `connect_upstream(lane) -> upstream or
    None` (upstream has
    read(max), write(data, done) and close()), `notify(central, frame) -> bool` and
    `max_frame(central) -> int`.

    Bounds as on the Mac: 2 streams per phone, 8192 bytes of credit per stream and direction, 32
    queued notifications per
    phone, 5 s to reach the loopback port, 90 s per stream. [max_centrals] is 4 on the Mac; BlueZ
    sends every
    notification to every subscribed phone, so the Linux host serves one phone at a time (1)."""

    MAX_OUTBOX = 32
    CONNECT_SECONDS = 5.0
    LANE_SECONDS = 90.0
    DATA_LIMIT = MAX_OUTBOX - MAX_STREAMS

    def __init__(self, host, max_centrals=4, now=time.monotonic):
        self.host, self.max_centrals, self.now = host, max_centrals, now
        self.centrals = {}

    @property
    def active_streams(self):
        return sum(len(c.lanes) for c in self.centrals.values())

    def queued_notifications(self, central):
        c = self.centrals.get(central)
        return len(c.outbox) if c else 0

    def is_quarantined(self, central):
        c = self.centrals.get(central)
        return bool(c and c.failed)

    def is_active(self, central):
        return central in self.centrals

    # phones

    def central_connected(self, ident):
        old = self.centrals.get(ident)
        if old:
            for lane in list(old.lanes.values()):
                self._end(lane, None)
        if old is None and len(self.centrals) >= self.max_centrals:
            return
        self.centrals[ident] = _Central(ident)

    def central_gone(self, ident):
        c = self.centrals.get(ident)
        if c is None:
            return
        for lane in list(c.lanes.values()):
            self._end(lane, None)
        del self.centrals[ident]

    def received(self, central, raw):
        """One write from a phone: exactly one frame. Junk, and writes from a phone beyond
        max_centrals, are dropped.
        """
        frame = Frame.decode(raw)
        if frame is None:
            return
        if central not in self.centrals:
            self.central_connected(central)
        c = self.centrals.get(central)
        if c is None or c.failed:
            return
        if frame.kind == OPEN:
            self._open(c, central, frame.stream)
        elif frame.kind == DATA:
            self._data(c, frame)
        elif frame.kind == CREDIT:
            lane = c.lanes.get(frame.stream)
            if lane is None:
                return
            if lane.credit + frame.credit_amount > WINDOW:
                self._end(lane, Frame.reset(lane.id, R_FLOW))
                return
            lane.credit += frame.credit_amount
            self._pump(lane)
        elif frame.kind in (CLOSE, RESET):
            lane = c.lanes.get(frame.stream)
            if lane:
                self._end(lane, None)

    def ready(self, central):
        """The radio can take notifications again."""
        c = self.centrals.get(central)
        if c is None:
            return
        c.blocked = False
        if c.failed:
            c.failed = False
            lost, c.lost = c.lost, []
            for stream in lost:
                c.outbox.append(Frame.reset(stream, R_FLOW).encode())
        self._flush(c)

    def tick(self):
        """Call about once a second: ends streams that never connected or lived too long."""
        t = self.now()
        for c in list(self.centrals.values()):
            for lane in list(c.lanes.values()):
                if lane.state == _CONNECTING and t - lane.started > self.CONNECT_SECONDS:
                    self._end(lane, Frame.reset(lane.id, R_UNAVAILABLE))
                elif t - lane.started > self.LANE_SECONDS:
                    self._end(lane, Frame.reset(lane.id, R_TIMEOUT))

    # streams

    def _open(self, c, central, ident):
        if ident in c.lanes or len(c.lanes) >= MAX_STREAMS:
            self._queue(c, Frame.reset(ident, R_BUSY))
            return
        lane = _Lane(ident, central, self.now(), self, c)
        c.lanes[ident] = lane
        up = self.host.connect_upstream(lane)
        if up is None:
            del c.lanes[ident]
            self._queue(c, Frame.reset(ident, R_UNAVAILABLE))
            return
        lane.upstream = up

    def _connected(self, lane):
        c = self._owner(lane)
        if lane.state != _CONNECTING or c is None:
            return
        lane.state = _OPEN
        self._queue(c, Frame.opened(lane.id))
        self._pump(lane)

    def _data(self, c, frame):
        lane = c.lanes.get(frame.stream)
        if lane is None:
            return  # a stream already closed: stale, ignored
        if lane.state != _OPEN or frame.seq != lane.rx_seq:
            self._end(lane, Frame.reset(lane.id, R_PROTOCOL))
            return
        lane.rx_seq = (lane.rx_seq + 1) & 0xFF
        n = len(frame.payload)
        if lane.pending_writes + n > WINDOW:
            self._end(lane, Frame.reset(lane.id, R_FLOW))
            return
        lane.pending_writes += n

        def done(ok, lane=lane, n=n):
            owner = self._owner(lane)
            if lane.state != _OPEN or owner is None:
                return
            lane.pending_writes -= n
            if ok:
                lane.owed += n
                self._flush(owner)
            else:
                self._end(lane, Frame.reset(lane.id, R_UNAVAILABLE))

        lane.upstream.write(frame.payload, done)

    def _received_upstream(self, lane, data):
        """Bytes from the loopback socket, at most what was asked for: framed, counted against
        credit, queued.
        """
        lane.reading = False
        c = self._owner(lane)
        if lane.state != _OPEN or c is None:
            return
        step = max_payload(self.host.max_frame(lane.central))
        at = 0
        while at < len(data):
            n = min(len(data) - at, step, lane.credit)
            if n <= 0 or len(c.outbox) >= self.DATA_LIMIT:
                self._end(lane, Frame.reset(lane.id, R_FLOW))
                return
            c.outbox.append(Frame.data(lane.id, lane.tx_seq, data[at : at + n]).encode())
            lane.tx_seq = (lane.tx_seq + 1) & 0xFF
            lane.credit -= n
            at += n
        self._flush(c)

    def _closed_upstream(self, lane, error):
        if lane.state == _ENDED:
            return
        self._end(lane, Frame.reset(lane.id, R_UNAVAILABLE) if error else Frame.close(lane.id))

    def _pump(self, lane):
        """Asks the loopback socket for more only while the phone has granted credit and the radio
        queue has room.
        """
        c = self._owner(lane)
        if lane.state != _OPEN or lane.reading or lane.credit <= 0 or c is None or c.blocked:
            return
        if len(c.outbox) + sum(1 for ln in c.lanes.values() if ln.reading) >= self.DATA_LIMIT:
            return
        lane.reading = True
        step = max_payload(self.host.max_frame(lane.central))
        lane.upstream.read(min(lane.credit, step))

    def _end(self, lane, tell):
        if lane.state == _ENDED:
            return
        lane.state = _ENDED
        upstream, lane.upstream = lane.upstream, None
        if upstream is not None:
            upstream.close()
        c = self.centrals.get(lane.central)
        if c is None or c is not lane.owner:
            return  # a replaced phone's lane has nothing left to update
        if c.lanes.get(lane.id) is lane:
            del c.lanes[lane.id]
        if tell is not None:
            self._queue(c, tell, terminal=True)

    def _owner(self, lane):
        """The lane's phone as it is now, or None for an ended lane, a replaced phone or a replaced
        lane.
        """
        c = self.centrals.get(lane.central)
        if (
            lane.state == _ENDED
            or c is None
            or c is not lane.owner
            or c.lanes.get(lane.id) is not lane
        ):
            return None
        return c

    def _queue(self, c, frame, terminal=False):
        """The only way into a phone's outbox. Past the cap the phone is cut off rather than a frame
        dropped silently.
        """
        if c.failed:
            return
        if len(c.outbox) >= (self.MAX_OUTBOX if terminal else self.DATA_LIMIT):
            self._fail(c, frame.stream)
            return
        c.outbox.append(frame.encode())
        self._flush(c)

    def _fail(self, c, stream):
        if c.failed:
            return
        c.failed = True
        lost = sorted(c.lanes.keys())
        if stream not in lost:
            lost.append(stream)
        c.lost = lost
        for lane in list(c.lanes.values()):
            self._end(lane, None)
        c.outbox.clear()

    def _flush(self, c):
        if c.blocked:
            return
        for lane in list(c.lanes.values()):
            if lane.owed > 0:
                n = min(lane.owed, WINDOW)
                if self.host.notify(c.id, Frame.credit(lane.id, n).encode()):
                    lane.owed -= n
                else:
                    c.blocked = True
                    return
        while c.outbox:
            if self.host.notify(c.id, c.outbox[0]):
                c.outbox.pop(0)
            else:
                c.blocked = True
                return
        for lane in list(c.lanes.values()):
            self._pump(lane)
