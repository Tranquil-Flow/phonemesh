import importlib.util
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/run_instrumentation.py"
spec = importlib.util.spec_from_file_location("run_instrumentation", SCRIPT)
ri = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ri)

PIXEL = "OWNER-PHONE-1"
FAIRPHONE = "OWNER-PHONE-2"
NATIVE = "org.phonemesh.app.NativeSpeechTest"
COMPARISON = "org.phonemesh.app.SpeechComparisonTest"


def test_emulator_runs_anything():
    assert ri.refusal("emulator-5584", NATIVE, ["engine=x.zip"], None) is None


def test_physical_phone_without_opt_in_keeps_the_old_rule():
    assert ri.refusal(FAIRPHONE, COMPARISON, [], None) is None
    assert ri.refusal(FAIRPHONE, NATIVE, [], None)
    assert ri.refusal(FAIRPHONE, COMPARISON, ["engine=x.zip"], None)


def test_explicitly_named_phone_may_run_any_class_with_arguments():
    assert ri.refusal(FAIRPHONE, NATIVE, ["engine=x.zip"], FAIRPHONE) is None


def test_opt_in_covers_only_the_named_serial():
    assert ri.refusal("OTHER123", NATIVE, [], FAIRPHONE)


def test_the_pixel_is_always_refused():
    for classes, args, allow in [
        (COMPARISON, [], None),
        (NATIVE, [], PIXEL),
        (COMPARISON, [], PIXEL),
    ]:
        assert ri.refusal(PIXEL, classes, args, allow)


def test_opt_in_cannot_name_an_emulator():
    assert ri.refusal("emulator-5584", NATIVE, [], "emulator-5584")


def test_refused_run_exits_before_adb(tmp_path):
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--serial", PIXEL, "--classes", COMPARISON]
        + ["--expected", "1", "--receipt", str(tmp_path / "r.txt"), "--allow-device", PIXEL],
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0
    assert "refused" in result.stderr
    assert not (tmp_path / "r.txt").exists()
