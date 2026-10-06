"""A bounded, human-readable text-only radio contract."""

import re
import unicodedata
from dataclasses import dataclass

from .languages import resolve_language

MAX_PAYLOAD_BYTES = 200
_TAG = re.compile(r"\[pm1:([A-Za-z]{2}(?:-[A-Za-z]{2})?)\] (.+)", re.DOTALL)
_DISPLAY_CONTROLS = frozenset(
    [0x00AD, 0x061C, 0x200B, 0x200E, 0x200F, 0xFEFF]
    + list(range(0x202A, 0x202F))
    + list(range(0x2066, 0x2070))
    + list(range(0xE0000, 0xE0080))
)


def validate_text(payload: str) -> None:
    if not isinstance(payload, str):
        raise ValueError("Radio accepts text, never audio bytes")
    if not payload.strip():
        raise ValueError("Message must not be empty")
    if any(
        unicodedata.category(c) in {"Cc", "Cs", "Zl", "Zp"} or ord(c) in _DISPLAY_CONTROLS
        for c in payload
    ):
        raise ValueError("Control characters are not allowed")
    # Keep ZWJ/ZWNJ inside Indic words, but reject format/combining-only bodies.
    if not any(
        not c.isspace() and unicodedata.category(c) not in {"Cf", "Mn", "Me"} for c in payload
    ):
        raise ValueError("Message must contain visible text")


def validate_payload(payload: str) -> bytes:
    validate_text(payload)
    data = payload.encode("utf-8")
    if len(data) > MAX_PAYLOAD_BYTES:
        raise ValueError(f"Message is {len(data)} bytes; limit is {MAX_PAYLOAD_BYTES} bytes")
    return data


@dataclass(frozen=True)
class Message:
    text: str
    language: str

    def __post_init__(self):
        object.__setattr__(self, "language", resolve_language(self.language).tag)
        # Drafts can exceed the radio cap so the user can edit, not lose, a transcript.
        validate_text(self.text)

    def encode(self) -> str:
        payload = f"[pm1:{self.language}] {self.text}"
        validate_payload(payload)
        return payload

    @classmethod
    def decode(cls, payload: str | bytes, *, default_language: str | None = None):
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8", errors="strict")
        validate_payload(payload)
        match = _TAG.fullmatch(payload)
        if match:
            language = resolve_language(match[1])
            if match[1] != language.tag:
                raise ValueError(f"Wire tag must use canonical language code {language.tag!r}")
            return cls(match[2], match[1])
        if payload.startswith("[pm"):
            raise ValueError("Malformed or unsupported Phonemesh tag")
        if default_language is None:
            raise ValueError("Plain text needs an explicit receiver language")
        return cls(payload, default_language)
