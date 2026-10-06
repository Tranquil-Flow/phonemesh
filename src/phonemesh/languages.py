"""Upstream Whisper/Kokoro capability intersection (not a quality claim)."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Language:
    tag: str
    name: str
    whisper: str
    kokoro: str
    voice: str


LANGUAGES = (
    Language("en-US", "English (US)", "en", "a", "af_heart"),
    Language("en-GB", "English (UK)", "en", "b", "bf_emma"),
    Language("es", "Spanish", "es", "e", "ef_dora"),
    Language("fr", "French", "fr", "f", "ff_siwis"),
    Language("hi", "Hindi", "hi", "h", "hf_alpha"),
    Language("it", "Italian", "it", "i", "if_sara"),
    Language("ja", "Japanese", "ja", "j", "jf_alpha"),
    Language("pt-BR", "Portuguese (Brazil)", "pt", "p", "pf_dora"),
    Language("zh", "Mandarin Chinese", "zh", "z", "zf_xiaobei"),
)


def resolve_language(code: str) -> Language:
    key = code.strip().lower().replace("_", "-")
    aliases = {"en": "en-us", "fr-fr": "fr", "pt": "pt-br", "zh-cn": "zh"}
    key = aliases.get(key, key)
    for language in LANGUAGES:
        if language.tag.lower() == key:
            return language
    raise ValueError(f"Unsupported speech language: {code!r}")
