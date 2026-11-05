"""Owner signing inputs: isolated source-ZIP build and signed-APK checks (signing_inputs.py).

External tools (Gradle, the hub's check-baseline, scripts/check.sh, aapt2, apksigner, zipalign)
are replaced by a fake runner; source ZIPs are real `git archive` output and tree ids are computed
by real git. The real tools are exercised separately on the build machine (a TEST-root refusal
and a disposable-key positive control).
"""

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "android/scripts/signing_inputs.py"
spec = importlib.util.spec_from_file_location("signing_inputs", SCRIPT)
si = importlib.util.module_from_spec(spec)
spec.loader.exec_module(si)

RELEASE_CERT = "ef8557c6133a62388c5263cc26552d4ed3b036acce267a0b841a57bbdf5ecdac"
OTHER_CERT = "11" * 32
ABIS = ("arm64-v8a", "armeabi-v7a")
KEYS = "android/core/src/main/resources/update/public-keys.txt"
BASELINE = "android/core/src/main/resources/update/baseline-catalog.pmup"
TEST_KEYS_REFUSAL = (
    "refused: android/core/src/main/resources/update/public-keys.txt holds TEST keys, "
    "which anyone can sign with; "
    "a release build refuses them. Check the owner's own public-keys.txt"
)
GIT_ENV = {
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "t",
    "GIT_AUTHOR_EMAIL": "t@example.invalid",
    "GIT_COMMITTER_NAME": "t",
    "GIT_COMMITTER_EMAIL": "t@example.invalid",
}


def git(cwd, *args):
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")} | GIT_ENV
    return subprocess.check_output(["git", *args], cwd=cwd, env=env, text=True).strip()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_repo(
    tmp_path, keys="catalog key_id=1 public_key=02aa\nrecovery key_id=100 public_key=03bb\n"
):
    repo = tmp_path / "repo"
    files = {
        KEYS: keys,
        BASELINE: "baseline-bytes",
        "android/hub/update-baseline.list": "epoch = 1\n",
        "android/gradle.properties": (
            "phonemesh.maxApkBytes.arm64-v8a=16803000\nphonemesh.maxApkBytes.armeabi-v7a=14821000\n"
            "phonemesh.maxSignedApkBytes.arm64-v8a=16901233\nphonemesh.maxSignedApkBytes.armeabi-v7a=14890111\n"
        ),
        "android/scripts/fetch-runtime.py": "print('fetch')\n",
        "scripts/check.sh": "#!/usr/bin/env bash\necho CHECK_OK\n",
        "android/gradlew": "#!/bin/sh\n",
        "README.md": "source\n",
    }
    for name, text in files.items():
        p = repo / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    (repo / "android/gradlew").chmod(0o755)
    (repo / "scripts/check.sh").chmod(0o755)
    git(tmp_path, "init", "-q", str(repo))
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "release")
    return repo


def archive(repo, out):
    git(repo, "archive", "--format=zip", "-o", str(out), "HEAD")
    return git(repo, "rev-parse", "HEAD"), git(repo, "rev-parse", "HEAD^{tree}")


