#!/usr/bin/env python3
"""Run the ItemGuard LITE sweep fixture against several Paper versions.

The question this answers: does the shipped JAR still work on Paper releases newer than the
one it was built against? Nothing here is inferred - each version gets a real server, a real
duplicate planted in two never-opened chests, and a verdict read from the plugin's own
console line.

Usage:
    python scripts/version_matrix.py 1.21.11 26.1.1 26.1.2 26.2

Every version runs in its own fixture root and is torn down before the next begins, so a
crash in one cannot contaminate another. Results are written to run/version-matrix-<tag>.json.
"""
from __future__ import annotations

import json
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO / "run"
CACHE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM/paper-cache")
API = "https://fill.papermc.io/v3/projects/paper"
PURPUR_API = "https://api.purpurmc.org/v2/purpur"
UA = {"User-Agent": "itemguard-version-matrix/1.0 (thanhredfield1999)"}

# Paper's own declared minimum Java per version family. A server started on too old a JVM
# fails at launch with a message that looks nothing like a plugin fault, so pick the JDK
# deliberately rather than letting JAVA_HOME decide.
JDK_BY_MINIMUM = {
    21: Path("C:/Program Files/Java/jdk-21"),
    25: Path("C:/Program Files/Java/jdk-25"),
}


def _get_json(url: str) -> dict:
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())


def resolve_build(version: str) -> tuple[str, int, str]:
    """Return (download_url, java_minimum, build_id) for the newest build of a version.

    Accepts "1.21.11" (Paper) or "purpur:1.21.11". Purpur is a Paper fork and publishes its
    own build API, so it needs a different endpoint but behaves identically afterwards.
    Spigot is deliberately absent: it has no official prebuilt download, only BuildTools,
    so it cannot be fetched here and must be built separately.
    """
    if version.startswith("purpur:"):
        mc = version.split(":", 1)[1]
        meta = _get_json(f"{PURPUR_API}/{mc}")
        build = str(meta["builds"]["latest"])
        url = f"{PURPUR_API}/{mc}/{build}/download"
        # Purpur tracks Paper's JVM floor; ask Paper rather than hardcoding a guess.
        java_minimum = int(
            _get_json(f"{API}/versions/{mc}")["version"]["java"]["version"]["minimum"]
        )
        return url, java_minimum, build

    meta = _get_json(f"{API}/versions/{version}")
    java_minimum = int(meta["version"]["java"]["version"]["minimum"])
    builds = _get_json(f"{API}/versions/{version}/builds")
    newest = builds[0]
    url = newest["downloads"]["server:default"]["url"]
    return url, java_minimum, str(newest.get("id", "unknown"))


def fetch_jar(version: str) -> tuple[Path, int, str]:
    url, java_minimum, build = resolve_build(version)
    CACHE.mkdir(parents=True, exist_ok=True)
    slug = version.replace(":", "-")
    target = CACHE / f"{slug}-{build}.jar"
    if not target.exists():
        request = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(request, timeout=600) as response:
            target.write_bytes(response.read())
    return target, java_minimum, build


