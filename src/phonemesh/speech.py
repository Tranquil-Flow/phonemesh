"""Lazy local-file-only CPU speech adapters. Model provisioning is a separate step."""

import importlib.util
import math
import os
import struct
import sys
import wave
from pathlib import Path

from .languages import resolve_language
from .message import Message, validate_payload


def _offline():
    # Set before lazy imports. Also handle a caller that imported HF earlier.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    constants = sys.modules.get("huggingface_hub.constants")
    if constants is not None:
        constants.HF_HUB_OFFLINE = True


def _file(path):
    path = Path(path).expanduser().resolve()
    if not path.is_file() or path.stat().st_size == 0:
        raise FileNotFoundError(f"Provision this local asset first: {path}")
    return path


def _check_audio(path):
    path = _file(path)
    with wave.open(str(path), "rb") as audio:
        if audio.getnchannels() not in (1, 2) or audio.getsampwidth() != 2:
            raise ValueError("Input must be mono/stereo 16-bit PCM WAV")
        if not 8000 <= audio.getframerate() <= 48000:
            raise ValueError("Input WAV sample rate must be 8–48 kHz")
        duration = audio.getnframes() / audio.getframerate()
        if not 0 < duration <= 60:
            raise ValueError("Input WAV must be nonempty and at most 60 seconds")
        expected = audio.getnframes() * audio.getnchannels() * audio.getsampwidth()
        if len(audio.readframes(audio.getnframes())) != expected:
            raise ValueError("Truncated WAV input")
    return path


class WhisperSTT:
    def __init__(self, model_dir):
        self.model_dir = Path(model_dir).expanduser().resolve()
        self._model = None

    def transcribe(self, audio_file, language) -> Message:
        language = resolve_language(language)
        audio_file = _check_audio(audio_file)
        # Standard faster-whisper exports omit preprocessor_config.json; the
        # runtime uses its built-in defaults without fetching anything for it.
        for name in ("model.bin", "config.json", "tokenizer.json"):
            _file(self.model_dir / name)
        _offline()
        if self._model is None:
            from faster_whisper import WhisperModel

            self._model = WhisperModel(
                str(self.model_dir),
                device="cpu",
                compute_type="int8",
                cpu_threads=2,
                local_files_only=True,
            )
        if language.whisper not in self._model.supported_languages:
            raise ValueError(f"Installed Whisper model does not support {language.whisper}")
        segments, _ = self._model.transcribe(
            str(audio_file),
            language=language.whisper,
            task="transcribe",
            vad_filter=True,
            beam_size=5,
        )
        text = " ".join(segment.text.strip() for segment in segments).strip()
        if not text:
            raise ValueError("No speech was transcribed; nothing to send")
        # Do not send here: the caller must preview/edit/confirm the transcript.
        return Message(text, language.tag)


class KokoroTTS:
    def __init__(self, model_dir):
        self.model_dir = Path(model_dir).expanduser().resolve()
        self._model = None
        self._pipelines = {}

    def _pipeline(self, language):
        if language.tag in self._pipelines:
            return self._pipelines[language.tag]
        config = _file(self.model_dir / "config.json")
        weights = _file(self.model_dir / "kokoro-v1_0.pth")
        _file(self.model_dir / "voices" / f"{language.voice}.pt")
        _offline()
        if language.whisper == "en" and importlib.util.find_spec("en_core_web_sm") is None:
            raise FileNotFoundError("Provision en_core_web_sm first; automatic downloads disabled")
        import torch
        from kokoro import KModel, KPipeline

        torch.set_num_threads(2)
        if self._model is None:
            self._model = (
                KModel(repo_id="hexgrad/Kokoro-82M", config=str(config), model=str(weights))
                .to("cpu")
                .eval()
            )
        pipeline = KPipeline(
            lang_code=language.kokoro,
            repo_id="hexgrad/Kokoro-82M",
            model=self._model,
            device="cpu",
        )
        self._pipelines[language.tag] = pipeline
        return pipeline

    def synthesize(self, message: Message, output):
        # Untagged stock text has no language-header overhead; do not add it here.
        validate_payload(message.text)
        language = resolve_language(message.language)
        voice = _file(self.model_dir / "voices" / f"{language.voice}.pt")
        pipeline = self._pipeline(language)
        # Bypass upstream's silent >510-phone truncation; reject instead.
        phones, _ = pipeline.g2p(message.text)
        if not phones or len(phones) > 510:
            raise ValueError("Text produced no phones or exceeds the 510-phone synthesis limit")
        samples = []
        for result in pipeline.generate_from_tokens(phones, voice=str(voice)):
            if result.audio is not None:
                values = result.audio.detach().cpu().tolist()
                if len(samples) + len(values) > 24000 * 60:
                    raise ValueError("Synthesis exceeds the 60-second output limit")
                samples.extend(values)
        if not samples or not all(
            isinstance(x, (int, float)) and math.isfinite(x) for x in samples
        ):
            raise ValueError("Kokoro produced empty or invalid audio")
        peak = max(abs(x) for x in samples)
        if peak == 0:
            raise ValueError("Kokoro produced silent audio")
        rms = math.sqrt(sum(x * x for x in samples) / len(samples))
        gain = min(0.10 / rms, 0.95 / peak)
        pcm = struct.pack(f"<{len(samples)}h", *(round(x * gain * 32767) for x in samples))
        output = Path(output).expanduser()
        # Exclusive creation protects existing recordings. No automatic playback.
        with output.open("xb") as file:
            with wave.open(file, "wb") as writer:
                writer.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
                writer.writeframes(pcm)
        return output