def fake_apk(path, abi, source, *, extra=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", b"manifest")
        z.writestr("classes.dex", b"dex" * 100)
        z.writestr(f"lib/{abi}/libphonemesh.so", b"so-" + abi.encode())
        z.writestr("update/public-keys.txt", (source / KEYS).read_bytes())
        z.writestr("update/baseline-catalog.pmup", (source / BASELINE).read_bytes())
        for name, data in (extra or {}).items():
            z.writestr(name, data)


def badging(abi, *, package="org.phonemesh.app", code="3", name="0.1.2"):
    return (
        f"package: name='{package}' versionCode='{code}' versionName='{name}' "
        "platformBuildVersionName='15'\n"
        "minSdkVersion:'26'\ntargetSdkVersion:'35'\n"
        f"native-code: '{abi}'\n"
    )


def apksigner_text(cert=RELEASE_CERT, signers=1, v2="true", v3="true"):
    lines = [
        "Verifies",
        "Verified using v1 scheme (JAR signing): false",
        f"Verified using v2 scheme (APK Signature Scheme v2): {v2}",
        f"Verified using v3 scheme (APK Signature Scheme v3): {v3}",
        f"Number of signers: {signers}",
    ]
    for n in range(1, signers + 1):
        lines += [
            f"Signer #{n} certificate DN: CN=Owner",
            f"Signer #{n} certificate SHA-256 digest: {cert}",
        ]
    return "\n".join(lines) + "\n"


class Fake:
    """Stands in for every external program; records the order of calls."""

    def __init__(self, **kw):
        self.calls = []
        self.baseline = kw.get(
            "baseline", (0, "OK: belong together.\nKeys: catalog key id 1, recovery key id 100.")
        )
        self.gate = kw.get("gate", (0, "...\nCHECK_OK\n"))
        self.apk_extra = kw.get("apk_extra", {})
        self.apk_keys = kw.get("apk_keys")
        self.badging = kw.get("badging", {})
        self.signer = kw.get("signer", (0, apksigner_text()))
        self.zipalign = kw.get("zipalign", 0)
        self.junit = kw.get("junit", (12, 0, 0))

    def kinds(self):
        return [kind for kind, _ in self.calls]

    def __call__(self, cmd, cwd, env, log=None):
        words = [str(c) for c in cmd]
        tool = Path(words[0]).name
        if tool == "java":
            kind, result = "java", (0, 'openjdk version "17.0.17"')
        elif any(w.endswith("fetch-runtime.py") for w in words):
            kind, result = "runtime", (0, "Verified")
        elif tool == "gradlew":
            kind, result = "hub-build", (0, "BUILD SUCCESSFUL")
        elif "check-baseline" in words:
            kind, result = "check-baseline", self.baseline
            assert "--packs" in words and env.get("JAVA_HOME")
        elif words[:2] == ["bash", "scripts/check.sh"]:
            kind, result = "gate", self.gate
            self._outputs(Path(cwd))
        elif tool == "aapt2":
            apk = Path(words[-1])
            abi = next(a for a in ABIS if a in apk.name)
            kind, result = "aapt2", (0, self.badging.get(abi, badging(abi)))
        elif tool == "apksigner":
            kind, result = "apksigner", self.signer
        elif tool == "zipalign":
            kind, result = "zipalign", (self.zipalign, "")
        else:
            raise AssertionError(f"unexpected command {words}")
        self.calls.append((kind, words))
        if log is not None:
            Path(log).write_text(result[1])
        return result

    def _outputs(self, source):
        release = source / "android/app/build/outputs/apk/release"
        for abi in ABIS:
            fake_apk(
                release / f"app-{abi}-release-unsigned.apk",
                abi,
                source,
                extra=self.apk_extra.get(abi),
            )
            if self.apk_keys:
                rewrite_entry(
                    release / f"app-{abi}-release-unsigned.apk",
                    "update/public-keys.txt",
                    self.apk_keys,
                )
        hub = source / "android/hub/build/distributions/phonemesh-hub.zip"
        hub.parent.mkdir(parents=True, exist_ok=True)
        hub.write_bytes(b"PK-hub")
        tests, failures, errors = self.junit
        results = source / "android/core/build/test-results/test"
        results.mkdir(parents=True, exist_ok=True)
        (results / "TEST-x.xml").write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="0">'
            "</testsuite>"
        )


def rewrite_entry(apk, name, data):
    with zipfile.ZipFile(apk) as z:
        entries = [(i, z.read(i.filename)) for i in z.infolist()]
    with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as z:
        for info, body in entries:
            z.writestr(info.filename, data if info.filename == name else body)


