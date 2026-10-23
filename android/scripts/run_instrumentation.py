"""Run an explicit test class list and require the instrumentation result, not adb's exit code."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

# Protected by default; only the exact reviewed isolated account classes have an exception.
PROTECTED_PIXEL_SHA256 = "2f13c86c804d5380b758bcb66c885ac12ce0a688f28efe6b4e4aa94578d62992"
PIXEL_ACCOUNT_CLASSES = frozenset(
    {
        "org.phonemesh.app.AccountStoreTest",
        "org.phonemesh.app.AccountsFlowTest",
    }
)
COMPARISON = "org.phonemesh.app.SpeechComparisonTest"


def is_protected(serial: str | None) -> bool:
    """Keep the exact physical-device fence without publishing its owner's raw serial."""
    return (
        serial is not None and hashlib.sha256(serial.encode()).hexdigest() == PROTECTED_PIXEL_SHA256
    )


def refusal(serial: str, classes: str, args: list[str], allow_device: str | None) -> str | None:
    """Why this run must not start, or None. Emulators run anything; a physical phone runs only
    the silent speech comparison, unless explicitly opted in. Pixel has a narrower exception."""
    if is_protected(serial) or is_protected(allow_device):
        if (
            is_protected(serial)
            and allow_device == serial
            and classes in PIXEL_ACCOUNT_CLASSES
            and not args
        ):
            return None
        return (
            "protected phone: only explicitly authorized isolated account classes, "
            "without arguments"
        )
    if allow_device is not None and allow_device.startswith("emulator-"):
        return "--allow-device names a physical phone, not an emulator"
    if serial.startswith("emulator-"):
        return None
    if allow_device == serial:
        return None
    if allow_device is not None:
        return f"--allow-device {allow_device} does not match --serial {serial}"
    if classes != COMPARISON:
        return "Only silent speech comparison is allowed on a physical phone"
    if args:
        return "No instrumentation arguments on a physical phone"
    return None


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True)
    p.add_argument("--classes", required=True)
    p.add_argument("--expected", required=True, type=int)
    p.add_argument("--receipt", required=True, type=Path)
    p.add_argument(
        "--timeout",
        type=int,
        default=300,
        help="seconds before the run is stopped (HARNESS_TIMEOUT)",
    )
    p.add_argument(
        "--args", action="append", default=[], metavar="KEY=VALUE", help="instrumentation argument"
    )
    p.add_argument(
        "--allow-device",
        metavar="SERIAL",
        help=(
            "owner-approved physical test phone; protected Pixel permits only exact "
            "isolated account classes without arguments"
        ),
    )
    a = p.parse_args()
    extra = []
    for pair in a.args:
        key, sep, value = pair.partition("=")
        if not (sep and key and key != "class"):
            sys.exit(f"--args needs KEY=VALUE (not class): {pair}")
        extra += ["-e", key, value]
    reason = refusal(a.serial, a.classes, a.args, a.allow_device)
    if reason:
        sys.exit(f"refused: {reason}")
    run(a, extra)


def run(a: argparse.Namespace, extra: list[str]) -> None:
    command = [
        "adb",
        "-s",
        a.serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        a.classes,
        *extra,
        "org.phonemesh.app.test/androidx.test.runner.AndroidJUnitRunner",
    ]
    try:
        result = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=a.timeout
        )
        output = result.stdout
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b""
        if isinstance(output, bytes):
            output = output.decode(errors="replace")
        a.receipt.parent.mkdir(parents=True, exist_ok=True)
        a.receipt.write_text(output + "\nHARNESS_TIMEOUT\n")
        subprocess.run(
            ["adb", "-s", a.serial, "shell", "am", "force-stop", "org.phonemesh.app"],
            check=False,
            timeout=60,
        )
        raise

    a.receipt.parent.mkdir(parents=True, exist_ok=True)
    a.receipt.write_text(output)
    match = re.search(r"OK \((\d+) tests?\)", output)
    passed = (
        result.returncode == 0
        and bool(match)
        and int(match.group(1)) == a.expected
        and "FAILURES" not in output
        and "INSTRUMENTATION_FAILED" not in output
    )
    print(
        json.dumps(
            {
                "serial": a.serial,
                "classes": a.classes,
                "args": a.args,
                "expected_tests": a.expected,
                "observed_tests": int(match.group(1)) if match else None,
                "passed": passed,
                "receipt": str(a.receipt),
            }
        )
    )
    if not passed:
        print(output[-6000:])
        raise SystemExit(1)


if __name__ == "__main__":
    main()
