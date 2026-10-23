"""Public-source privacy contract; this reads source and never starts devices or subprocesses."""

import ast
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTECTED_DEVICE_HASHES = {
    "2f13c86c804d5380b758bcb66c885ac12ce0a688f28efe6b4e4aa94578d62992",
    "e607f3ba7a99dea58b7030215c852b46d2aef8472daf3427b4eabca2de3add59",
}


def test_public_policy_and_fixtures_do_not_publish_owner_serial_literals():
    for relative in (
        "android/scripts/run_instrumentation.py",
        "tests/test_run_instrumentation.py",
        "tests/test_compare_espeak_variants.py",
    ):
        tree = ast.parse((ROOT / relative).read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Constant) and isinstance(node.value, str):
                digest = hashlib.sha256(node.value.encode()).hexdigest()
                if digest in PROTECTED_DEVICE_HASHES:
                    # Avoid assertion rewriting dumping the protected identifier.
                    raise AssertionError(
                        f"{relative}:{node.lineno} publishes a protected device identifier"
                    )
