#!/usr/bin/env python3
"""Owner APK signing inputs: build them from a source ZIP in isolation; check what the owner signed.

    signing_inputs.py build --source-zip phonemesh-source.zip --commit <40 hex> --tree <40 hex>
        --packs model-packs --out <new folder>
        [--work <new folder>] [--runtime-aar sherpa-onnx-1.13.8.aar] [--provisional]
    signing_inputs.py verify --inputs <signing-inputs folder> --signed <folder of signed APKs>
        [--certificate-sha256 <64 hex>] [--receipt <new file>] [--build-tools <folder>]

build: the ZIP must be `git archive` of --commit (git stores the commit id as the ZIP comment),
and its files must hash to --tree. It is unpacked into a fresh folder, the pinned speech runtime
is fetched or checked, and the hub tool built from those sources runs `check-baseline` on the
compiled update keys and baseline. If that refuses (TEST keys, for example), nothing is built and
nothing is written: signing inputs are made only after the owner's public pair is committed
(docs/update-keys.md). --provisional builds such sources anyway, into a folder marked NOT signing
inputs that `verify` refuses. Then the canonical gate (scripts/check.sh) runs in the unpacked
sources. Each unsigned release APK is checked (package, version, ABI, no signature, the checked
update files inside) and written with the hub ZIP, the source ZIP, a build receipt, the owner's
signing steps and SHA256SUMS.

verify: checks the folder against its SHA256SUMS, then each signed APK with Android build-tools:
one signer, whose certificate is the Phonemesh release certificate (or the one named); APK
Signature Scheme v2 or v3; the package, version and ABI of its input; the signed size ceiling;
zip alignment; and the same entries and contents as its unsigned input (only JAR-signature files
may be added). It prints the SHA256SUMS lines of the signed APKs.

Nothing here reads a private key, keystore or passphrase, and nothing signs.
Exit codes: 0 done, 2 refused or misused.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

RELEASE_CERT_SHA256 = "ef8557c6133a62388c5263cc26552d4ed3b036acce267a0b841a57bbdf5ecdac"
PACKAGE = "org.phonemesh.app"
ABIS = ("arm64-v8a", "armeabi-v7a")
KEYS = "android/core/src/main/resources/update/public-keys.txt"
BASELINE = "android/core/src/main/resources/update/baseline-catalog.pmup"
BASELINE_LIST = "android/hub/update-baseline.list"
APK_UPDATE_FILES = {"update/public-keys.txt": KEYS, "update/baseline-catalog.pmup": BASELINE}
SELF = "android/scripts/signing_inputs.py"
HUB = "android/hub/build/install/phonemesh-hub/bin/phonemesh-hub"
RELEASE_OUTPUTS = "android/app/build/outputs/apk/release"
HUB_ZIP = "android/hub/build/distributions/phonemesh-hub.zip"
MAX_SOURCE_BYTES = 2 * 1024**3
SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
SIGNATURE_ENTRY = re.compile(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))")
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
RELEASE_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


class Refused(Exception):
    pass


def run_command(cmd, cwd, env, log=None):
    """Runs one program; returns (exit code, its output). With [log], output streams there."""
    if log is None:
        p = subprocess.run(
            cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
        )
        return p.returncode, p.stdout
    with open(log, "w") as out:
        p = subprocess.run(cmd, cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT)
    return p.returncode, Path(log).read_text(errors="replace")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tail(log: Path, lines: int = 40) -> str:
    """The end of a build log, for a refusal message (temporary build folders are removed)."""
    try:
        return "\n".join(log.read_text(errors="replace").splitlines()[-lines:])
    except OSError:
        return "(no log)"


def tool_env(environ) -> dict:
    env = {k: v for k, v in environ.items() if k not in ("PYTHONPATH", "PYTHONHOME")}
    if not env.get("JAVA_HOME"):
        raise Refused("set JAVA_HOME to a JDK 17 installation")
    if not env.get("ANDROID_HOME"):
        if not env.get("ANDROID_SDK_ROOT"):
            raise Refused("set ANDROID_HOME to the Android SDK")
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"]
    return env


def build_tools(env, chosen=None) -> Path:
    if chosen:
        folder = Path(chosen)
    else:
        root = Path(env["ANDROID_HOME"]) / "build-tools"
        versions = [p for p in root.iterdir() if p.is_dir()] if root.is_dir() else []

        def key(p):
            return tuple(int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", p.name))

        if not versions:
            raise Refused(f"no Android build-tools in {root}")
        folder = max(versions, key=key)
    for tool in ("aapt2", "apksigner", "zipalign"):
        if not (folder / tool).exists():
            raise Refused(f"{folder} has no {tool}")
    return folder


# ---- build ----


def unpack(source_zip: Path, commit: str, dest: Path) -> int:
    """Checks that [source_zip] is `git archive` of [commit] with plain, safe entries; unpacks."""
    try:
        z = zipfile.ZipFile(source_zip)
    except (OSError, zipfile.BadZipFile) as e:
        raise Refused(f"{source_zip} is not a readable ZIP: {e}") from e
    with z:
        comment = z.comment.decode("ascii", "replace").strip()
        if comment != commit:
            raise Refused(
                f"{source_zip} is not `git archive` of commit {commit}: "
                f"its ZIP comment says {comment!r}"
            )
        seen, total = set(), 0
        infos = z.infolist()
        for info in infos:
            name = info.filename
            parts = name.rstrip("/").split("/")
            if (
                not name
                or name.startswith("/")
                or "\\" in name
                or ":" in parts[0]
                or any(p in ("", ".", "..", ".git") for p in parts)
            ):
                raise Refused(f"{source_zip} has an unsafe path: {name!r}")
            if stat.S_ISLNK(info.external_attr >> 16):
                raise Refused(
                    f"{source_zip} has a symbolic link, {name}; release sources have none"
                )
            if name in seen:
                raise Refused(f"{source_zip} names {name} twice")
            seen.add(name)
            total += info.file_size
            if total > MAX_SOURCE_BYTES:
                raise Refused(f"{source_zip} unpacks to more than {MAX_SOURCE_BYTES} bytes")
        dest.mkdir(parents=True)
        files = 0
        for info in infos:
            target = dest / info.filename
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "xb") as out:
                shutil.copyfileobj(src, out)
            os.chmod(target, 0o755 if (info.external_attr >> 16) & 0o111 else 0o644)
            files += 1
        return files


def tree_id(root: Path) -> str:
    """The git tree id of the files under [root], as `git rev-parse <commit>^{tree}` prints it."""
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
    with tempfile.TemporaryDirectory(prefix="phonemesh-tree-") as g:
        cfg = ["-c", "core.autocrlf=false", "-c", "core.filemode=true", "-c", "core.symlinks=true"]
        base = ["git", *cfg, f"--git-dir={g}/.git", f"--work-tree={root}"]
        subprocess.run(["git", "init", "-q", g], env=env, check=True)
        subprocess.run([*base, "add", "-A", "-f", "--", "."], cwd=root, env=env, check=True)
        out = subprocess.run(
            [*base, "write-tree"], cwd=root, env=env, check=True, stdout=subprocess.PIPE, text=True
        )
        return out.stdout.strip()


def properties(path: Path) -> dict:
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            values[k.strip()] = v.strip()
    return values


def signed_ceilings(source: Path) -> dict:
    props = properties(source / "android/gradle.properties")
    ceilings = {}
    for abi in ABIS:
        name = f"phonemesh.maxSignedApkBytes.{abi}"
        value = props.get(name, "")
        if not value.isdigit():
            raise Refused(
                f"android/gradle.properties has no whole-number {name}: "
                "the signed size ceiling is needed"
            )
        ceilings[abi] = int(value)
    return ceilings


def junit(source: Path) -> dict:
    total = {"reports": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for p in sorted((source / "android").glob("**/build/test-results/**/TEST-*.xml")):
        doc = ET.parse(p).getroot()
        total["reports"] += 1
        for k in ("tests", "failures", "errors", "skipped"):
            total[k] += int(doc.attrib.get(k, 0))
    return total


def badging_of(text: str) -> dict:
    m = re.search(
        r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text, re.M
    )
    if not m:
        raise Refused("aapt2 printed no package line")
    native = re.search(r"^native-code: (.*)$", text, re.M)
    abis = re.findall(r"'([^']*)'", native.group(1)) if native else []
    return {
        "package": m.group(1),
        "version_code": m.group(2),
        "version_name": m.group(3),
        "native_code": abis,
    }


def inspect_unsigned(apk: Path, abi: str, source: Path, badging: dict) -> None:
    if badging["package"] != PACKAGE:
        raise Refused(f"{apk.name} is package {badging['package']}, not {PACKAGE}")
    if (
        not RELEASE_VERSION.fullmatch(badging["version_name"])
        or not badging["version_code"].isdigit()
    ):
        raise Refused(
            f"{apk.name} has version {badging['version_name']} code {badging['version_code']}: "
            "not a release version"
        )
    if badging["native_code"] != [abi]:
        raise Refused(
            f"{apk.name} has native code {' '.join(badging['native_code']) or 'none'}, not {abi}"
        )
    if SIGNING_BLOCK_MAGIC in apk.read_bytes():
        raise Refused(f"{apk.name} is already signed (it has an APK signing block)")
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        signed = [n for n in names if SIGNATURE_ENTRY.fullmatch(n)]
        if signed:
            raise Refused(f"{apk.name} is already signed ({signed[0]})")
        other = sorted(
            {"/".join(n.split("/")[:2]) for n in names if n.startswith("lib/")} - {f"lib/{abi}"}
        )
        if other:
            raise Refused(f"{apk.name} carries libraries for {other[0]}, not only lib/{abi}")
        for entry, path in APK_UPDATE_FILES.items():
            if entry not in names or z.read(entry) != (source / path).read_bytes():
                raise Refused(f"{entry} in the APK is not the checked {path}")


def signing_steps(receipt: dict) -> str:
    v, keys = receipt["version_name"], receipt["update_trust"]["public_keys_sha256"]
    src = receipt["source"]
    lines = [
        f"# Sign Phonemesh {v} (owner, on your own computer)",
        "",
        f"These are the unsigned release APKs of commit `{src['commit']}`",
        f"(tree `{src['tree']}`), built in a fresh folder from `{src['zip']}`.",
        "This folder holds no key. Your keystore and its passphrase never leave your computer:",
        "never send them to anyone, an agent included.",
        "",
        "1. Check the folder. Every line must say OK:",
        "",
        "   ```sh",
        "   shasum -a 256 -c SHA256SUMS",
        "   ```",
        "",
        "2. Check that these APKs carry your update keys. Their `public-keys.txt` has SHA-256",
        "",
        f"   `{keys}`",
        "",
        "   It must equal `shasum -a 256 public-keys.txt` in your offline update-key folder.",
        "   If it differs, stop: do not sign.",
        "",
        "3. Sign each APK with your release keystore, the one used for v0.1.1, with Android",
        "   build-tools 34 or newer. `apksigner` asks for the passphrase itself; never put it on",
        "   the command line.",
        "",
        "   ```sh",
        "   mkdir signed",
    ]
    for abi in ABIS:
        a = receipt["apks"][abi]
        lines.append("   apksigner sign --ks <your keystore> \\")
        lines.append(f"     --out signed/{a['signed_name']} {a['unsigned']}")
    lines += [
        "   ```",
        "",
        "4. Check each one. The certificate SHA-256 must be",
        f"   `{receipt['release_certificate_sha256']}`:",
        "",
        "   ```sh",
    ]
    for abi in ABIS:
        lines.append(
            f"   apksigner verify --print-certs signed/{receipt['apks'][abi]['signed_name']}"
        )
    lines += [
        "   ```",
        "",
        "5. Send back only the two files in `signed/`. Before anything is published the developer",
        "   checks them against this folder with",
        "   `android/scripts/signing_inputs.py verify --inputs <this folder> --signed signed`.",
        "",
    ]
    return "\n".join(lines)


NOT_SIGNING_INPUTS = """PROVISIONAL: these are NOT signing inputs. Do not sign these APKs.

