#!/usr/bin/env python3
"""Write android/core/src/main/resources/emoji-5.0.txt: emoji every supported phone draws.

Android 8.0, the oldest Android Phonemesh supports, draws Unicode Emoji 5.0 (spec decision 49).
This keeps the fully-qualified sequences of Unicode's emoji-test.txt for that version, in its
groups, one group per line. The source file is pinned by SHA-256.

Usage: scripts/emoji_avatar_list.py [path to emoji-test.txt]; without a path it downloads it.
"""

import hashlib
import pathlib
import sys
import urllib.request

URL = "https://www.unicode.org/Public/emoji/5.0/emoji-test.txt"
SHA256 = "fcb5b1dab310dc8210af46372828453431fa6ecdafdae8e051cbb68eb698380c"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "android/core/src/main/resources/emoji-5.0.txt"

HEADER = """\
# Emoji every phone Phonemesh supports can draw: Android 8.0 draws Unicode Emoji 5.0
# (spec decision 49). The fully-qualified sequences of Unicode's emoji-test.txt, version 5.0
# (2017-06-19), one group per line after its "@" line. Made by scripts/emoji_avatar_list.py
# from SHA-256 {sha}.
# Copyright © 2017 Unicode, Inc. Unicode License v3: docs/licenses/unicode-LICENSE.txt.
"""


def main() -> None:
    if len(sys.argv) > 1:
        data = pathlib.Path(sys.argv[1]).read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        sys.exit("emoji-test.txt does not match the pinned SHA-256")
    groups: dict[str, list[str]] = {}
    group = None
    for line in data.decode("utf-8").splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            groups.setdefault(group, [])
        elif "; fully-qualified" in line and group is not None:
            points = line.split(";", 1)[0].split()
            groups[group].append("".join(chr(int(p, 16)) for p in points))
    lines = [HEADER.format(sha=SHA256).rstrip("\n")]
    for name, sequences in groups.items():
        lines.append("@" + name)
        lines.append(" ".join(sequences))
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    count = sum(len(s) for s in groups.values())
    print(f"{count} emoji in {len(groups)} groups -> {OUT}")


if __name__ == "__main__":
    main()
