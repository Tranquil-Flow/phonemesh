import importlib.util
import zipfile
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/check_network_boundary.py"
spec = importlib.util.spec_from_file_location("nb", SCRIPT)
nb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nb)

PACK = {"Lorg/phonemesh/app/PackDownloads;"}

DUMP = """Class #0            -
  Class descriptor  : 'Lorg/phonemesh/app/PackDownloads;'
      0000: invoke-virtual {v0, v1}, Landroid/app/DownloadManager;.enqueue:(Lx;)J
Class #1            -
  Class descriptor  : 'Lorg/phonemesh/app/MainActivity;'
      0000: invoke-virtual {v0}, Ljava/net/URL;.openConnection:()Ljava/net/URLConnection;
Class #2            -
  Class descriptor  : 'Lcom/k2fsa/sherpa/onnx/Foo;'
      0000: new-instance v0, Ljava/net/Socket; // type@0003
"""

# Real dexdump -d shape: hex bytes, then '|offset: instruction'.
REAL_DUMP = """Class #7            -
  Class descriptor  : 'Lorg/phonemesh/app/Other;'
    #0              : (in Lorg/phonemesh/app/Other;)
      code          -
023478: 6e10 a001 1000  |0004: invoke-virtual {v0}, Ljava/net/URL;.openStream:()Lx;
"""


def test_only_packdownloads_may_use_network_apis():
    found = nb.violations(DUMP, allowed_callers=PACK, allowlist=set())
    assert ("Lorg/phonemesh/app/MainActivity;", "Ljava/net/URL;.openConnection") in found
    assert ("Lcom/k2fsa/sherpa/onnx/Foo;", "Ljava/net/Socket;") in found
    assert not any(c == "Lorg/phonemesh/app/PackDownloads;" for c, _ in found)


def test_reviewed_library_reference_can_be_allowlisted():
    allow = {"Lcom/k2fsa/sherpa/onnx/Foo; Ljava/net/Socket;"}
    found = nb.violations(DUMP, allowed_callers=PACK, allowlist=allow)
    assert found == [("Lorg/phonemesh/app/MainActivity;", "Ljava/net/URL;.openConnection")]


def _dump_for(caller):
    return f"""Class #0            -
  Class descriptor  : '{caller}'
      0000: new-instance v0, Ljava/net/Socket; // type@0003
"""


def test_inner_companion_and_lambda_classes_of_packdownloads_are_allowed():
    for caller in (
        "Lorg/phonemesh/app/PackDownloads$Companion;",
        "Lorg/phonemesh/app/PackDownloads$collect$1;",
    ):
        assert nb.violations(_dump_for(caller), allowed_callers=PACK, allowlist=set()) == []


def test_similarly_named_class_is_not_allowed():
    caller = "Lorg/phonemesh/app/PackDownloadsEvil;"
    found = nb.violations(_dump_for(caller), allowed_callers=PACK, allowlist=set())
    assert found == [(caller, "Ljava/net/Socket;")]


def test_real_dexdump_instruction_lines_are_scanned():
    found = nb.violations(REAL_DUMP, allowed_callers=PACK, allowlist=set())
    assert found == [("Lorg/phonemesh/app/Other;", "Ljava/net/URL;.openStream")]


def test_allowlist_file_ignores_comments_and_blank_lines(tmp_path):
    f = tmp_path / "a.txt"
    f.write_text("# why\n\nLfoo/Bar; Ljava/net/Socket;  # trailing\n")
    assert nb.load_allowlist(f) == {"Lfoo/Bar; Ljava/net/Socket;"}


def test_apk_without_dex_fails(tmp_path):

    apk = tmp_path / "a.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("assets/classes.dex", "x")
        z.writestr("classes.txt", "x")
    with pytest.raises(nb.ScanError):
        nb.dump_apk(apk, "unused")


def test_empty_dump_fails():
    with pytest.raises(nb.ScanError):
        nb.check_scanned("Processing 'classes.dex'...\n", [])


def test_required_class_missing_fails_and_present_passes():
    with pytest.raises(nb.ScanError):
        nb.check_scanned(DUMP, ["Lorg/phonemesh/app/Nope;"])
    nb.check_scanned(DUMP, ["Lorg/phonemesh/app/PackDownloads;"])