@pytest.fixture
def env(tmp_path):
    sdk = tmp_path / "sdk/build-tools/36.0.0"
    sdk.mkdir(parents=True)
    for tool in ("aapt2", "apksigner", "zipalign"):
        (sdk / tool).write_text("")
    (tmp_path / "sdk/build-tools/9.0.0").mkdir()
    jdk = tmp_path / "jdk/bin"
    jdk.mkdir(parents=True)
    return {
        "JAVA_HOME": str(tmp_path / "jdk"),
        "ANDROID_HOME": str(tmp_path / "sdk"),
        "PATH": os.environ["PATH"],
    }


@pytest.fixture
def source(tmp_path):
    repo = source_repo(tmp_path)
    commit, tree = archive(repo, tmp_path / "src.zip")
    packs = tmp_path / "packs"
    packs.mkdir()
    return {
        "zip": tmp_path / "src.zip",
        "commit": commit,
        "tree": tree,
        "packs": packs,
        "repo": repo,
    }


def build_args(src, out, *extra):
    return [
        "build",
        "--source-zip", str(src["zip"]), "--commit", src["commit"], "--tree", src["tree"],
        "--packs", str(src["packs"]), "--out", str(out), *extra,
    ]  # fmt: skip


def make_kit(tmp_path, src, env, fake=None, *extra):
    out = tmp_path / "kit"
    fake = fake or Fake()
    assert si.main(build_args(src, out, *extra), run=fake, environ=env) == 0
    return out


# ---- build ----


def test_builds_bound_signing_inputs_from_the_source_zip(tmp_path, source, env, capsys):
    fake = Fake()
    out = make_kit(tmp_path, source, env, fake)
    printed = capsys.readouterr().out
    assert fake.kinds()[:5] == ["java", "runtime", "hub-build", "check-baseline", "gate"]
    receipt = json.loads((out / "build-receipt.json").read_text())
    assert receipt["provisional"] is False and receipt["final_signing_input"] is True
    assert (
        receipt["source"]["commit"] == source["commit"]
        and receipt["source"]["tree"] == source["tree"]
    )
    assert receipt["source"]["sha256"] == sha(source["zip"])
    assert (receipt["package"], receipt["version_name"], receipt["version_code"]) == (
        "org.phonemesh.app",
        "0.1.2",
        3,
    )
    assert receipt["release_certificate_sha256"] == RELEASE_CERT
    assert receipt["gate"]["check_ok"] is True and receipt["gate"]["junit"]["tests"] == 12
    for abi, ceiling in (("arm64-v8a", 16901233), ("armeabi-v7a", 14890111)):
        apk = receipt["apks"][abi]
        assert apk["unsigned"] == f"unsigned/phonemesh-0.1.2-{abi}-unsigned.apk"
        assert apk["signed_name"] == f"phonemesh-0.1.2-{abi}.apk"
        assert apk["signed_ceiling_bytes"] == ceiling
        assert apk["sha256"] == sha(out / apk["unsigned"])
        assert apk["sha256"] in printed
    assert receipt["update_trust"]["public_keys_sha256"] == sha(source["repo"] / KEYS)
    assert (out / "phonemesh-0.1.2-source.zip").read_bytes() == source["zip"].read_bytes()
    signing = (out / "SIGNING.md").read_text()
    assert sha(source["repo"] / KEYS) in signing and RELEASE_CERT in signing
    # apksigner writes .idsig (v4) files by default; a release needs neither them nor v4.
    assert signing.count("--v4-signing-enabled false") == 2
    assert "Send back only the two `.apk` files" in signing
    assert not (out / "NOT-SIGNING-INPUTS.txt").exists()
    sums = dict(
        reversed(line.split("  ", 1)) for line in (out / "SHA256SUMS").read_text().splitlines()
    )
    files = {p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file()} - {"SHA256SUMS"}
    assert set(sums) == files and all(sha(out / f) == sums[f] for f in files)


def test_test_update_keys_are_refused_before_the_apks_are_built(tmp_path, source, env, capsys):
    fake = Fake(baseline=(2, TEST_KEYS_REFUSAL))
    out = tmp_path / "kit"
    assert si.main(build_args(source, out), run=fake, environ=env) == 2
    err = capsys.readouterr().err
    assert "cannot make signing inputs" in err and "TEST keys" in err
    assert "gate" not in fake.kinds()
    assert not out.exists()


