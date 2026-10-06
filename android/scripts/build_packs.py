"""Build reproducible format-2 engine and voice packs offline from SHA-pinned inputs."""

import argparse
import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".local/android-model-downloads"
HF_CACHE = ROOT / ".local/android-base-hf"
OUT = ROOT / "android/model-packs"
CORE_CATALOG = ROOT / "android/core/src/main/kotlin/org/phonemesh/core/GeneratedPackCatalog.kt"
ESPEAK_CORE = {"phontab", "phonindex", "phondata", "intonations"}
FIXED_DATE = (2020, 1, 1, 0, 0, 0)
VERSION = 1
DEFAULT_CATALOG = [
    "engine-whisper-tiny",
    "engine-whisper-base",
    "voice-fr-siwis",
    "voice-en-US-ljspeech",
]

# (key, release tag, file name, size, sha256)
INPUTS = [
    (
        "whisper-tiny",
        "asr-models",
        "sherpa-onnx-whisper-tiny.tar.bz2",
        116204861,
        "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
    ),
    (
        "voice-en-US-ljspeech",
        "tts-models",
        "vits-piper-en_US-ljspeech-medium-int8.tar.bz2",
        21090429,
        "24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b",
    ),
    (
        "voice-fr-siwis",
        "tts-models",
        "vits-piper-fr_FR-siwis-low-int8.tar.bz2",
        13317962,
        "c001d38068a316c93601a7d10d0b983c9e61adf2af0c4533595483c7f6cba614",
    ),
    (
        "voice-en-US-lessac-low",
        "tts-models",
        "vits-piper-en_US-lessac-low-int8.tar.bz2",
        21070568,
        "af63fbe60d8bdcfccdee61ba057304a11dfc077145da383d4d351ec3c594d5e2",
    ),
]
VOICES = {
    "voice-en-US-ljspeech": {"languageTag": "en-US", "whisperLanguage": "en", "label": "English"},
    "voice-fr-siwis": {"languageTag": "fr", "whisperLanguage": "fr", "label": "Français"},
    "voice-en-US-lessac-low": {
        "languageTag": "en-US",
        "whisperLanguage": "en",
        "label": "English (low)",
    },
}
VOICE_NOTES = {
    "voice-en-US-ljspeech": (
        "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ljspeech/medium/MODEL_CARD",
        "LJ Speech Dataset, https://keithito.com/LJ-Speech-Dataset/, "
        "upstream model card: public domain.",
    ),
    "voice-fr-siwis": (
        "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/low/MODEL_CARD",
        "SIWIS French Speech Synthesis Database, "
        "https://datashare.is.ed.ac.uk/handle/10283/2353, "
        "upstream model card: CC-BY 4.0. Voice fine-tuned from Ryan low.",
    ),
    "voice-en-US-lessac-low": (
        "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/lessac/low/MODEL_CARD",
        "Blizzard 2013 (Lessac) dataset; see the upstream model card for licence terms.",
    ),
}

HF_REVISION = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
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


def espeak_keep(names: set[str], voice: str) -> set[str]:
    base = voice.split("-")[0].lower()
    dictionary = f"{base}_dict"
    if dictionary not in names:
        raise ValueError(f"espeak-ng data has no {dictionary}")
    voice_files = sorted(
        n for n in names if n.startswith("lang/") and n.split("/")[-1].lower() == voice.lower()
    )
    if len(voice_files) != 1:
        raise ValueError(f"expected exactly one lang/*/{voice}, found {voice_files}")
    missing = ESPEAK_CORE - names
    if missing:
        raise ValueError(f"espeak-ng core files missing: {sorted(missing)}")
    return ESPEAK_CORE | {dictionary, voice_files[0]}


def manifest(files: dict[str, bytes]) -> bytes:
    rows = sorted((n, hashlib.sha256(d).hexdigest()) for n, d in files.items() if n != "SHA256SUMS")
    return "".join(f"{h}  {n}\n" for n, h in rows).encode()