They were built from sources whose compiled update keys and baseline `check-baseline` refused
(its words are in build-receipt.json; until the owner's pair is committed, the TEST keys, which
anyone can sign with). They show that this exact source builds and passes its gate in a fresh
folder. Signing inputs are built again after the owner's public-keys.txt and
baseline-catalog.pmup are committed (docs/update-keys.md); `signing_inputs.py verify` refuses
this folder.
"""


def write_sums(folder: Path) -> None:
    files = sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file())
    lines = [f"{sha256(folder / f)}  {f}" for f in files if f != "SHA256SUMS"]
    (folder / "SHA256SUMS").write_text("\n".join(lines) + "\n")


def build(args, run, environ) -> int:
    out = Path(args.out)
    if out.exists() or out.is_symlink():
        raise Refused(f"{out} is not a new folder")
    commit, tree = args.commit.lower(), args.tree.lower()
    if not HEX40.fullmatch(commit) or not HEX40.fullmatch(tree):
        raise Refused(
            "--commit and --tree are 40 hex digits (git rev-parse <commit> and <commit>^{tree})"
        )
    packs = Path(args.packs)
    if not packs.is_dir():
        raise Refused(f"{packs} is not a folder of the baseline's packs")
    source_zip = Path(args.source_zip)
    if args.work and Path(args.work).exists():
        raise Refused(f"{args.work} is not a new folder")
    # A temporary build folder is always removed; --work keeps one for inspection.
    work = (
        Path(args.work) if args.work else Path(tempfile.mkdtemp(prefix="phonemesh-signing-inputs-"))
    )
    started = time.time()
    try:
        source = work / "source"
        entries = unpack(source_zip, commit, source)
        got = tree_id(source)
        if got != tree:
            raise Refused(f"{source_zip} is tree {got}, not {tree}")
        ceilings = signed_ceilings(source)
        env = tool_env(environ)
        tools = build_tools(env)
        _, java = run([str(Path(env["JAVA_HOME"]) / "bin/java"), "-version"], source, env)
        if args.runtime_aar:
            aar = Path(args.runtime_aar)
            (source / "android/libs").mkdir(parents=True, exist_ok=True)
            shutil.copyfile(aar, source / "android/libs" / aar.name)
        code, text = run([sys.executable, "-I", "android/scripts/fetch-runtime.py"], source, env)
        if code != 0:
            raise Refused(
                f"the pinned speech runtime could not be fetched or checked:\n{text.strip()}"
            )
        code, _ = run(
            ["./gradlew", "--no-daemon", "--console", "plain", ":hub:installDist"],
            source / "android",
            env,
            log=work / "hub-build.log",
        )
        if code != 0:
            raise Refused(
                f"the hub tool did not build from these sources:\n{tail(work / 'hub-build.log')}"
            )
        check = [
            "sh", str(source / HUB), "check-baseline",
            "--public-keys", str(source / KEYS), "--baseline", str(source / BASELINE),
            "--list", str(source / BASELINE_LIST), "--packs", str(packs.resolve()),
        ]  # fmt: skip
        trust_code, trust_text = run(check, source, env)
        if trust_code != 0 and not args.provisional:
            raise Refused(
                "these sources cannot make signing inputs: check-baseline refused their update "
                f"keys and baseline:\n{trust_text.strip()}\n"
                "Commit the owner's public pair first (docs/update-keys.md, step 5). "
                "--provisional builds clearly marked test inputs instead."
            )
        if trust_code == 0 and args.provisional:
            raise Refused(
                "--provisional is only for sources that still carry TEST keys; "
                "these pass check-baseline"
            )
        gate_started = time.time()
        code, log = run(["bash", "scripts/check.sh"], source, env, log=work / "check.log")
        tests = junit(source)
        check_ok = "CHECK_OK" in log.splitlines()
        if code != 0 or not check_ok:
            raise Refused(
                f"the canonical gate failed in the isolated source (exit {code}):\n"
                f"{tail(work / 'check.log')}"
            )
        if tests["failures"] or tests["errors"] or not tests["tests"]:
            raise Refused(
                f"the canonical gate failed in the isolated source: {tests['tests']} tests, "
                f"{tests['failures']} failures, {tests['errors']} errors"
            )
        gate = {
            "command": "bash scripts/check.sh",
            "exit_code": code,
            "check_ok": check_ok,
            "junit": tests,
            "elapsed_s": round(time.time() - gate_started, 1),
        }
        apks, versions = {}, set()
        for abi in ABIS:
            apk = source / RELEASE_OUTPUTS / f"app-{abi}-release-unsigned.apk"
            if not apk.is_file():
                raise Refused(f"the build made no {apk.name}")
            code, text = run([str(tools / "aapt2"), "dump", "badging", str(apk)], source, env)
            if code != 0:
                raise Refused(f"aapt2 could not read {apk.name}")
            badge = badging_of(text)
            inspect_unsigned(apk, abi, source, badge)
            versions.add((badge["version_name"], badge["version_code"]))
            apks[abi] = (apk, badge)
        if len(versions) != 1:
            raise Refused(f"the two APKs have different versions: {sorted(versions)}")
        (version_name, version_code) = versions.pop()
        hub_zip = source / HUB_ZIP
        if not hub_zip.is_file():
            raise Refused("the build made no phonemesh-hub.zip")

        partial = out.with_name(out.name + ".partial")
        if partial.exists():
            raise Refused(f"{partial} exists; remove it first")
        (partial / "unsigned").mkdir(parents=True)
        mark = "-provisional" if args.provisional else ""
        receipt = {
            "schema": 1,
            "kind": "phonemesh-signing-inputs",
            "provisional": bool(args.provisional),
            "final_signing_input": not args.provisional,
            "package": PACKAGE,
            "version_name": version_name,
            "version_code": int(version_code),
            "source": {
                "zip": f"phonemesh-{version_name}-source.zip",
                "sha256": sha256(source_zip),
                "bytes": source_zip.stat().st_size,
                "commit": commit,
                "tree": tree,
                "files": entries,
            },
            "tool": {
                "sha256": sha256(Path(__file__)),
                "same_as_source_copy": (source / SELF).is_file()
                and sha256(source / SELF) == sha256(Path(__file__)),
            },
            "toolchain": {
                "java": java.strip().splitlines()[0] if java.strip() else "",
                "build_tools": tools.name,
                "system": f"{platform.system()} {platform.machine()}",
                "gradle_user_home": "shared" if not env.get("GRADLE_USER_HOME") else "set",
            },
            "update_trust": {
                "check_baseline_exit": trust_code,
                "check_baseline_output": trust_text.strip(),
                "public_keys_sha256": sha256(source / KEYS),
                "baseline_sha256": sha256(source / BASELINE),
            },
            "gate": gate,
            "release_certificate_sha256": RELEASE_CERT_SHA256,
            "apks": {},
            "hub_zip": {},
            "built_unix": round(time.time()),
            "elapsed_s": round(time.time() - started, 1),
        }
        for abi in ABIS:
            apk, badge = apks[abi]
            rel = f"unsigned/phonemesh-{version_name}-{abi}{mark}-unsigned.apk"
            shutil.copyfile(apk, partial / rel)
            receipt["apks"][abi] = {
                "unsigned": rel,
                "sha256": sha256(partial / rel),
                "bytes": (partial / rel).stat().st_size,
                "signed_name": f"phonemesh-{version_name}-{abi}.apk",
                "signed_ceiling_bytes": ceilings[abi],
                "native_code": badge["native_code"],
            }
        hub_name = f"phonemesh-hub-{version_name}{mark}.zip"
        shutil.copyfile(hub_zip, partial / hub_name)
        receipt["hub_zip"] = {
            "name": hub_name,
            "sha256": sha256(partial / hub_name),
            "bytes": hub_zip.stat().st_size,
        }
        shutil.copyfile(source_zip, partial / receipt["source"]["zip"])
        shutil.copyfile(work / "check.log", partial / "check.log")
        (partial / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        if args.provisional:
            (partial / "NOT-SIGNING-INPUTS.txt").write_text(NOT_SIGNING_INPUTS)
        else:
            (partial / "SIGNING.md").write_text(signing_steps(receipt))
        write_sums(partial)
        partial.rename(out)
    finally:
        if not args.work:
            shutil.rmtree(work, ignore_errors=True)
    head = f"{PACKAGE} {version_name} (code {version_code}) from commit {commit}, tree {tree}"
    if args.provisional:
        print(f"PROVISIONAL inputs (TEST update keys; NOT signing inputs) for {head}:")
    else:
        print(f"Signing inputs for {head}:")
    junit_ = gate["junit"]
    print(f"Gate: CHECK_OK, {junit_['tests']} JUnit test records in {junit_['reports']} reports.")
    for abi in ABIS:
        a = receipt["apks"][abi]
        print(f"{a['sha256']}  {a['unsigned']} ({a['bytes']} bytes)")
    print(f"{receipt['hub_zip']['sha256']}  {receipt['hub_zip']['name']}")
    print(f"SHA256SUMS {sha256(out / 'SHA256SUMS')}")
    if args.provisional:
        print(f"Written to {out}. Do not sign these; `verify` refuses them.")
    else:
        print(f"Written to {out}. The owner signs them as SIGNING.md says; then run:")
        print(f"  signing_inputs.py verify --inputs {out} --signed <folder of the signed APKs>")
    return 0


# ---- verify ----


def read_kit(kit: Path) -> dict:
    sums_file = kit / "SHA256SUMS"
    if not sums_file.is_file():
        raise Refused(f"{kit} has no SHA256SUMS")
    listed = {}
    for line in sums_file.read_text().splitlines():
        digest, _, name = line.partition("  ")
        if (
            not HEX64.fullmatch(digest)
            or not name
            or name.startswith("/")
            or ".." in name.split("/")
        ):
            raise Refused(f"SHA256SUMS has a line that is not `<sha256>  <file>`: {line!r}")
        listed[name] = digest
    for p in sorted(kit.rglob("*")):
        rel = p.relative_to(kit).as_posix()
        if p.is_symlink():
            raise Refused(f"{rel} is a symbolic link")
        if p.is_file() and rel != "SHA256SUMS" and rel not in listed:
            raise Refused(f"{rel} is not in SHA256SUMS")
    for name, digest in listed.items():
        if not (kit / name).is_file() or sha256(kit / name) != digest:
            raise Refused(f"{name} does not match SHA256SUMS")
    receipt = json.loads((kit / "build-receipt.json").read_text())
    if receipt.get("kind") != "phonemesh-signing-inputs" or receipt.get("schema") != 1:
        raise Refused(f"{kit} is not a signing-inputs folder this tool knows")
    if receipt.get("provisional") or not receipt.get("final_signing_input"):
        raise Refused(
            f"{kit} is provisional: built with TEST update keys, it holds no signing inputs"
        )
    return receipt


def apksigner_result(text: str, code: int, name: str) -> str:
    first = text.strip().splitlines()[0] if text.strip() else ""
    if code != 0 or first != "Verifies":
        raise Refused(f"{name} does not verify: {first or 'apksigner printed nothing'}")

    def flag(scheme):
        m = re.search(rf"^Verified using {scheme} scheme \(.*\): (true|false)$", text, re.M)
        return bool(m and m.group(1) == "true")

    if not (flag("v2") or flag("v3")):
        raise Refused(f"{name} has neither APK Signature Scheme v2 nor v3")
    signers = re.search(r"^Number of signers: ([0-9]+)$", text, re.M)
    count = int(signers.group(1)) if signers else 0
    digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", text, re.M)
    if count != 1 or len(digests) != 1:
        raise Refused(f"{name} has {count} signers; a release has exactly one")
    return digests[0]


def payload_difference(unsigned: Path, signed: Path) -> str | None:
    with zipfile.ZipFile(unsigned) as u, zipfile.ZipFile(signed) as s:
        un, sn = u.infolist(), s.infolist()
        u_names, s_names = {i.filename: i for i in un}, {i.filename: i for i in sn}
        if len(u_names) != len(un) or len(s_names) != len(sn):
            return "an entry is named twice"
        missing = sorted(set(u_names) - set(s_names))
        if missing:
            return f"lacks {missing[0]}"
        added = sorted(n for n in set(s_names) - set(u_names) if not SIGNATURE_ENTRY.fullmatch(n))
        if added:
            return f"adds {added[0]}"
        for name, info in u_names.items():
            other = s_names[name]
            if (
                info.CRC != other.CRC
                or info.file_size != other.file_size
                or u.read(name) != s.read(name)
            ):
                return f"{name} differs from the unsigned input"
    return None


def verify(args, run, environ) -> int:
    kit, signed = Path(args.inputs), Path(args.signed)
    report = Path(args.receipt) if args.receipt else None
    if report and (report.exists() or report.is_symlink()):
        raise Refused(f"{report} already exists")
    cert = (args.certificate_sha256 or RELEASE_CERT_SHA256).lower()
    if not HEX64.fullmatch(cert):
        raise Refused("--certificate-sha256 is 64 hex digits")
    receipt = read_kit(kit)
    env = {k: v for k, v in environ.items() if k not in ("PYTHONPATH", "PYTHONHOME")}
    if not args.build_tools and not env.get("ANDROID_HOME"):
        if not env.get("ANDROID_SDK_ROOT"):
            raise Refused("set ANDROID_HOME to the Android SDK, or give --build-tools")
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"]
    tools = build_tools(env, args.build_tools)
    results = {}
    for abi in ABIS:
        a = receipt["apks"][abi]
        apk = signed / a["signed_name"]
        if apk.is_symlink() or not apk.is_file():
            raise Refused(f"{apk.name} is missing from {signed}")
        size = apk.stat().st_size
        ceiling = a["signed_ceiling_bytes"]
        if size > ceiling:
            raise Refused(f"{apk.name} is {size} bytes, over the signed ceiling of {ceiling} bytes")
        code, text = run(
            [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
            signed,
            env,
        )
        digest = apksigner_result(text, code, apk.name)
        if digest != cert:
            raise Refused(f"{apk.name} is signed by certificate {digest}, not {cert}")
        code, text = run([str(tools / "aapt2"), "dump", "badging", str(apk)], signed, env)
        if code != 0:
            raise Refused(f"aapt2 could not read {apk.name}")
        badge = badging_of(text)
        if badge["package"] != receipt["package"]:
            raise Refused(f"{apk.name} is package {badge['package']}, not {receipt['package']}")
        want = f"{receipt['version_name']} code {receipt['version_code']}"
        got = f"{badge['version_name']} code {badge['version_code']}"
        if got != want:
            raise Refused(f"{apk.name} has version {got}, not {want}")
        if badge["native_code"] != [abi]:
            raise Refused(
                f"{apk.name} has native code {' '.join(badge['native_code']) or 'none'}, not {abi}"
            )
        difference = payload_difference(kit / a["unsigned"], apk)
        if difference:
            raise Refused(f"{apk.name} {difference}")
        code, _ = run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], signed, env)
        if code != 0:
            raise Refused(f"{apk.name} is not zip-aligned (zipalign -c -P 16 4)")
        results[abi] = {
            "file": apk.name,
            "bytes": size,
            "sha256": sha256(apk),
            "certificate_sha256": digest,
        }
    release = cert == RELEASE_CERT_SHA256
    src = receipt["source"]
    name, code_ = receipt["version_name"], receipt["version_code"]
    print(f"Signed {receipt['package']} {name} (code {code_}), commit {src['commit']}:")
    for abi in ABIS:
        r, ceiling = results[abi], receipt["apks"][abi]["signed_ceiling_bytes"]
        print(f"  {abi}: {r['bytes']} bytes (ceiling {ceiling}); same payload as its input")
    if release:
        print(f"Certificate {cert}: the Phonemesh release certificate.")
    else:
        print(
            f"Certificate {cert}: NOT the Phonemesh release certificate. Test only; never publish."
        )
    print("SHA256SUMS lines:")
    for abi in ABIS:
        print(f"{results[abi]['sha256']}  {results[abi]['file']}")
    if report:
        record = {
            "schema": 1,
            "kind": "phonemesh-signed-apk-verification",
            "release_identity": release,
            "certificate_sha256": cert,
            "source": src,
            "inputs_sha256sums": sha256(kit / "SHA256SUMS"),
            "build_tools": tools.name,
            "apks": results,
            "verified_unix": round(time.time()),
        }
        with open(report, "x") as f:
            f.write(json.dumps(record, indent=2) + "\n")
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="signing_inputs.py", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="command", required=True)
    b = sub.add_parser(
        "build", help="unsigned release APKs from a source ZIP, built in a fresh folder"
    )
    b.add_argument("--source-zip", required=True)
    b.add_argument("--commit", required=True)
    b.add_argument("--tree", required=True)
    b.add_argument(
        "--packs", required=True, help="folder with the four packs the baseline list names"
    )
    b.add_argument("--out", required=True, help="new folder for the signing inputs")
    b.add_argument(
        "--work", help="new folder to build in, kept afterwards (default: a temporary folder)"
    )
    b.add_argument(
        "--runtime-aar",
        help="a local copy of the pinned sherpa-onnx AAR (checked; else downloaded)",
    )
    b.add_argument(
        "--provisional",
        action="store_true",
        help="build TEST-key sources into marked, unsignable inputs",
    )
    v = sub.add_parser("verify", help="check owner-signed APKs against their signing inputs")
    v.add_argument("--inputs", required=True)
    v.add_argument("--signed", required=True)
    v.add_argument(
        "--certificate-sha256",
        help="expected certificate (default: the Phonemesh release certificate)",
    )
    v.add_argument("--receipt", help="new file for a JSON record of the result")
    v.add_argument(
        "--build-tools", help="Android build-tools folder (default: the newest under ANDROID_HOME)"
    )
    return p


def main(argv=None, run=run_command, environ=None) -> int:
    args = parser().parse_args(argv)
    environ = dict(os.environ if environ is None else environ)
    try:
        return build(args, run, environ) if args.command == "build" else verify(args, run, environ)
    except Refused as e:
        print(f"refused: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