def test_provisional_inputs_are_marked_and_carry_no_signing_instructions(
    tmp_path, source, env, capsys
):
    fake = Fake(baseline=(2, TEST_KEYS_REFUSAL))
    out = make_kit(tmp_path, source, env, fake, "--provisional")
    receipt = json.loads((out / "build-receipt.json").read_text())
    assert receipt["provisional"] is True and receipt["final_signing_input"] is False
    assert "TEST keys" in receipt["update_trust"]["check_baseline_output"]
    assert receipt["update_trust"]["check_baseline_exit"] == 2
    assert not (out / "SIGNING.md").exists()
    assert "Do not sign" in (out / "NOT-SIGNING-INPUTS.txt").read_text()
    assert receipt["apks"]["arm64-v8a"]["unsigned"].endswith("-provisional-unsigned.apk")
    assert "PROVISIONAL" in capsys.readouterr().out


def test_provisional_is_refused_for_sources_that_pass_the_key_check(tmp_path, source, env, capsys):
    out = tmp_path / "kit"
    assert si.main(build_args(source, out, "--provisional"), run=Fake(), environ=env) == 2
    assert "--provisional is only for sources that still carry TEST keys" in capsys.readouterr().err
    assert not out.exists()


def test_zip_that_is_not_an_archive_of_the_commit_is_refused(tmp_path, source, env, capsys):
    other = dict(source, commit="0" * 40)
    fake = Fake()
    assert si.main(build_args(other, tmp_path / "kit"), run=fake, environ=env) == 2
    assert f"not `git archive` of commit {'0' * 40}" in capsys.readouterr().err
    assert fake.calls == []


def test_extracted_tree_must_match_the_commit_tree(tmp_path, source, env, capsys):
    wrong = dict(source, tree="f" * 40)
    fake = Fake()
    assert si.main(build_args(wrong, tmp_path / "kit"), run=fake, environ=env) == 2
    assert f"is tree {source['tree']}, not {'f' * 40}" in capsys.readouterr().err
    assert fake.calls == []


def test_tree_id_keeps_executable_bits(tmp_path, source, env):
    work = tmp_path / "work"
    si.main(build_args(source, tmp_path / "kit", "--work", str(work)), run=Fake(), environ=env)
    assert os.access(work / "source/android/gradlew", os.X_OK)
    assert not os.access(work / "source/README.md", os.X_OK)


@pytest.mark.parametrize(
    "name, message",
    [
        ("../escape.txt", "unsafe path"),
        ("/abs.txt", "unsafe path"),
        ("a/.git/config", "unsafe path"),
    ],
)
def test_unsafe_zip_entries_are_refused(tmp_path, source, env, capsys, name, message):
    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(source["zip"]) as z, zipfile.ZipFile(bad, "w") as out:
        out.comment = z.comment
        for info in z.infolist():
            out.writestr(info, z.read(info.filename))
        out.writestr(name, b"x")
    assert (
        si.main(build_args(dict(source, zip=bad), tmp_path / "kit"), run=Fake(), environ=env) == 2
    )
    assert message in capsys.readouterr().err
    assert not (tmp_path / "escape.txt").exists()


def test_symlink_entries_are_refused(tmp_path, source, env, capsys):
    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(source["zip"]) as z, zipfile.ZipFile(bad, "w") as out:
        out.comment = z.comment
        for info in z.infolist():
            out.writestr(info, z.read(info.filename))
        link = zipfile.ZipInfo("link")
        link.external_attr = (0o120777 << 16) | 0
        link.create_system = 3
        out.writestr(link, "/etc/passwd")
    assert (
        si.main(build_args(dict(source, zip=bad), tmp_path / "kit"), run=Fake(), environ=env) == 2
    )
    assert "symbolic link" in capsys.readouterr().err


