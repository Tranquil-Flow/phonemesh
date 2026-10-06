"""Check that trimmed espeak-ng data synthesizes the same samples as the full data, on an emulator.

espeak-ng is set up once per process from the first data directory it is given, so each variant
runs `PackEquivalenceTest#singleVariantSynthesis` in its own `am instrument` process (through
run_instrumentation.py), after force-stopping the app and deleting the shared espeak copy and the
variant pack dirs. The receipts are pulled and the sample hashes compared: trimmed must equal full
for every sentence, in four different processes.

Needs the minified debug app and test APKs installed and the catalog engine, both catalog voices
and the `-full` voices (`build_packs.py --reference`) pushed with push_test_packs.sh.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

VARIANTS = ["fr-full", "fr-trimmed", "en-full", "en-trimmed"]
APP = "org.phonemesh.app"
TEST = "org.phonemesh.app.PackEquivalenceTest#singleVariantSynthesis"
FILES = f"/sdcard/Android/data/{APP}/files"
RUNNER = Path(__file__).resolve().parent / "run_instrumentation.py"


def require_emulator(serial: str) -> None:
    if not serial.startswith("emulator-"):
        raise SystemExit(f"refused: {serial} is not an emulator")


def problems(results: dict[str, dict]) -> list[str]:
    """Everything that stops the receipts from showing trimmed == full in separate processes."""
    found = []
    missing = [v for v in VARIANTS if v not in results]
    if missing:
        return [f"missing variant receipts: {', '.join(missing)}"]
    pids = [results[v]["pid"] for v in VARIANTS]
    if len(set(pids)) != len(pids):
        found.append(f"variants shared a process (pids {pids})")
    for v in VARIANTS:
        if results[v].get("sharedCopyPresent"):
            found.append(f"{v}: the app's shared espeak copy was present")
    for language in ("fr", "en"):
        full = results[f"{language}-full"]["rows"]
        trimmed = results[f"{language}-trimmed"]["rows"]
        if len(full) != len(trimmed) or not full:
            found.append(f"{language}: {len(full)} full rows vs {len(trimmed)} trimmed rows")
            continue
        for f, t in zip(full, trimmed, strict=True):
            if f["text"] != t["text"]:
                found.append(f"{language}: sentences differ ({f['text']!r} vs {t['text']!r})")
            elif (f["sha256"], f["samples"]) != (t["sha256"], t["samples"]):
                found.append(f"{language}: trimmed differs from full for {f['text']}")
    return found


def adb(serial: str, *args: str, check: bool = True) -> str:
    out = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, check=check)
    return out.stdout


def run_variant(serial: str, variant: str, receipts: Path) -> dict:
    adb(serial, "shell", "am", "force-stop", APP)
    adb(
        serial,
        "shell",
        "run-as",
        APP,
        "rm",
        "-rf",
        "files/espeak-ng-data",
        *[f"files/espeak-variant-{v}" for v in VARIANTS],
    )
    adb(serial, "shell", "rm", "-f", f"{FILES}/espeak-variant-{variant}.json")
    subprocess.run(
        [
            sys.executable,
            str(RUNNER),
            "--serial",
            serial,
            "--classes",
            TEST,
            "--args",
            f"variant={variant}",
            "--expected",
            "1",
            "--receipt",
            str(receipts / f"espeak-{variant}.txt"),
        ],
        check=True,
    )
    text = adb(serial, "shell", "cat", f"{FILES}/espeak-variant-{variant}.json")
    (receipts / f"espeak-{variant}.json").write_text(text)
    return json.loads(text)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--receipts", type=Path, required=True, help="directory for the receipts")
    a = p.parse_args()
    require_emulator(a.serial)
    a.receipts.mkdir(parents=True, exist_ok=True)
    results = {v: run_variant(a.serial, v, a.receipts) for v in VARIANTS}
    for v in VARIANTS:
        rows = ", ".join(f"{r['samples']}/{r['sha256'][:8]}" for r in results[v]["rows"])
        print(f"{v}: pid {results[v]['pid']}, {results[v].get('espeakFiles')} files: {rows}")
    found = problems(results)
    for line in found:
        print(f"ESPEAK_VARIANTS_DIFFER: {line}")
    if found:
        raise SystemExit(1)
    print("ESPEAK_VARIANTS_IDENTICAL")


if __name__ == "__main__":
    main()
