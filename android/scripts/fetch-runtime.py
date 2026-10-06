"""Acquire the exact upstream AAR used by this Android build."""

import hashlib
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
name = "sherpa-onnx-1.13.8.aar"
url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/" + name
size = 50129134
expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
out = root / "libs" / name
out.parent.mkdir(parents=True, exist_ok=True)
if not out.exists():
    part = out.with_suffix(".partial")
    total = 0
    with urllib.request.urlopen(url, timeout=60) as response, part.open("xb") as output:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > size:
                raise RuntimeError("Unexpected artifact size")
            output.write(chunk)
    if total != size or hashlib.sha256(part.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Runtime integrity check failed; partial retained")
    part.rename(out)
if out.stat().st_size != size or hashlib.sha256(out.read_bytes()).hexdigest() != expected:
    raise RuntimeError("Existing runtime does not match pinned SHA-256")
print(f"Verified {out}: {expected}")
