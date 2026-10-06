#!/usr/bin/env python3
"""Fail if networking APIs appear in the compiled APK outside the allowed callers."""

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

API_PATTERNS = [
    "Ljava/net/Socket;",
    "Ljava/net/URL;.openConnection",
    "Ljava/net/URL;.openStream",
    "Ljava/net/HttpURLConnection;",
    "Ljavax/net/ssl/",
    "Landroid/webkit/WebView;",
    "Landroid/app/DownloadManager;.enqueue",
]
ALLOWED_CALLERS = {"Lorg/phonemesh/app/PackDownloads;"}
DESCRIPTOR_MARKER = "Class descriptor"


def caller_allowed(caller: str, allowed_callers: set[str]) -> bool:
    """Allowed if listed, or an inner/companion/lambda class (`Name$...;`) of a listed one."""
    return any(caller == a or caller.startswith(a.removesuffix(";") + "$") for a in allowed_callers)


def violations(dump: str, allowed_callers: set[str], allowlist: set[str]) -> list[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    caller = ""
    for line in dump.splitlines():
        if DESCRIPTOR_MARKER in line:
            caller = line.split("'")[1]
            continue
        if not caller or caller_allowed(caller, allowed_callers):
            continue
        for pattern in API_PATTERNS:
            if pattern in line and f"{caller} {pattern}" not in allowlist:
                found.add((caller, pattern))
    return sorted(found)


def load_allowlist(path: Path) -> set[str]:
    entries = set()
    for raw in path.read_text().splitlines():
        entry = raw.split("#", 1)[0].strip()
        if entry:
            entries.add(entry)
    return entries


class ScanError(Exception):
    """The scan could not be trusted (it would have passed vacuously)."""


def descriptors(dump: str) -> set[str]:
    return {line.split("'")[1] for line in dump.splitlines() if DESCRIPTOR_MARKER in line}


def check_scanned(dump: str, required: list[str]) -> None:
    found = descriptors(dump)
    if not found:
        raise ScanError("dexdump output has no class descriptors; nothing was scanned")
    missing = [c for c in required if c not in found]
    if missing:
        raise ScanError(f"required class missing from the scanned dex: {', '.join(missing)}")


def dump_apk(apk: Path, dexdump: str) -> str:
    parts = []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        dex_names = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not dex_names:
            raise ScanError(f"no classes*.dex in {apk}; nothing was scanned")
        for name in sorted(dex_names):
            dex = Path(tmp) / name
            dex.write_bytes(z.read(name))
            cmd = [dexdump, "-d", str(dex)]
            out = subprocess.run(cmd, check=True, capture_output=True)
            parts.append(out.stdout.decode("utf-8", errors="replace"))
    return "\n".join(parts)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    ap.add_argument("--allowlist", type=Path, required=True)
    ap.add_argument("--dexdump", required=True)
    ap.add_argument("--require-class", action="append", default=[], metavar="DESCRIPTOR")
    args = ap.parse_args()
    try:
        dump = dump_apk(args.apk, args.dexdump)
        check_scanned(dump, args.require_class)
    except ScanError as e:
        print(f"NETWORK_BOUNDARY_SCAN_FAILED: {e}", file=sys.stderr)
        return 1
    found = violations(dump, ALLOWED_CALLERS, load_allowlist(args.allowlist))
    if found:
        print("NETWORK_BOUNDARY_VIOLATIONS", file=sys.stderr)
        for caller, api in found:
            print(f"  {caller} -> {api}", file=sys.stderr)
        return 1
    print("NETWORK_BOUNDARY_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
