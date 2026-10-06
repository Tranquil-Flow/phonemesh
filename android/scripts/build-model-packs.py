"""Build reproducible offline phone packs; never opens a microphone or radio."""

import hashlib
import json
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".local/android-model-downloads"
OUT = ROOT / "android/model-packs"
CACHE.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
ASSETS = [
    (
        "whisper",
        "asr-models",
        "sherpa-onnx-whisper-tiny.tar.bz2",
        116204861,
        "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
    ),
    (
        "en-US",
        "tts-models",
        "vits-piper-en_US-ljspeech-medium-int8.tar.bz2",
        21090429,
        "24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b",
    ),
    (
        "fr",
        "tts-models",
        "vits-piper-fr_FR-siwis-low-int8.tar.bz2",
        13317962,
        "c001d38068a316c93601a7d10d0b983c9e61adf2af0c4533595483c7f6cba614",
    ),
]
receipts = []
for key, tag, name, size, expected in ASSETS:
    path = CACHE / name
    url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/{name}"
    if not path.exists():
        part = path.with_suffix(".partial")
        total = 0
        with urllib.request.urlopen(url, timeout=60) as response, part.open("xb") as output:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > size:
                    raise RuntimeError("Oversized download")
                output.write(chunk)
        if total != size:
            raise RuntimeError("Incomplete download; partial retained")
        if hashlib.sha256(part.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Artifact hash mismatch; partial retained")
        part.rename(path)
    if path.stat().st_size != size or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"Unexpected existing artifact: {path}")
    receipt = {"key": key, "url": url, "size": size, "sha256": expected}
    receipts.append(receipt)
    print(json.dumps(receipt), flush=True)
(CACHE / "production-pack-sources.json").write_text(json.dumps(receipts, indent=2))

whisper = {}
with tarfile.open(CACHE / ASSETS[0][2], "r:bz2") as archive:
    for member in archive.getmembers():
        if not member.isfile():
            continue
        if member.name.endswith("encoder.int8.onnx"):
            name = "encoder.int8.onnx"
        elif member.name.endswith("decoder.int8.onnx"):
            name = "decoder.int8.onnx"
        elif member.name.endswith("tokens.txt"):
            name = "tokens.txt"
        else:
            continue
        assert name not in whisper and 0 < member.size < 256 * 1024 * 1024
        whisper[name] = archive.extractfile(member).read()
assert set(whisper) == {"encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"}

for key, _tag, name, _size, _expected in ASSETS[1:]:
    models = {}
    with tarfile.open(CACHE / name, "r:bz2") as archive:
        members = archive.getmembers()
        assert len(members) < 5000
        for member in members:
            if not member.isfile():
                continue
            assert 0 <= member.size < 256 * 1024 * 1024
            if member.name.endswith(".onnx"):
                relative = "model.onnx"
            elif member.name.endswith("/tokens.txt"):
                relative = "tokens.txt"
            elif "/espeak-ng-data/" in member.name:
                relative = "espeak-ng-data/" + member.name.split("/espeak-ng-data/", 1)[1]
            else:
                continue
            assert ".." not in Path(relative).parts and not Path(relative).is_absolute()
            assert relative not in models
            models[relative] = archive.extractfile(member).read()
    assert {"model.onnx", "tokens.txt", "espeak-ng-data/phontab"} <= models.keys()
    voice = "ljspeech" if key == "en-US" else "siwis"
    target = OUT / f"phonemesh-{key}-whisper-tiny-piper-{voice}-int8.zip"
    files = {"whisper/" + k: v for k, v in whisper.items()}
    files.update({"tts/" + k: v for k, v in models.items()})
    label = "English - Piper LJSpeech INT8" if key == "en-US" else "Francais - Piper Siwis INT8"
    files["pack.properties"] = (
        f"format=1\nlanguageTag={key}\nwhisperLanguage={key.split('-')[0]}\nlabel={label}\n".encode()
    )
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    files["SHA256SUMS"] = "".join(
        f"{value}  {name}\n" for name, value in sorted(hashes.items())
    ).encode()
    files["SOURCES.json"] = json.dumps(
        [receipts[0], next(x for x in receipts if x["key"] == key)], indent=2
    ).encode()
    card = (
        "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ljspeech/medium/MODEL_CARD"
        if key == "en-US"
        else "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/low/MODEL_CARD"
    )
    dataset = (
        "LJ Speech Dataset, https://keithito.com/LJ-Speech-Dataset/, "
        "upstream model card: public domain."
        if key == "en-US"
        else "SIWIS French Speech Synthesis Database, "
        "https://datashare.is.ed.ac.uk/handle/10283/2353, "
        "upstream model card: CC-BY 4.0. Voice fine-tuned from Ryan low."
    )
    files["NOTICE.txt"] = (
        f"Whisper: OpenAI MIT model, ONNX conversion by sherpa-onnx.\n"
        f"Piper voice model card: {card}\n{dataset}\n"
        "Read the original model cards and fulfil attribution/licence requirements "
        "before redistribution. "
        "This test pack does not claim public-release legal clearance.\n"
    ).encode()
    if target.exists():
        with zipfile.ZipFile(target) as archive:
            assert set(archive.namelist()) == set(files)
            for filename, data in files.items():
                assert (
                    hashlib.sha256(archive.read(filename)).digest() == hashlib.sha256(data).digest()
                )
    else:
        with zipfile.ZipFile(
            target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=3
        ) as archive:
            for filename, data in sorted(files.items()):
                info = zipfile.ZipInfo(filename, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=3)
    print(
        json.dumps(
            {
                "model_pack": str(target),
                "bytes": target.stat().st_size,
                "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            }
        ),
        flush=True,
    )
print("ANDROID_MODEL_PACKS_READY", flush=True)
