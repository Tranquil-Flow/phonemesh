import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/build_packs.py"
spec = importlib.util.spec_from_file_location("build_packs", SCRIPT)
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)

FULL = {
    "phontab",
    "phonindex",
    "phondata",
    "intonations",
    "fr_dict",
    "en_dict",
    "ru_dict",
    "cmn_dict",
    "lang/roa/fr",
    "lang/roa/fr-BE",
    "lang/gmw/en",
    "lang/gmw/en-US",
    "lang/zle/ru",
    "voices/!v/Gene",
}


def test_french_keeps_core_dict_and_voice_file_only():
    assert bp.espeak_keep(FULL, "fr") == {
        "phontab",
        "phonindex",
        "phondata",
        "intonations",
        "fr_dict",
        "lang/roa/fr",
    }


def test_english_voice_en_keeps_lang_gmw_en_not_variants():
    assert bp.espeak_keep(FULL, "en") == {
        "phontab",
        "phonindex",
        "phondata",
        "intonations",
        "en_dict",
        "lang/gmw/en",
    }


def test_missing_dictionary_fails_the_build():
    with pytest.raises(ValueError, match="fr_dict"):
        bp.espeak_keep(FULL - {"fr_dict"}, "fr")


def test_missing_voice_file_fails_the_build():
    with pytest.raises(ValueError, match="lang/.*/fr"):
        bp.espeak_keep(FULL - {"lang/roa/fr"}, "fr")


def test_manifest_lists_every_file_but_itself_sorted():
    text = bp.manifest({"b": b"2", "a": b"1", "SHA256SUMS": b"x"}).decode()
    lines = text.splitlines()
    assert [line.split("  ")[1] for line in lines] == ["a", "b"]
    assert all(len(line.split("  ")[0]) == 64 for line in lines)


def test_pack_properties_engine_and_voice():
    engine = bp.pack_properties(
        kind="engine",
        id="engine-whisper-tiny",
        version=1,
        label="Whisper Tiny",
        recognizerModel="whisper-tiny",
    ).decode()
    assert engine.splitlines()[0] == "format=2" and "kind=engine" in engine
    voice = bp.pack_properties(
        kind="voice",
        id="voice-fr-siwis",
        version=1,
        label="Français",
        languageTag="fr",
        whisperLanguage="fr",
    ).decode()
    assert "languageTag=fr" in voice and "recognizerModel" not in voice
