"""Run an explicit test class list and require the instrumentation result, not adb's exit code."""

import argparse
import json
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--classes", required=True)
p.add_argument("--expected", required=True, type=int)
p.add_argument("--receipt", required=True, type=Path)
p.add_argument(
    "--args", action="append", default=[], metavar="KEY=VALUE", help="instrumentation argument"
)
a = p.parse_args()
extra = []
for pair in a.args:
    key, sep, value = pair.partition("=")
    assert sep and key and key != "class", f"--args needs KEY=VALUE (not class): {pair}"
    extra += ["-e", key, value]
if not a.serial.startswith("emulator-"):
    assert a.classes == "org.phonemesh.app.SpeechComparisonTest", (
        "Only silent speech comparison is allowed on a physical phone"
    )
    assert not a.args, "No instrumentation arguments on a physical phone"
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
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=300
    )
    output = result.stdout
except subprocess.TimeoutExpired as error:
    output = error.stdout or b""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    a.receipt.parent.mkdir(parents=True, exist_ok=True)
    a.receipt.write_text(output + "\nHARNESS_TIMEOUT\n")
    subprocess.run(
        ["adb", "-s", a.serial, "shell", "am", "force-stop", "org.phonemesh.app"], check=False
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
