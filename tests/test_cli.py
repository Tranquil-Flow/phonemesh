import json
import os
import subprocess
import sys

import pytest

from phonemesh.cli import main
from phonemesh.transport import MeshtasticTransport


def run(*args):
    return subprocess.run(
        [sys.executable, "-m", "phonemesh", *args],
        capture_output=True,
        text=True,
        timeout=10,
        env={**os.environ, "HF_HUB_OFFLINE": "1"},
    )


def test_languages_and_simulation_installed_entrypoint():
    listed = run("languages")
    assert listed.returncode == 0, listed.stderr
    languages = json.loads(listed.stdout)
    assert len(languages) == 9
    assert len({lang["whisper"] for lang in languages}) == 8
    result = run("simulate", "--language", "fr", "--text", "Bonjour à tous.")
    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["received"] == {"language": "fr", "text": "Bonjour à tous."}
    assert data["mode"] == "simulated-text-only"
    assert data["receipt"]["status"] == "submitted"


def test_simulated_loss_has_nonzero_exit_and_no_delivery_claim():
    result = run("simulate", "--language", "en", "--text", "Hello", "--loss", "1", "--timeout", "0")
    assert result.returncode == 3
    assert json.loads(result.stdout)["received"] is None


def test_no_confirmation_never_opens_device(monkeypatch, capsys):
    def fail(*args, **kwargs):
        pytest.fail("hardware opened without confirmation")

    monkeypatch.setattr(MeshtasticTransport, "serial", fail)
    with pytest.raises(SystemExit) as error:
        main(
            [
                "send",
                "--text",
                "Bonjour",
                "--language",
                "fr",
                "--device",
                "/dev/not-real",
                "--channel",
                "0",
                "--to",
                "^all",
            ]
        )
    assert error.value.code == 2
    assert "confirm" in capsys.readouterr().err


def test_core_cli_does_not_import_speech_engines():
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "from phonemesh.cli import main; import sys; "
            "main(['languages']); "
            "assert 'torch' not in sys.modules; "
            "assert 'kokoro' not in sys.modules; "
            "assert 'faster_whisper' not in sys.modules",
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert result.returncode == 0, result.stderr
