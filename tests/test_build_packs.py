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


CORE = {"phontab", "phonindex", "phondata", "intonations"}
CATALOG_VOICES = ("fr", "en")


def test_voice_pack_keeps_the_union_of_catalog_languages():
    union = CORE | {"fr_dict", "lang/roa/fr", "en_dict", "lang/gmw/en"}
    assert bp.espeak_keep(FULL, "fr", CATALOG_VOICES) == union
    assert bp.espeak_keep(FULL, "en", CATALOG_VOICES) == union


def test_en_us_voice_matches_lang_gmw_en_us_case_insensitively():
    assert bp.espeak_keep(FULL, "en-us", CATALOG_VOICES) == CORE | {
        "fr_dict",
        "lang/roa/fr",
        "en_dict",
        "lang/gmw/en",
        "lang/gmw/en-US",
    }


def test_missing_file_of_another_catalog_language_fails_the_build():
    with pytest.raises(ValueError, match="en_dict"):
        bp.espeak_keep(FULL - {"en_dict"}, "fr", CATALOG_VOICES)
    with pytest.raises(ValueError, match="lang/.*/en"):
        bp.espeak_keep(FULL - {"lang/gmw/en"}, "fr", CATALOG_VOICES)


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


def _voice_tar(tmp_path):
    import io
    import json
    import tarfile

    path = tmp_path / "v.tar.bz2"
    members = {
        "v/m.onnx": b"model",
        "v/m.onnx.json": json.dumps({"espeak": {"voice": "fr"}}).encode(),
        "v/tokens.txt": b"t",
    }
    members.update({"v/espeak-ng-data/" + n: b"x" for n in FULL})
    with tarfile.open(path, "w:bz2") as archive:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return path


def test_voice_files_trims_by_default_and_keeps_everything_for_full(tmp_path):
    tar = _voice_tar(tmp_path)
    trimmed, voice = bp.voice_files(tar, CATALOG_VOICES)
    assert voice == "fr"
    kept = {n.removeprefix("tts/espeak-ng-data/") for n in trimmed if "espeak-ng-data" in n}
    assert kept == CORE | {"fr_dict", "lang/roa/fr", "en_dict", "lang/gmw/en"}
    full, _ = bp.voice_files(tar, trim=False)
    assert {n.removeprefix("tts/espeak-ng-data/") for n in full if "espeak-ng-data" in n} == FULL
