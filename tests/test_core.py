import pytest

from phonemesh.languages import LANGUAGES, resolve_language
from phonemesh.message import MAX_PAYLOAD_BYTES, Message
from phonemesh.service import send_message
from phonemesh.transport import SimulatedMesh

SAMPLES = {
    "en-US": "Meet me at the library.",
    "en-GB": "Meet me at the library.",
    "fr": "Rendez-vous à la bibliothèque.",
    "es": "Nos vemos en la biblioteca.",
    "hi": "हम पुस्तकालय में मिलेंगे।",
    "it": "Ci vediamo in biblioteca.",
    "ja": "図書館で会いましょう。",
    "pt-BR": "Encontramo-nos na biblioteca.",
    "zh": "我们在图书馆见面。",
}


@pytest.mark.parametrize("language", LANGUAGES)
def test_all_profiles_round_trip(language):
    message = Message(SAMPLES[language.tag], language.tag)
    payload = message.encode()
    assert isinstance(payload, str)
    assert len(payload.encode("utf-8")) <= MAX_PAYLOAD_BYTES
    assert Message.decode(payload.encode()) == message
    mesh = SimulatedMesh(seed=42)
    sender, receiver = mesh.endpoint("sender"), mesh.endpoint("receiver")
    receipt = send_message(message, sender, "receiver", confirmed=True)
    incoming = receiver.receive(timeout=0.1)
    assert incoming.sender == "sender"
    assert incoming.payload == payload
    assert receipt.status == "submitted"
    assert Message.decode(incoming.payload) == message


@pytest.mark.parametrize(
    "alias,canonical",
    [
        ("EN", "en-US"),
        ("en_gb", "en-GB"),
        ("fr-FR", "fr"),
        ("pt", "pt-BR"),
        ("zh-CN", "zh"),
        ("es", "es"),
    ],
)
def test_language_aliases(alias, canonical):
    assert resolve_language(alias).tag == canonical


@pytest.mark.parametrize("unsupported", ["de", "blo", "ru", "auto", "xx", ""])
def test_unsupported_language_rejected(unsupported):
    with pytest.raises(ValueError, match="Unsupported"):
        resolve_language(unsupported)


def test_exact_utf8_budget_not_character_count():
    prefix = len(b"[pm1:fr] ")
    text = "a" * (MAX_PAYLOAD_BYTES - prefix)
    assert len(Message(text, "fr").encode().encode()) == MAX_PAYLOAD_BYTES
    with pytest.raises(ValueError, match="bytes"):
        Message(text + "a", "fr").encode()
    with pytest.raises(ValueError, match="bytes"):
        Message("é" * 100, "fr").encode()


@pytest.mark.parametrize("text", ["", "  ", "hello\x00world", "hello\x1b[31m", "x\rbye"])
def test_invalid_text_is_rejected(text):
    with pytest.raises(ValueError):
        Message(text, "fr").encode()


@pytest.mark.parametrize(
    "payload",
    [
        b"\xff",
        b"RIFF\x00\x00\x00\x00WAVE",
        "[pm2:fr] hi",
        "[pm1:blo] hi",
        "[pm1:fr] ",
        "[pm1:fr]hello",
        "[pm1] hi",
        "[pm1:fr] " + "a" * 201,
    ],
)
def test_malformed_wire_rejected(payload):
    with pytest.raises(ValueError):
        Message.decode(payload, default_language="en")


def test_plain_stock_text_requires_explicit_receiver_language():
    with pytest.raises(ValueError, match="language"):
        Message.decode("Bonjour")
    assert Message.decode("Bonjour", default_language="fr") == Message("Bonjour", "fr")


def test_no_confirmation_means_no_send():
    mesh = SimulatedMesh()
    sender, receiver = mesh.endpoint("a"), mesh.endpoint("b")
    with pytest.raises(PermissionError):
        send_message(Message("Do not send", "en"), sender, "b")
    assert receiver.receive(timeout=0) is None


def test_loss_is_not_reported_as_delivery():
    mesh = SimulatedMesh(loss=1, seed=1)
    sender, receiver = mesh.endpoint("a"), mesh.endpoint("b")
    receipt = send_message(Message("Lost", "en"), sender, "b", confirmed=True)
    assert receipt.status == "submitted"
    assert receiver.receive(timeout=0.01) is None


def test_latency_and_broadcast():
    mesh = SimulatedMesh(latency=0.02)
    sender, receiver, third = [mesh.endpoint(x) for x in ("a", "b", "c")]
    sender.send(Message("Hi", "en").encode(), "^all")
    assert receiver.receive(timeout=0) is None
    assert receiver.receive(timeout=0.2) is not None
    assert third.receive(timeout=0.2) is not None
    assert sender.receive(timeout=0) is None


def test_queue_is_bounded_and_close_is_terminal():
    mesh = SimulatedMesh(inbox_size=1)
    sender, receiver = mesh.endpoint("a"), mesh.endpoint("b")
    sender.send(Message("first", "fr").encode(), "b")
    sender.send(Message("second", "fr").encode(), "b")
    assert receiver.dropped == 1
    assert Message.decode(receiver.receive(timeout=0).payload).text == "first"
    receiver.close()
    with pytest.raises(RuntimeError, match="closed"):
        receiver.receive(timeout=0)


@pytest.mark.parametrize("kwargs", [{"loss": -1}, {"loss": 2}, {"latency": -1}, {"inbox_size": 0}])
def test_bad_simulator_configuration(kwargs):
    with pytest.raises(ValueError):
        SimulatedMesh(**kwargs)
