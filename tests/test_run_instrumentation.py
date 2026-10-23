import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/run_instrumentation.py"
spec = importlib.util.spec_from_file_location("run_instrumentation", SCRIPT)
ri = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ri)

PIXEL = "test-protected-pixel"
FAIRPHONE = "test-authorized-phone"
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


def test_pixel_speech_classes_remain_refused():
    for classes, args, allow in [
        (COMPARISON, [], None),
        (NATIVE, [], PIXEL),
        (COMPARISON, [], PIXEL),
    ]:
        assert ri.refusal(PIXEL, classes, args, allow)


def test_opt_in_cannot_name_an_emulator():
    assert ri.refusal("emulator-5584", NATIVE, [], "emulator-5584")


def test_refused_run_exits_before_adb(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "argv", main_argv(tmp_path, PIXEL, COMPARISON, [], PIXEL))
    with pytest.raises(SystemExit, match="refused"):
        ri.main()
    assert not (tmp_path / "must-not-create").exists()


ACCOUNT_CLASSES = (
    "org.phonemesh.app.AccountStoreTest",
    "org.phonemesh.app.AccountsFlowTest",
)


def pixel_refusals():
    cases = []
    for cls in ACCOUNT_CLASSES:
        for serial, allow in [
            (PIXEL, None),
            (PIXEL, FAIRPHONE),
            (FAIRPHONE, PIXEL),
            ("emulator-5584", PIXEL),
        ]:
            cases.append((serial, cls, [], allow))
        for arg in [
            "log=true",
            "debug=true",
            "listener=x",
            "package=x",
            "notClass=x",
            "clearPackageData=true",
            "class=x",
            "",
        ]:
            cases.append((PIXEL, cls, [arg], PIXEL))
        for selector in [
            cls + "Extra",
            cls + "#unknown",
            cls + "#noSecretReachesTheLog",
            cls + "," + NATIVE,
            NATIVE + "," + cls,
            " " + cls,
            cls + " ",
            cls + ";echo x",
            cls + "\n",
        ]:
            cases.append((PIXEL, selector, [], PIXEL))
    for selector in [
        "",
        "org.phonemesh.app",
        "org.phonemesh.app.*",
        "*",
        ",".join(ACCOUNT_CLASSES),
        ",".join(reversed(ACCOUNT_CLASSES)),
        COMPARISON,
        NATIVE,
    ]:
        cases.append((PIXEL, selector, [], PIXEL))
    return cases


@pytest.fixture(autouse=True)
def no_real_subprocess(monkeypatch):
    monkeypatch.setattr(ri, "PROTECTED_PIXEL_SHA256", hashlib.sha256(PIXEL.encode()).hexdigest())

    def forbidden(*args, **kwargs):
        raise AssertionError("host policy tests must never start a subprocess")

    monkeypatch.setattr(ri.subprocess, "run", forbidden)
    monkeypatch.setattr(ri.subprocess, "Popen", forbidden)


@pytest.mark.parametrize("cls", ACCOUNT_CLASSES)
def test_exact_pixel_account_class_requires_and_accepts_exact_opt_in(cls):
    assert ri.is_protected(PIXEL)
    assert ri.refusal(PIXEL, cls, [], None)
    assert ri.refusal(PIXEL, cls, [], PIXEL) is None


@pytest.mark.parametrize("serial,cls,args,allow", pixel_refusals())
def test_pixel_scope_is_exact(serial, cls, args, allow):
    assert ri.refusal(serial, cls, args, allow)


def main_argv(tmp_path, serial, cls, args, allow):
    argv = [
        str(SCRIPT),
        "--serial",
        serial,
        "--classes",
        cls,
        "--expected",
        "8",
        "--receipt",
        str(tmp_path / "must-not-create" / "receipt.txt"),
    ]
    if allow is not None:
        argv += ["--allow-device", allow]
    for arg in args:
        argv += ["--args", arg]
    return argv


@pytest.mark.parametrize("serial,cls,args,allow", pixel_refusals())
def test_pixel_refusal_precedes_dispatch_and_receipt(
    tmp_path, monkeypatch, serial, cls, args, allow
):
    monkeypatch.setattr(sys, "argv", main_argv(tmp_path, serial, cls, args, allow))

    def forbidden(*args, **kwargs):
        raise AssertionError("refused main must not dispatch")

    monkeypatch.setattr(ri, "run", forbidden)
    with pytest.raises(SystemExit) as error:
        ri.main()
    assert error.value.code != 0
    assert not (tmp_path / "must-not-create").exists()


@pytest.mark.parametrize("cls", ACCOUNT_CLASSES)
def test_pixel_main_dispatches_only_exact_authorized_class(tmp_path, monkeypatch, cls):
    monkeypatch.setattr(sys, "argv", main_argv(tmp_path, PIXEL, cls, [], PIXEL))
    seen = []
    monkeypatch.setattr(
        ri, "run", lambda a, extra: seen.append((a.serial, a.classes, a.args, extra))
    )
    ri.main()
    assert seen == [(PIXEL, cls, [], [])]
    assert not (tmp_path / "must-not-create").exists()


@pytest.mark.parametrize(
    "output,rc,ok",
    [
        ("OK (8 tests)\n", 0, True),
        ("OK (7 tests)\n", 0, False),
        ("OK (0 tests)\n", 0, False),
        ("OK (8 tests)\n", 1, False),
        ("FAILURES!!!\nOK (8 tests)\n", 0, False),
        ("INSTRUMENTATION_FAILED\nOK (8 tests)\n", 0, False),
        ("", 0, False),
    ],
)
def test_run_command_and_result_are_checked(tmp_path, monkeypatch, capsys, output, rc, ok):
    from argparse import Namespace

    a = Namespace(
        serial=PIXEL,
        classes=ACCOUNT_CLASSES[0],
        args=[],
        expected=8,
        receipt=tmp_path / "result.txt",
        timeout=17,
    )
    calls = []

    def fake(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, rc, output)

    monkeypatch.setattr(ri.subprocess, "run", fake)
    if ok:
        ri.run(a, [])
    else:
        with pytest.raises(SystemExit):
            ri.run(a, [])
    assert len(calls) == 1
    assert calls[0][0] == [
        "adb",
        "-s",
        PIXEL,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        ACCOUNT_CLASSES[0],
        "org.phonemesh.app.test/androidx.test.runner.AndroidJUnitRunner",
    ]
    assert calls[0][1] == dict(
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=17
    )
    assert a.receipt.read_text() == output
    assert json.loads(capsys.readouterr().out.splitlines()[0])["passed"] is ok


def test_timeout_preserves_marker_and_only_stops_same_app_and_serial(tmp_path, monkeypatch):
    from argparse import Namespace

    a = Namespace(
        serial=PIXEL,
        classes=ACCOUNT_CLASSES[0],
        args=[],
        expected=8,
        receipt=tmp_path / "result.txt",
        timeout=17,
    )
    calls = []

    def fake(command, **kwargs):
        calls.append((command, kwargs))
        if len(calls) == 1:
            raise subprocess.TimeoutExpired(command, 17, output=b"partial\n")
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(ri.subprocess, "run", fake)
    with pytest.raises(subprocess.TimeoutExpired):
        ri.run(a, [])
    assert a.receipt.read_text() == "partial\n\nHARNESS_TIMEOUT\n"
    assert len(calls) == 2
    assert calls[1] == (
        ["adb", "-s", PIXEL, "shell", "am", "force-stop", "org.phonemesh.app"],
        dict(check=False, timeout=60),
    )
