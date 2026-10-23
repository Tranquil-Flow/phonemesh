"""Sandboxed launch contract; the real clean-export build remains a separate gate."""

import json
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _executable(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(0o700)


def test_canonical_pytest_declares_its_radio_and_pack_dependencies(tmp_path):
    sandbox = tmp_path / "source with spaces"
    script = sandbox / "scripts/check.sh"
    _executable(script, (ROOT / "scripts/check.sh").read_text())
    calls = tmp_path / "calls.jsonl"
    stub_code = (
        "import json, sys\n"
        f"with open({str(calls)!r}, 'a') as out:\n"
        "    out.write(json.dumps(sys.argv[1:]) + '\\n')\n"
    )
    _executable(
        tmp_path / "bin/uv",
        f'#!/bin/sh\nexec {shlex.quote(sys.executable)} -c {shlex.quote(stub_code)} "$@"\n',
    )
    marker = tmp_path / "gradle-stub.txt"
    _executable(
        sandbox / "android/gradlew",
        f"#!/bin/sh\nprintf '%s\\n' stub > {shlex.quote(str(marker))}\n",
    )
    _executable(tmp_path / "jdk/bin/java", "#!/bin/sh\nexit 0\n")
    result = subprocess.run(
        ["/bin/bash", str(script)],
        cwd=tmp_path,
        env={
            "PATH": f"{tmp_path / 'bin'}:/usr/bin:/bin",
            "JAVA_HOME": str(tmp_path / "jdk"),
            "HOME": str(tmp_path),
        },
        text=True,
        capture_output=True,
        timeout=20,
    )
    assert result.returncode == 0, result.stderr
    assert marker.read_text() == "stub\n", "only the isolated Gradle stub may run"
    commands = [json.loads(line) for line in calls.read_text().splitlines()]
    pytest_commands = [args for args in commands if "pytest" in args]
    assert len(pytest_commands) == 1
    args = pytest_commands[0]
    assert "--extra" in args, "clean canonical checks must install the declared radio test extra"
    assert args[args.index("--extra") + 1] == "radio"
    assert "--group" in args and args[args.index("--group") + 1] == "packs"
