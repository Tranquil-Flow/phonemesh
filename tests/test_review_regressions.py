"""Regression coverage for reviewed remote-input and preview boundaries."""

import pytest

from phonemesh.message import MAX_PAYLOAD_BYTES, Message, validate_payload
from phonemesh.service import send_message
from phonemesh.transport import SimulatedMesh


@pytest.mark.parametrize("alias", ["en", "pt", "EN", "en-us", "fr-FR", "zh-CN"])
def test_wire_tags_require_canonical_spelling(alias):
    # User-facing selectors can use aliases; the wire format must be unambiguous.
    prefix = f"[pm1:{alias}] "
    payload = prefix + "a" * (MAX_PAYLOAD_BYTES - len(prefix.encode()))
    assert len(payload.encode()) == MAX_PAYLOAD_BYTES
    with pytest.raises(ValueError, match="canonical"):
        Message.decode(payload)


@pytest.mark.parametrize("tag", ["en-US", "en-GB", "pt-BR", "fr", "zh"])
def test_full_budget_canonical_tags_reencode_identically(tag):
    prefix = f"[pm1:{tag}] "
    payload = prefix + "a" * (MAX_PAYLOAD_BYTES - len(prefix.encode()))
    assert Message.decode(payload).encode() == payload


@pytest.mark.parametrize(
    "codepoint",
    [
        0x00AD,
        0x061C,
        0x200B,
        0x200E,
        0x200F,
        *range(0x202A, 0x202F),
        *range(0x2066, 0x2070),
        0xFEFF,
        0xE0000,
        0xE0041,
        0xE007F,
        0x2028,
        0x2029,
    ],
)
def test_display_controls_rejected_at_both_wire_and_draft_boundaries(codepoint):
    text = "Bonjour" + chr(codepoint) + " à tous"
    with pytest.raises(ValueError, match="Control"):
        Message(text, "fr")
    with pytest.raises(ValueError, match="Control"):
        Message.decode(f"[pm1:fr] {text}")
    with pytest.raises(ValueError, match="Control"):
        validate_payload(text)


@pytest.mark.parametrize("text", ["\u200c", "\u200d", " \u200c\u200d ", "\u2060", "\u0301"])
def test_invisible_only_text_is_not_a_message(text):
    with pytest.raises(ValueError, match="visible"):
        Message(text, "hi")
    # The visible wire header must not disguise an invisible-only body.
    with pytest.raises(ValueError, match="visible"):
        Message.decode(f"[pm1:hi] {text}")


@pytest.mark.parametrize("joiner", ["\u200c", "\u200d"])
def test_hindi_joiners_preserved_through_confirmed_mesh_send(joiner):
    text = f"क्{joiner}ष"
    message = Message(text, "hi")
    mesh = SimulatedMesh()
    sender, receiver = mesh.endpoint("a"), mesh.endpoint("b")
    send_message(message, sender, "b", confirmed=True)
    received = Message.decode(receiver.receive().payload)
    assert received.text == text
    assert received.encode() == message.encode()
