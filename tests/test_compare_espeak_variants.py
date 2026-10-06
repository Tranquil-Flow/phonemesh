import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/compare_espeak_variants.py"
spec = importlib.util.spec_from_file_location("compare_espeak_variants", SCRIPT)
cv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cv)


def receipt(variant, pid, hashes, shared=False):
    return {
        "variant": variant,
        "pid": pid,
        "sharedCopyPresent": shared,
        "rows": [{"text": f"s{i}", "sha256": h, "samples": 10} for i, h in enumerate(hashes)],
    }


def good():
    return {
        "fr-full": receipt("fr-full", 1, ["a", "b"]),
        "fr-trimmed": receipt("fr-trimmed", 2, ["a", "b"]),
        "en-full": receipt("en-full", 3, ["c", "d"]),
        "en-trimmed": receipt("en-trimmed", 4, ["c", "d"]),
    }


def test_identical_hashes_in_separate_processes_pass():
    assert cv.problems(good()) == []


def test_a_different_sample_hash_fails():
    results = good()
    results["en-trimmed"] = receipt("en-trimmed", 4, ["c", "x"])
    assert any("en" in p and "s1" in p for p in cv.problems(results))


def test_variants_sharing_a_process_fail():
    results = good()
    results["fr-trimmed"]["pid"] = 1
    assert any("process" in p for p in cv.problems(results))


def test_a_present_shared_copy_fails():
    results = good()
    results["fr-full"]["sharedCopyPresent"] = True
    assert any("shared" in p for p in cv.problems(results))


def test_missing_variant_or_rows_fail():
    results = good()
    del results["en-full"]
    assert any("en-full" in p for p in cv.problems(results))
    results = good()
    results["fr-trimmed"]["rows"] = results["fr-trimmed"]["rows"][:1]
    assert cv.problems(results)


def test_only_an_emulator_is_accepted():
    with pytest.raises(SystemExit):
        cv.require_emulator("OWNER-PHONE-1")
    cv.require_emulator("emulator-5584")
