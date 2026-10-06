"""Generate shared wire vectors from the actual Python codec (no models/radios)."""

import base64
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "src"))
from phonemesh.message import Message  # noqa: E402

cases = [
    ("en-US", "Hello, world! 🌍"),
    ("en-GB", "Meet me at the library."),
    ("fr", "Bonjour à tous."),
    ("es", "Nos vemos en la biblioteca."),
    ("hi", "क्\u200dष नमस्ते"),
    ("it", "Ci vediamo in biblioteca."),
    ("ja", "図書館で会いましょう。"),
    ("pt-BR", "Olá, como vai?"),
    ("zh", "我们在图书馆见面。"),
]
rows = []
for language, text in cases:
    payload = Message(text, language).encode()
    assert Message.decode(payload).text == text
    rows.append(
        "\t".join(
            [
                language,
                base64.b64encode(text.encode()).decode(),
                base64.b64encode(payload.encode()).decode(),
            ]
        )
    )
out = root / "android/core/src/test/resources/python-wire-vectors.tsv"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(rows) + "\n")
print(f"Generated {len(rows)} vectors from the Python production codec: {out}")
