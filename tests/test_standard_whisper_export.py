"""The published faster-whisper export omits an optional preprocessing file."""

import sys
import wave
from types import SimpleNamespace

from phonemesh.speech import WhisperSTT


def test_standard_export_without_optional_preprocessor_config(tmp_path, monkeypatch):
    model = tmp_path / "model"
    model.mkdir()
    for filename in ("model.bin", "config.json", "tokenizer.json", "vocabulary.txt"):
        (model / filename).write_text("fixture")
    audio = tmp_path / "input.wav"
    with wave.open(str(audio), "wb") as out:
        out.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        out.writeframes(b"\0\0" * 16000)

    class Model:
        supported_languages = ["en"]

        def __init__(self, path, **kwargs):
            assert path == str(model)
            assert kwargs["local_files_only"] is True

        def transcribe(self, *args, **kwargs):
            return iter([SimpleNamespace(text="Test message.")]), None

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model))
    result = WhisperSTT(model).transcribe(audio, "en")
    assert result.text == "Test message."
