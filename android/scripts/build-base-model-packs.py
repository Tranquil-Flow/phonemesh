"""Build the OPTIONAL Whisper base model packs. Never mutates existing tiny packs.

Same on-disk layout, naming convention and metadata contract as
`android/scripts/build-model-packs.py` (tiny), plus the optional
`recognizerModel=whisper-base` property in pack.properties. `PackStore` defaults
`recognizerModel` to `whisper-tiny`, so packs without the key stay tiny.

Only public, free, pinned upstream artifacts are downloaded. Every download is
size- and sha256-pinned; a mismatching artifact is never promoted into the cache.
"""

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

# key, release tag, file name, exact size in bytes, sha256
ASSETS = [
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

HF_REVISION = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
HF_CACHE = ROOT / ".local/android-base-hf"
HF_CACHE.mkdir(parents=True, exist_ok=True)
# Encoder/decoder SHA256 values are upstream LFS digests. Tokens were verified
# against upstream Git blob a4edf0b719c10d28cd83f8c294449f99ff5d5dc0 before pinning SHA256.
HF_ASSETS = [
    (
        "encoder.int8.onnx",
        29120534,
        "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
    ),
    (
        "decoder.int8.onnx",
        130672026,
        "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
    ),
    ("tokens.txt", 816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
]
receipts = []
whisper = {}
for filename, size, expected in HF_ASSETS:
    name = "base-" + filename
    path = HF_CACHE / name
    url = f"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/{HF_REVISION}/{name}"
    if not path.exists():
        part = path.with_suffix(path.suffix + ".download")
        total = 0
        with urllib.request.urlopen(url, timeout=45) as response, part.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > size:
                    raise RuntimeError("Oversized Whisper file")
                output.write(chunk)
        if total != size or hashlib.sha256(part.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Whisper artifact size/hash mismatch; partial retained")
        part.replace(path)
    data = path.read_bytes()
    if len(data) != size or hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Unexpected Whisper file: {name}")
    whisper[filename] = data
    receipts.append(
        {
            "key": "whisper-base",
            "file": filename,
            "url": url,
            "revision": HF_REVISION,
            "size": size,
            "sha256": expected,
        }
    )
for key, tag, name, size, expected in ASSETS:
    path = CACHE / name
    url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/{name}"
    if not path.exists():
        part = path.with_suffix(".partial")
        total = 0
        with urllib.request.urlopen(url, timeout=120) as response, part.open("wb") as output:
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

(ROOT / ".local/android-speech-comparison" / "base-pack-sources.json").parent.mkdir(
    parents=True, exist_ok=True
)
(ROOT / ".local/android-speech-comparison" / "base-pack-sources.json").write_text(
    json.dumps(receipts, indent=2)
)

assert set(whisper) == {"encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"}

for key, _tag, name, _size, _expected in ASSETS:
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
    assert {
        "model.onnx",
        "tokens.txt",
        "espeak-ng-data/phontab",
        "espeak-ng-data/phondata",
        "espeak-ng-data/phonindex",
        "espeak-ng-data/intonations",
    } <= models.keys()
    assert any(p.startswith("espeak-ng-data/lang/") for p in models)
    voice = "ljspeech" if key == "en-US" else "siwis"
    target = OUT / f"phonemesh-{key}-whisper-base-piper-{voice}-int8.zip"
    files = {"whisper/" + k: v for k, v in whisper.items()}
    files.update({"tts/" + k: v for k, v in models.items()})
    label = (
        "English - Whisper Base + Piper LJSpeech INT8"
        if key == "en-US"
        else "Francais - Whisper Base + Piper Siwis INT8"
    )
    assert len(label) <= 80
    files["pack.properties"] = (
        f"format=1\nlanguageTag={key}\nwhisperLanguage={key.split('-')[0]}\n"
        f"recognizerModel=whisper-base\nlabel={label}\n"
    ).encode()
    hashes = {n: hashlib.sha256(d).hexdigest() for n, d in files.items()}
    files["SHA256SUMS"] = "".join(f"{v}  {n}\n" for n, v in sorted(hashes.items())).encode()
    files["SOURCES.json"] = json.dumps(
        [x for x in receipts if x["key"] in ("whisper-base", key)], indent=2
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
        "Whisper base (multilingual): OpenAI MIT model, ONNX conversion by sherpa-onnx.\n"
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
        partial = target.with_suffix(".zip.partial")
        with zipfile.ZipFile(
            partial, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3
        ) as archive:
            for filename, data in sorted(files.items()):
                info = zipfile.ZipInfo(filename, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=3)
        with zipfile.ZipFile(partial) as archive:
            assert archive.testzip() is None
        partial.replace(target)
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
print("ANDROID_BASE_MODEL_PACKS_READY", flush=True)