def stage_fixture(java_home: Path, paper_jar: Path) -> Path:
    # The Paper jar has to be chosen at stage time. smoke.py hashes every admitted artifact
    # into attempt.json, and swapping paper.jar afterwards trips the artifact gate - correctly,
    # since that gate is what stops a fixture running something other than what it recorded.
    # 2026-09-17: this used to chain `set "ITEMGUARD_PAPER_JAR=..." && python ...` through
    # `cmd.exe /d /s /c`. It silently did not work. With /s, cmd strips the first and last quote
    # of the whole string, which unbalances the remaining quotes, and the last `set` never took
    # effect: the child ran with the variable unset, smoke.py fell back to the default Paper jar,
    # and every version in the matrix booted the same server while the record kept the requested
    # build number. Reproduced deterministically without starting a server (child printed
    # `ITEMGUARD_PAPER_JAR = None`). Pass the environment directly instead of quoting it.
    child_env = dict(os.environ)
    child_env["JAVA_HOME"] = str(java_home)
    child_env["PATH"] = str(java_home / "bin") + os.pathsep + child_env.get("PATH", "")
    child_env["ITEMGUARD_PAPER_JAR"] = str(paper_jar)
    out = subprocess.run(
        ["python", "-B", "tools/lite-runtime/smoke.py", "stage"],
        cwd=REPO, capture_output=True, text=True, timeout=900, env=child_env,
    )
    lines = [line.strip() for line in out.stdout.splitlines() if line.strip()]
    if out.returncode != 0 or not lines:
        # An empty stdout used to raise IndexError, which got recorded as STAGE_FAILED with
        # no hint of the cause. It happened for real when smoke.py was edited mid-matrix: the
        # staging process died instantly and four versions were written off as failures that
        # had nothing to do with the plugin. Say what actually went wrong instead.
        raise RuntimeError(
            f"stage produced no root (exit {out.returncode}). "
            f"stdout: {out.stdout[-2000:]!r} stderr: {out.stderr[-2000:]!r}"
        )
    root = lines[-1]
    if not Path(root).exists():
        raise RuntimeError(f"stage failed: {out.stdout[-2000:]}\n{out.stderr[-2000:]}")
    # A version label is only worth something if the server actually boots that version. The
    # binding failed silently once (see above), so it is checked rather than assumed: a fixture
    # whose paper.jar is not the jar this version resolved to is a lie about what was tested.
    staged = Path(root) / "paper.jar"
    expected = hashlib.sha256(paper_jar.read_bytes()).hexdigest()
    actual = hashlib.sha256(staged.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(
            f"staged paper.jar is not the jar this version resolved to: expected "
            f"{expected[:16]} ({paper_jar.name}), fixture holds {actual[:16]}. "
            f"Refusing to record a version that was not booted."
        )
    return Path(root)


def run_version(version: str) -> dict:
    started = datetime.now(timezone.utc).isoformat()
    record: dict = {"version": version, "started": started}
    try:
        jar, java_minimum, build = fetch_jar(version)
        record["build"] = build
        record["java_minimum"] = java_minimum
    except Exception as failure:  # noqa: BLE001 - recorded, not swallowed
        record["status"] = "DOWNLOAD_FAILED"
        record["detail"] = repr(failure)
        return record

    java_home = JDK_BY_MINIMUM.get(java_minimum)
    if java_home is None or not java_home.exists():
        record["status"] = "NO_JDK"
        record["detail"] = f"Paper {version} needs Java {java_minimum}; no JDK for it locally"
        return record
    record["jdk"] = java_home.name

    try:
        root = stage_fixture(java_home, jar)
    except Exception as failure:  # noqa: BLE001
        record["status"] = "STAGE_FAILED"
        record["detail"] = repr(failure)
        return record
    record["root"] = str(root)

    # Same reason as stage_fixture: chaining `set` through `cmd.exe /s` silently drops the
    # variables, so JAVA_HOME never reached these children either - it only appeared to work
    # because the invoking shell already had it exported. Pass the environment directly.
    #
    # ITEMGUARD_BOT_MC_VERSION is what makes a per-version run possible at all: bots.cjs pins its
    # client to 1.21.11 unless told otherwise, and a pinned client is refused by any other server
    # ("This server is version X, you are using version 1.21.11"). Without it every version is
    # recorded as a plugin failure while the plugin is fine.
    smoke_env = dict(os.environ)
    smoke_env["JAVA_HOME"] = str(java_home)
    smoke_env["PATH"] = str(java_home / "bin") + os.pathsep + smoke_env.get("PATH", "")
    smoke_env["ITEMGUARD_BOT_MC_VERSION"] = version.split(":")[-1]

    smoke = subprocess.run(
        ["python", "-B", "tools/lite-runtime/smoke.py", "sweep", str(root)],
        cwd=REPO, capture_output=True, text=True, timeout=1800, env=smoke_env,
    )
    record["smoke_exit"] = smoke.returncode
    record["smoke_tail"] = (smoke.stdout or "")[-600:]

    verify = subprocess.run(
        ["python", "-B", "tools/lite-runtime/verify.py", "sweep", str(root)],
        cwd=REPO, capture_output=True, text=True, timeout=600, env=smoke_env,
    )
    try:
        verdict = json.loads(verify.stdout)
    except json.JSONDecodeError:
        verdict = {"status": "UNPARSEABLE", "raw": verify.stdout[-800:]}
    record["verdict"] = verdict
    record["status"] = verdict.get("status", "UNKNOWN")

    # Read the two facts that matter straight out of the server log, so the summary does not
    # depend on the verifier having been correct.
    console = root / "server-1.log"
    if console.exists():
        text = console.read_text(encoding="utf-8", errors="replace")
        confirmed = re.search(r"ITEMGUARD_DUPLICATE_CONFIRMED code=(\S+).*?locations=(\d+)", text)
        record["duplicate_confirmed"] = bool(confirmed)
        if confirmed:
            record["confirmed_code"] = confirmed.group(1)
            record["confirmed_locations"] = int(confirmed.group(2))
        record["plugin_enabled"] = "ItemGuard" in text and "Enabling ItemGuard" in text
        record["unsupported_api"] = "Unsupported API version" in text
        for pattern in (r"Could not load 'plugins[^']*ItemGuard[^']*'", r"java\.lang\.NoSuchMethodError",
                        r"java\.lang\.NoClassDefFoundError"):
            hit = re.search(pattern + r".{0,200}", text, re.DOTALL)
            if hit:
                record.setdefault("load_errors", []).append(hit.group(0)[:200])
    record["finished"] = datetime.now(timezone.utc).isoformat()
    return record


def main(argv: list[str]) -> int:
    versions = argv[1:]
    if not versions:
        print("usage: version_matrix.py <version> [version ...]", file=sys.stderr)
        return 2
    tag = datetime.now().strftime("%Y%m%d-%H%M%S")
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    report = RESULTS_DIR / f"version-matrix-{tag}.json"

    results = []
    for version in versions:
        print(f"=== {version} ===", flush=True)
        record = run_version(version)
        results.append(record)
        print(json.dumps(record, indent=2)[:1200], flush=True)
        report.write_text(json.dumps(results, indent=2), encoding="utf-8")

    print("\n=== SUMMARY ===", flush=True)
    for record in results:
        mark = "PASS" if record.get("status") == "PASS_SWEEP_SMOKE" else record.get("status")
        print(f"{record['version']:<10} {mark:<24} "
              f"dupe={record.get('duplicate_confirmed')} "
              f"locations={record.get('confirmed_locations')}", flush=True)
    print(f"\nreport: {report}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
