import pytest
from meshtastic.mesh_interface import MeshInterface
from meshtastic.protobuf import mesh_pb2, portnums_pb2
from pubsub import pub

from phonemesh.message import Message
from phonemesh.transport import MeshtasticTransport


class CaptureInterface(MeshInterface):
    """Real SDK/protobuf, replacing only the device-write boundary. No hardware."""

    def __init__(self):
        super().__init__()
        self.sent = []
        self.nodesByNum = {}
        self.was_closed = False

    def _sendToRadio(self, packet):
        copied = mesh_pb2.ToRadio()
        copied.ParseFromString(packet.SerializeToString())
        self.sent.append(copied)

    def close(self):
        self.was_closed = True


@pytest.fixture
def radio():
    sdk = CaptureInterface()
    transport = MeshtasticTransport(sdk, channel=2)
    yield transport, sdk
    transport.close()


@pytest.mark.parametrize("recipient,ack", [("!12345678", True), ("^all", False)])
def test_real_sdk_builds_text_packet(radio, recipient, ack):
    transport, sdk = radio
    text = Message("Bonjour à tous", "fr").encode()
    receipt = transport.send(text, recipient)
    packet = sdk.sent[0].packet
    assert packet.decoded.portnum == portnums_pb2.TEXT_MESSAGE_APP
    assert packet.decoded.payload == text.encode()
    assert packet.channel == 2
    assert packet.want_ack == ack
    assert packet.to == (0x12345678 if ack else 0xFFFFFFFF)
    assert receipt.packet_id == packet.id
    assert receipt.status == "submitted"


def packet(**overrides):
    return {
        "from": 42,
        "id": 7,
        "channel": 2,
        "decoded": {"portnum": "TEXT_MESSAGE_APP", "payload": b"[pm1:fr] Bonjour"},
        **overrides,
    }


def test_receive_filters_and_deduplicates(radio):
    transport, sdk = radio
    pub.sendMessage("meshtastic.receive.text", packet=packet(), interface=object())
    pub.sendMessage("meshtastic.receive.text", packet=packet(channel=1), interface=sdk)
    assert transport.receive() is None
    for _ in range(2):
        pub.sendMessage("meshtastic.receive.text", packet=packet(), interface=sdk)
    item = transport.receive()
    assert item.payload == "[pm1:fr] Bonjour"
    assert item.sender == "!0000002a"
    assert transport.receive() is None


def test_real_sdk_receive_decode_path(radio):
    transport, sdk = radio
    protobuf = mesh_pb2.MeshPacket(id=51, channel=2)
    setattr(protobuf, "from", 42)
    protobuf.decoded.portnum = portnums_pb2.TEXT_MESSAGE_APP
    protobuf.decoded.payload = "[pm1:zh] 你好".encode()
    sdk._handlePacketFromRadio(protobuf, hack=True)
    item = transport.receive(timeout=1)
    assert item is not None
    assert item.payload == "[pm1:zh] 你好"


@pytest.mark.parametrize("bad", [b"\xff", b"x" * 201, b"RIFF\0WAVE", None])
def test_bad_incoming_payload_rejected(radio, bad):
    transport, sdk = radio
    p = packet(decoded={"portnum": "TEXT_MESSAGE_APP", "payload": bad})
    pub.sendMessage("meshtastic.receive.text", packet=p, interface=sdk)
    assert transport.receive() is None


@pytest.mark.parametrize("destination", ["", "all", "!ffffffff", "12345678", "!123", "localhost"])
def test_invalid_destination_never_reaches_sdk(radio, destination):
    transport, sdk = radio
    with pytest.raises(ValueError):
        transport.send("Bonjour", destination)
    assert not sdk.sent


def test_closed_radio_and_oversized_send(radio):
    transport, sdk = radio
    with pytest.raises(ValueError):
        transport.send("é" * 101, "^all")
    assert not sdk.sent
    transport.close()
    assert sdk.was_closed
    with pytest.raises(RuntimeError, match="closed"):
        transport.send("hello", "^all")
    pub.sendMessage("meshtastic.receive.text", packet=packet(), interface=sdk)
    transport.close()