def test_existing_output_folder_is_refused(tmp_path, source, env, capsys):
    out = tmp_path / "kit"
    out.mkdir()
    fake = Fake()
    assert si.main(build_args(source, out), run=fake, environ=env) == 2
    assert "is not a new folder" in capsys.readouterr().err
    assert fake.calls == []


def test_failed_gate_writes_nothing(tmp_path, source, env, capsys):
    out = tmp_path / "kit"
    fake = Fake(gate=(1, "FAILURE: Build failed\n"))
    assert si.main(build_args(source, out), run=fake, environ=env) == 2
    assert "the canonical gate failed in the isolated source" in capsys.readouterr().err
    assert not out.exists()


def test_gate_without_check_ok_or_with_failed_tests_is_refused(tmp_path, source, env, capsys):
    assert (
        si.main(build_args(source, tmp_path / "a"), run=Fake(gate=(0, "done\n")), environ=env) == 2
    )
    assert "the canonical gate failed in the isolated source" in capsys.readouterr().err
    assert si.main(build_args(source, tmp_path / "b"), run=Fake(junit=(12, 1, 0)), environ=env) == 2
    assert "1 failures, 0 errors" in capsys.readouterr().err


def test_apk_must_carry_the_checked_update_keys(tmp_path, source, env, capsys):
    out = tmp_path / "kit"
    fake = Fake(apk_keys=b"catalog key_id=1 public_key=other\n")
    assert si.main(build_args(source, out), run=fake, environ=env) == 2
    assert "update/public-keys.txt in the APK is not the checked" in capsys.readouterr().err
    assert not out.exists()


def test_apk_with_another_abis_libraries_is_refused(tmp_path, source, env, capsys):
    fake = Fake(apk_extra={"arm64-v8a": {"lib/x86_64/libphonemesh.so": b"x"}})
    assert si.main(build_args(source, tmp_path / "kit"), run=fake, environ=env) == 2
    assert "carries libraries for lib/x86_64" in capsys.readouterr().err


def test_already_signed_apk_is_refused_as_input(tmp_path, source, env, capsys):
    fake = Fake(apk_extra={"armeabi-v7a": {"META-INF/CERT.RSA": b"sig"}})
    assert si.main(build_args(source, tmp_path / "kit"), run=fake, environ=env) == 2
    assert "is already signed" in capsys.readouterr().err


@pytest.mark.parametrize(
    "bad, message",
    [
        (badging("arm64-v8a", package="org.phonemesh.app.demo"), "package org.phonemesh.app.demo"),
        (badging("arm64-v8a", name="0.1.2-demo"), "not a release version"),
        (badging("armeabi-v7a"), "native code armeabi-v7a"),
    ],
)
def test_badging_must_be_the_release_app_for_its_abi(tmp_path, source, env, capsys, bad, message):
    fake = Fake(badging={"arm64-v8a": bad})
    assert si.main(build_args(source, tmp_path / "kit"), run=fake, environ=env) == 2
    assert message in capsys.readouterr().err


def test_missing_signed_ceiling_is_refused(tmp_path, env, capsys):
    repo = source_repo(tmp_path)
    props = repo / "android/gradle.properties"
    props.write_text(
        props.read_text().replace("phonemesh.maxSignedApkBytes.armeabi-v7a=14890111\n", "")
    )
    git(repo, "commit", "-q", "-am", "drop")
    commit, tree = archive(repo, tmp_path / "src.zip")
    packs = tmp_path / "packs"
    packs.mkdir()
    src = {"zip": tmp_path / "src.zip", "commit": commit, "tree": tree, "packs": packs}
    assert si.main(build_args(src, tmp_path / "kit"), run=Fake(), environ=env) == 2
    assert "phonemesh.maxSignedApkBytes.armeabi-v7a" in capsys.readouterr().err


# ---- verify ----


