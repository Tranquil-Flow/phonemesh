import sys
import wave
from types import SimpleNamespace

import pytest

from phonemesh.message import Message
from phonemesh.speech import KokoroTTS, WhisperSTT


def wav_file(path, seconds=1):
    with wave.open(str(path), "wb") as writer:
        writer.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        writer.writeframes(b"\0\0" * (16000 * seconds))
    return path


@pytest.fixture
def stt_assets(tmp_path):
    root = tmp_path / "whisper"
    root.mkdir()
    for name in ("model.bin", "config.json", "tokenizer.json", "preprocessor_config.json"):
        (root / name).write_text("fixture, not model weights")
    return root


def test_transcription_uses_local_model_original_language_and_vad(
    tmp_path, stt_assets, monkeypatch
):
    calls = []

    class Model:
        supported_languages = ["en", "fr"]

        def __init__(self, path, *, device, compute_type, cpu_threads, local_files_only):
            calls.append((path, device, compute_type, cpu_threads, local_files_only))

        def transcribe(self, path, *, language, task, vad_filter, beam_size):
            calls.append((path, language, task, vad_filter, beam_size))
            return iter(
                [SimpleNamespace(text=" Bonjour "), SimpleNamespace(text=" le monde.")]
            ), None

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model))
    audio = wav_file(tmp_path / "input.wav")
    result = WhisperSTT(stt_assets).transcribe(audio, "fr")
    assert result == Message("Bonjour le monde.", "fr")
    assert calls[0][1:] == ("cpu", "int8", 2, True)
    assert calls[1][1:] == ("fr", "transcribe", True, 5)


def test_english_only_weights_reject_french(tmp_path, stt_assets, monkeypatch):
    class Model:
        supported_languages = ["en"]

        def __init__(self, *args, **kwargs):
            pass

        def transcribe(self, *args, **kwargs):
            pytest.fail("must reject before inference")

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model))
    with pytest.raises(ValueError, match="model"):
        WhisperSTT(stt_assets).transcribe(wav_file(tmp_path / "in.wav"), "fr")


def test_missing_stt_assets_fail_before_import_or_download(tmp_path, monkeypatch):
    monkeypatch.setitem(sys.modules, "faster_whisper", None)
    with pytest.raises(FileNotFoundError):
        WhisperSTT(tmp_path / "missing").transcribe(wav_file(tmp_path / "in.wav"), "fr")


def test_long_audio_rejected_before_loading(tmp_path, stt_assets, monkeypatch):
    monkeypatch.setitem(sys.modules, "faster_whisper", None)
    with pytest.raises(ValueError, match="60"):
        WhisperSTT(stt_assets).transcribe(wav_file(tmp_path / "in.wav", seconds=61), "fr")


def test_empty_transcription_is_not_a_message(tmp_path, stt_assets, monkeypatch):
    class Model:
        supported_languages = ["fr"]

        def __init__(self, *args, **kwargs):
            pass

        def transcribe(self, *args, **kwargs):
            return iter([]), None

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model))
    with pytest.raises(ValueError, match="speech"):
        WhisperSTT(stt_assets).transcribe(wav_file(tmp_path / "in.wav"), "fr")


def test_missing_tts_assets_fail_before_import(tmp_path, monkeypatch):
    monkeypatch.setitem(sys.modules, "kokoro", None)
    with pytest.raises(FileNotFoundError):
        KokoroTTS(tmp_path / "missing").synthesize(Message("Bonjour", "fr"), tmp_path / "out.wav")
    assert not (tmp_path / "out.wav").exists()


class AudioTensor:
    def __init__(self, samples):
        self.samples = samples

    def detach(self):
        return self

    def cpu(self):
        return self

    def tolist(self):
        return self.samples


@pytest.mark.parametrize("tag", ["en-US", "en-GB", "fr", "es", "hi", "it", "ja", "pt-BR", "zh"])
def test_tts_writes_local_pcm_wav_with_profile_voice(tmp_path, tag):
    from phonemesh.languages import resolve_language

    profile = resolve_language(tag)
    voices = tmp_path / "voices"
    voices.mkdir()
    voice = voices / f"{profile.voice}.pt"
    voice.write_text("fixture, not model weights")
    calls = []

    class Pipeline:
        def g2p(self, text):
            calls.append(text)
            return "abc", None

        def generate_from_tokens(self, phones, *, voice):
            calls.append((phones, voice))
            yield SimpleNamespace(audio=AudioTensor([0.2, -0.2] * 120))

    engine = KokoroTTS(tmp_path)
    engine._pipelines[tag] = Pipeline()
    output = tmp_path / "out.wav"
    engine.synthesize(Message("Example", tag), output)
    with wave.open(str(output), "rb") as audio:
        assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 24000)
        assert audio.getnframes() == 240
        assert any(audio.readframes(240))
    assert calls == ["Example", ("abc", str(voice))]
    with pytest.raises(FileExistsError):
        engine.synthesize(Message("Example", tag), output)


@pytest.mark.parametrize("samples", [[], [0.0, 0.0], [float("nan")], [float("inf")]])
def test_bad_tts_output_creates_no_file(tmp_path, samples):
    voice = tmp_path / "voices" / "ff_siwis.pt"
    voice.parent.mkdir()
    voice.write_text("fixture")

    class Pipeline:
        def g2p(self, text):
            return "abc", None

        def generate_from_tokens(self, phones, *, voice):
            yield SimpleNamespace(audio=AudioTensor(samples))

    engine = KokoroTTS(tmp_path)
    engine._pipelines["fr"] = Pipeline()
    with pytest.raises(ValueError):
        engine.synthesize(Message("Bonjour", "fr"), tmp_path / "out.wav")
    assert not (tmp_path / "out.wav").exists()


def test_overlong_phone_sequence_rejected_not_truncated(tmp_path):
    voice = tmp_path / "voices" / "ff_siwis.pt"
    voice.parent.mkdir()
    voice.write_text("fixture")

    class Pipeline:
        def g2p(self, text):
            return "a" * 511, None

        def generate_from_tokens(self, *args, **kwargs):
            pytest.fail("must not silently truncate and synthesize")

    engine = KokoroTTS(tmp_path)
    engine._pipelines["fr"] = Pipeline()
    with pytest.raises(ValueError, match="510"):
        engine.synthesize(Message("Bonjour", "fr"), tmp_path / "out.wav")


def test_long_transcript_remains_available_for_editing(tmp_path, stt_assets, monkeypatch):
    text = "Bonjour " * 50

    class Model:
        supported_languages = ["fr"]

        def __init__(self, *args, **kwargs):
            pass

        def transcribe(self, *args, **kwargs):
            return iter([SimpleNamespace(text=text)]), None

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Model))
    result = WhisperSTT(stt_assets).transcribe(wav_file(tmp_path / "input.wav"), "fr")
    assert result.text == text.strip()
    with pytest.raises(ValueError, match="bytes"):
        result.encode()