def pack_properties(**fields) -> bytes:
    order = ["kind", "id", "version", "label", "recognizerModel", "languageTag", "whisperLanguage"]
    lines = ["format=2"] + [f"{k}={fields[k]}" for k in order if k in fields]
    return ("\n".join(lines) + "\n").encode()


def download(url: str, path: Path, size: int, expected: str, timeout: int) -> None:
    part = path.with_suffix(path.suffix + ".partial")
    total = 0
    with urllib.request.urlopen(url, timeout=timeout) as response, part.open("xb") as output:
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


def fetch(key: str) -> tuple[Path, dict]:
    _, tag, name, size, expected = next(i for i in INPUTS if i[0] == key)
    path = CACHE / name
    url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/{name}"
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        download(url, path, size, expected, 120)
    if path.stat().st_size != size or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"Unexpected existing artifact: {path}")
    return path, {"key": key, "url": url, "size": size, "sha256": expected}


def load_requantize():
    path = Path(__file__).resolve().parent / "requantize_decoder.py"
    spec = importlib.util.spec_from_file_location("requantize_decoder", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.requantize


def whisper_tiny_files(tar_path: Path, decoder_mode: str) -> dict[str, bytes]:
    whisper = {}
    with tarfile.open(tar_path, "r:bz2") as archive:
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
    if decoder_mode == "requant":
        requantize = load_requantize()
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "decoder.onnx", Path(tmp) / "decoder.requant.onnx"
            src.write_bytes(whisper["decoder.int8.onnx"])
            requantize(src, dst)
            whisper["decoder.int8.onnx"] = dst.read_bytes()
    return whisper


def whisper_base_files() -> tuple[dict[str, bytes], list[dict]]:
    HF_CACHE.mkdir(parents=True, exist_ok=True)
    files, receipts = {}, []
    for filename, size, expected in HF_ASSETS:
        name = "base-" + filename
        path = HF_CACHE / name
        url = (
            f"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/"
            f"{HF_REVISION}/{name}"
        )
        if not path.exists():
            download(url, path, size, expected, 45)
        data = path.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError(f"Unexpected Whisper file: {name}")
        files[filename] = data
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
    return files, receipts


def voice_files(tar_path: Path) -> tuple[dict[str, bytes], str]:
    model, tokens, espeak, voice = None, None, {}, None
    with tarfile.open(tar_path, "r:bz2") as archive:
        members = archive.getmembers()
        assert len(members) < 5000
        for member in members:
            if not member.isfile():
                continue
            assert 0 <= member.size < 256 * 1024 * 1024
            if member.name.endswith(".onnx.json"):
                voice = json.loads(archive.extractfile(member).read())["espeak"]["voice"]
            elif member.name.endswith(".onnx"):
                model = archive.extractfile(member).read()
            elif member.name.endswith("/tokens.txt"):
                tokens = archive.extractfile(member).read()
            elif "/espeak-ng-data/" in member.name:
                relative = member.name.split("/espeak-ng-data/", 1)[1]
                assert ".." not in Path(relative).parts and not Path(relative).is_absolute()
                assert relative not in espeak
                espeak[relative] = archive.extractfile(member).read()
    if model is None or tokens is None or voice is None:
        raise ValueError(f"{tar_path.name}: missing model, tokens or .onnx.json")
    files = {"model.onnx": model, "tokens.txt": tokens}
    for name in sorted(espeak_keep(set(espeak), voice)):
        files["espeak-ng-data/" + name] = espeak[name]
    return {"tts/" + k: v for k, v in files.items()}, voice


def notice(kind: str, key: str) -> bytes:
    tail = (
        "Read the original model cards and fulfil attribution/licence requirements "
        "before redistribution. This test pack does not claim public-release legal clearance.\n"
    )
    if kind == "engine":
        name = "base (multilingual)" if key == "whisper-base" else "tiny (multilingual)"
        return (
            f"Whisper {name}: OpenAI MIT model, ONNX conversion by sherpa-onnx.\n" + tail
        ).encode()
    card, dataset = VOICE_NOTES[key]
    return (
        f"Piper voice model card: {card}\n{dataset}\n"
        "espeak-ng (GPL-3.0), source: https://github.com/espeak-ng/espeak-ng\n" + tail
    ).encode()


def write_pack(out: Path, name: str, files: dict[str, bytes], kind: str, key: str, sources) -> dict:
    files = dict(files)
    files["NOTICE.txt"] = notice(kind, key)
    files["SOURCES.json"] = json.dumps(sources, indent=2).encode()
    files["SHA256SUMS"] = manifest(files)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for filename, data in sorted(files.items()):
            info = zipfile.ZipInfo(filename, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    blob = buffer.getvalue()
    target = out / name
    if target.exists():
        if target.read_bytes() != blob:
            raise RuntimeError(f"Existing pack differs from rebuild: {target}")
    else:
        partial = target.with_suffix(".zip.partial")
        partial.write_bytes(blob)
        partial.replace(target)
    return {
        "bytes": len(blob),
        "manifest_sha256": hashlib.sha256(files["SHA256SUMS"]).hexdigest(),
        "fileName": name,
    }


def catalog_kotlin(entries: list[dict]) -> str:
    rows = "".join(
        f'        CatalogEntry("{e["id"]}", {VERSION}, PackKind.{e["kind"].upper()}, '
        f'"{e["fileName"]}", {e["bytes"]}L, "{e["manifest_sha256"]}"),\n'
        for e in entries
    )
    return (
        "// Generated by android/scripts/build_packs.py. Do not edit.\n"
        "package org.phonemesh.core\n\n"
        "object GeneratedPackCatalog {\n"
        "    val entries: List<CatalogEntry> = listOf(\n"
        f"{rows}"
        "    )\n"
        "}\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--decoder", choices=["original", "requant"], default="original")
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--catalog-out", type=Path, default=CORE_CATALOG)
    parser.add_argument("--catalog-include", action="append", default=[])
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    built = []

    def record(pack_id: str, kind: str, result: dict) -> None:
        entry = {"id": pack_id, "kind": kind, **result}
        built.append(entry)
        print(
            json.dumps({k: entry[k] for k in ("id", "bytes", "manifest_sha256")}),
            flush=True,
        )

    tiny_path, tiny_receipt = fetch("whisper-tiny")
    engines = [
        (
            "whisper-tiny",
            "Whisper Tiny",
            whisper_tiny_files(tiny_path, args.decoder),
            [tiny_receipt],
        )
    ]
    base_files, base_receipts = whisper_base_files()
    engines.append(("whisper-base", "Whisper Base", base_files, base_receipts))
    for model, label, whisper, receipts in engines:
        pack_id = f"engine-{model}"
        files = {"whisper/" + k: v for k, v in whisper.items()}
        files["pack.properties"] = pack_properties(
            kind="engine",
            id=pack_id,
            version=VERSION,
            label=label,
            recognizerModel=model,
        )
        name = f"phonemesh-{pack_id}-{VERSION}.zip"
        record(pack_id, "engine", write_pack(args.out, name, files, "engine", model, receipts))

    for pack_id, meta in VOICES.items():
        path, receipt = fetch(pack_id)
        files, espeak_voice = voice_files(path)
        files["pack.properties"] = pack_properties(
            kind="voice", id=pack_id, version=VERSION, **meta
        )
        name = f"phonemesh-{pack_id}-{VERSION}.zip"
        record(pack_id, "voice", write_pack(args.out, name, files, "voice", pack_id, [receipt]))
        print(json.dumps({"id": pack_id, "espeak_voice": espeak_voice}), flush=True)

    wanted = set(DEFAULT_CATALOG) | set(args.catalog_include)
    unknown = wanted - {e["id"] for e in built}
    if unknown:
        raise SystemExit(f"unknown catalog ids: {sorted(unknown)}")
    chosen = [e for e in built if e["id"] in wanted]
    args.catalog_out.parent.mkdir(parents=True, exist_ok=True)
    args.catalog_out.write_text(catalog_kotlin(chosen))


if __name__ == "__main__":
    main()