def sign_like_apksigner(unsigned, signed, *, extra=None, drop=None, change=None):
    """Copies every entry (as apksigner does) and adds JAR-signature files; optionally tampers."""
    signed.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(unsigned) as u, zipfile.ZipFile(signed, "w", zipfile.ZIP_DEFLATED) as s:
        for info in u.infolist():
            if info.filename == drop:
                continue
            body = u.read(info.filename)
            s.writestr(info.filename, change if info.filename == "classes.dex" and change else body)
        s.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        s.writestr("META-INF/OWNER.SF", b"sf")
        s.writestr("META-INF/OWNER.RSA", b"rsa")
        for name, data in (extra or {}).items():
            s.writestr(name, data)


def signed_folder(tmp_path, kit, **tamper):
    receipt = json.loads((kit / "build-receipt.json").read_text())
    folder = tmp_path / "signed"
    for abi in ABIS:
        apk = receipt["apks"][abi]
        sign_like_apksigner(
            kit / apk["unsigned"],
            folder / apk["signed_name"],
            **(tamper if abi == "arm64-v8a" else {}),
        )
    return folder


def verify_args(kit, signed, *extra):
    return ["verify", "--inputs", str(kit), "--signed", str(signed), *extra]


def test_verifies_owner_signed_apks_against_their_inputs(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    capsys.readouterr()
    fake = Fake()
    receipt_path = tmp_path / "verified.json"
    assert (
        si.main(verify_args(kit, signed, "--receipt", str(receipt_path)), run=fake, environ=env)
        == 0
    )
    out = capsys.readouterr().out
    for abi in ABIS:
        assert f"{sha(signed / f'phonemesh-0.1.2-{abi}.apk')}  phonemesh-0.1.2-{abi}.apk" in out
    assert "the Phonemesh release certificate" in out
    assert "CN=Owner" not in out
    assert sorted(set(fake.kinds())) == ["aapt2", "apksigner", "zipalign"]
    assert any("/36.0.0/" in words[0] for _, words in fake.calls)
    result = json.loads(receipt_path.read_text())
    assert result["release_identity"] is True and result["source"]["commit"] == source["commit"]


def test_another_certificate_is_refused(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    fake = Fake(signer=(0, apksigner_text(cert=OTHER_CERT)))
    assert si.main(verify_args(kit, signed), run=fake, environ=env) == 2
    assert f"signed by certificate {OTHER_CERT}, not {RELEASE_CERT}" in capsys.readouterr().err


def test_a_named_test_certificate_is_labelled_not_a_release(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    capsys.readouterr()
    fake = Fake(signer=(0, apksigner_text(cert=OTHER_CERT)))
    assert (
        si.main(verify_args(kit, signed, "--certificate-sha256", OTHER_CERT), run=fake, environ=env)
        == 0
    )
    assert "NOT the Phonemesh release certificate" in capsys.readouterr().out


@pytest.mark.parametrize(
    "signer, message",
    [
        ((1, "DOES NOT VERIFY\nERROR: bad digest\n"), "does not verify"),
        ((0, apksigner_text(signers=2)), "2 signers"),
        ((0, apksigner_text(v2="false", v3="false")), "neither APK Signature Scheme v2 nor v3"),
    ],
)
def test_apksigner_results_must_be_one_modern_signature(
    tmp_path, source, env, capsys, signer, message
):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    assert si.main(verify_args(kit, signed), run=Fake(signer=signer), environ=env) == 2
    assert message in capsys.readouterr().err


def test_provisional_inputs_are_never_verified_as_signing_inputs(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env, Fake(baseline=(2, TEST_KEYS_REFUSAL)), "--provisional")
    receipt = json.loads((kit / "build-receipt.json").read_text())
    folder = tmp_path / "signed"
    for abi in ABIS:
        sign_like_apksigner(
            kit / receipt["apks"][abi]["unsigned"], folder / receipt["apks"][abi]["signed_name"]
        )
    fake = Fake()
    assert si.main(verify_args(kit, folder), run=fake, environ=env) == 2
    assert "provisional: built with TEST update keys" in capsys.readouterr().err
    assert fake.calls == []


@pytest.mark.parametrize(
    "tamper, message",
    [
        ({"change": b"other dex"}, "classes.dex differs from the unsigned input"),
        ({"drop": "update/public-keys.txt"}, "lacks update/public-keys.txt"),
        ({"extra": {"assets/added.bin": b"x"}}, "adds assets/added.bin"),
    ],
)
def test_signed_payload_must_equal_the_unsigned_input(
    tmp_path, source, env, capsys, tamper, message
):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit, **tamper)
    assert si.main(verify_args(kit, signed), run=Fake(), environ=env) == 2
    assert message in capsys.readouterr().err


def test_signed_size_over_the_ceiling_is_refused(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit, extra={"META-INF/PAD.SF": os.urandom(17_000_000)})
    assert si.main(verify_args(kit, signed), run=Fake(), environ=env) == 2
    assert "over the signed ceiling of 16901233 bytes" in capsys.readouterr().err


def test_version_or_abi_of_the_signed_apk_must_match(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    fake = Fake(badging={"arm64-v8a": badging("arm64-v8a", code="2")})
    assert si.main(verify_args(kit, signed), run=fake, environ=env) == 2
    assert "version 0.1.2 code 2, not 0.1.2 code 3" in capsys.readouterr().err


def test_unaligned_signed_apk_is_refused(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    assert si.main(verify_args(kit, signed), run=Fake(zipalign=1), environ=env) == 2
    assert "is not zip-aligned" in capsys.readouterr().err


def test_altered_or_extra_kit_files_are_refused(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    receipt = json.loads((kit / "build-receipt.json").read_text())
    unsigned = kit / receipt["apks"]["armeabi-v7a"]["unsigned"]
    original = unsigned.read_bytes()
    unsigned.write_bytes(original + b"x")
    assert si.main(verify_args(kit, signed), run=Fake(), environ=env) == 2
    assert "does not match SHA256SUMS" in capsys.readouterr().err
    unsigned.write_bytes(original)
    (kit / "unsigned/stray.apk").write_bytes(b"x")
    assert si.main(verify_args(kit, signed), run=Fake(), environ=env) == 2
    assert "unsigned/stray.apk is not in SHA256SUMS" in capsys.readouterr().err


def test_missing_signed_apk_is_refused(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    (signed / "phonemesh-0.1.2-armeabi-v7a.apk").unlink()
    assert si.main(verify_args(kit, signed), run=Fake(), environ=env) == 2
    assert "phonemesh-0.1.2-armeabi-v7a.apk is missing" in capsys.readouterr().err


def test_verify_never_writes_over_an_existing_receipt(tmp_path, source, env, capsys):
    kit = make_kit(tmp_path, source, env)
    signed = signed_folder(tmp_path, kit)
    existing = tmp_path / "verified.json"
    existing.write_text("keep")
    assert (
        si.main(verify_args(kit, signed, "--receipt", str(existing)), run=Fake(), environ=env) == 2
    )
    assert "already exists" in capsys.readouterr().err
    assert existing.read_text() == "keep"


def test_unknown_command_is_a_usage_error(capsys):
    with pytest.raises(SystemExit) as exit_:
        si.main(["sign"])
    assert exit_.value.code == 2
    assert "invalid choice: 'sign'" in capsys.readouterr().err


def test_work_folder_is_removed_after_a_temporary_build(tmp_path, source, env, monkeypatch):
    made = []
    real = si.tempfile.mkdtemp

    def mkdtemp(suffix=None, prefix=None, dir=None):
        path = Path(real(suffix, prefix, tmp_path if dir is None else dir))
        if prefix == "phonemesh-signing-inputs-":
            made.append(path)
        return str(path)

    monkeypatch.setattr(si.tempfile, "mkdtemp", mkdtemp)
    make_kit(tmp_path, source, env)
    assert made and not made[0].exists()
    shutil.rmtree(tmp_path / "kit")
