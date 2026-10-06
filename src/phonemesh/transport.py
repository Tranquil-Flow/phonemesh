"""Text-only simulated and Meshtastic transports; no automatic device discovery."""

import heapq
import math
import random
import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from itertools import count
from typing import Protocol

from .message import validate_payload

BROADCAST = "^all"


@dataclass(frozen=True)
class Receipt:
    packet_id: int
    status: str = "submitted"  # Never a delivery/read/played acknowledgement.


@dataclass(frozen=True)
class Incoming:
    payload: str
    sender: str
    packet_id: int
    channel: int = 0


class Transport(Protocol):
    def send(self, payload: str, destination: str) -> Receipt: ...
    def receive(self, timeout: float = 0) -> Incoming | None: ...
    def close(self) -> None: ...


def _seconds(value: float) -> float:
    if not math.isfinite(value) or value < 0:
        raise ValueError("Time must be finite and nonnegative")
    return value


class _Inbox:
    def __init__(self, size: int):
        if type(size) is not int or size < 1:
            raise ValueError("Inbox size must be a positive integer")
        self.size = size
        self.closed = False
        self.dropped = 0
        self._items = []
        self._order = count()
        self._condition = threading.Condition()

    def push(self, item: Incoming, latency: float = 0):
        with self._condition:
            if self.closed:
                return
            if len(self._items) >= self.size:
                self.dropped += 1
                return
            heapq.heappush(self._items, (time.monotonic() + latency, next(self._order), item))
            self._condition.notify()

    def receive(self, timeout: float = 0) -> Incoming | None:
        deadline = time.monotonic() + _seconds(timeout)
        with self._condition:
            while True:
                if self.closed:
                    raise RuntimeError("Transport is closed")
                now = time.monotonic()
                if self._items and self._items[0][0] <= now:
                    return heapq.heappop(self._items)[2]
                if now >= deadline:
                    return None
                wait = deadline - now
                if self._items:
                    wait = min(wait, self._items[0][0] - now)
                self._condition.wait(wait)

    def close(self):
        with self._condition:
            self.closed = True
            self._items.clear()
            self._condition.notify_all()


class SimulatedMesh:
    def __init__(self, *, loss=0.0, latency=0.0, seed=0, inbox_size=64):
        if not 0 <= loss <= 1 or not math.isfinite(loss):
            raise ValueError("Loss must be between 0 and 1")
        _Inbox(inbox_size)
        self.loss, self.latency, self.inbox_size = loss, _seconds(latency), inbox_size
        self._rng = random.Random(seed)
        self._endpoints = {}
        self._ids = count(1)
        self._lock = threading.Lock()

    def endpoint(self, name: str):
        with self._lock:
            if not name or name == BROADCAST or name in self._endpoints:
                raise ValueError("Endpoint needs a unique, nonempty name")
            endpoint = _SimulatedEndpoint(self, name)
            self._endpoints[name] = endpoint
            return endpoint

    def _send(self, sender, payload, destination):
        validate_payload(payload)
        with self._lock:
            if sender.closed:
                raise RuntimeError("Transport is closed")
            if destination != BROADCAST and destination not in self._endpoints:
                raise ValueError("Unknown simulated recipient")
            packet_id = next(self._ids)
            for name, target in self._endpoints.items():
                if name == sender.name or (destination != BROADCAST and destination != name):
                    continue
                if self._rng.random() >= self.loss:
                    target.push(Incoming(payload, sender.name, packet_id), self.latency)
            return Receipt(packet_id)


class _SimulatedEndpoint(_Inbox):
    def __init__(self, mesh, name):
        super().__init__(mesh.inbox_size)
        self.mesh, self.name = mesh, name

    def send(self, payload, destination):
        return self.mesh._send(self, payload, destination)


def validate_channel(channel):
    if type(channel) is not int or not 0 <= channel <= 7:
        raise ValueError("Channel index must be 0..7")


def validate_destination(destination):
    if destination != BROADCAST and not re.fullmatch(r"![0-9a-fA-F]{8}", destination):
        raise ValueError("Recipient must be ! followed by 8 hex digits, or ^all")
    if destination.lower() == "!ffffffff":
        raise ValueError("Use ^all explicitly for broadcast")


class MeshtasticTransport(_Inbox):
    """Owns the supplied SDK interface until close; receives only its selected channel."""

    def __init__(self, interface, *, channel=0, inbox_size=64):
        validate_channel(channel)
        super().__init__(inbox_size)
        from pubsub import pub

        self.interface, self.channel = interface, channel
        self._seen = OrderedDict()
        self._pub = pub
        pub.subscribe(self._on_receive, "meshtastic.receive.text")

    @classmethod
    def serial(cls, device: str, *, channel: int, timeout: int = 15):
        validate_channel(channel)
        if not device or not device.strip():
            raise ValueError("Explicit serial device is required; no auto-discovery")
        if type(timeout) is not int or not 1 <= timeout <= 60:
            raise ValueError("Serial connection timeout must be 1..60 seconds")
        from meshtastic.serial_interface import SerialInterface

        interface = SerialInterface(devPath=device, timeout=timeout)
        try:
            return cls(interface, channel=channel)
        except Exception:
            interface.close()
            raise

    def send(self, payload: str, destination: str) -> Receipt:
        validate_payload(payload)
        validate_destination(destination)
        if self.closed:
            raise RuntimeError("Transport is closed")
        packet = self.interface.sendText(
            payload,
            destinationId=destination,
            channelIndex=self.channel,
            wantAck=destination != BROADCAST,
        )
        return Receipt(packet.id)

    def _on_receive(self, packet, interface):
        if self.closed or interface is not self.interface or not isinstance(packet, dict):
            return
        if packet.get("channel", 0) != self.channel:
            return
        decoded = packet.get("decoded")
        if not isinstance(decoded, dict) or decoded.get("portnum") not in (1, "TEXT_MESSAGE_APP"):
            return
        try:
            payload = decoded.get("payload", decoded.get("text"))
            if isinstance(payload, bytes):
                payload = payload.decode("utf-8", errors="strict")
            validate_payload(payload)
            sender = packet.get("from")
            packet_id = packet.get("id")
            if type(sender) is not int or not 0 <= sender <= 0xFFFFFFFF:
                return
            if type(packet_id) is not int or not 0 < packet_id <= 0xFFFFFFFF:
                return
            own = getattr(self.interface, "myInfo", None)
            if own is not None and sender == own.my_node_num:
                return
            item = Incoming(payload, f"!{sender:08x}", packet_id, self.channel)
        except (ValueError, TypeError):
            return
        with self._condition:
            key = (sender, packet_id)
            if key in self._seen:
                return
            self._seen[key] = None
            if len(self._seen) > 256:
                self._seen.popitem(last=False)
            self.push(item)

    def close(self):
        if self.closed:
            return
        super().close()
        self._pub.unsubscribe(self._on_receive, "meshtastic.receive.text")
        self.interface.close()
