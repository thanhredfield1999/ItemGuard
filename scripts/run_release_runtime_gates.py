"""Run the seven LITE runtime gates on the pinned candidate, one server at a time.

Each gate is a separate real Paper server plus a real client, and each one verifies its own result;
this only sequences them and records what each said. Sequenced rather than parallel on purpose: two
Paper servers at once is how the 2026-09-17 matrix ran the host out of native memory and reported
`plugin_enabled: False` on every version.

Each gate is bound to the jar hash in `tools/lite-runtime/smoke.py` (`EXPECTED`), which
`scripts/repackage_and_repin.py` rewrites whenever the jar is rebuilt, so a run here cannot quietly
test a different jar than the one in `release/`.

    python scripts/run_release_runtime_gates.py            # all seven
    python scripts/run_release_runtime_gates.py loss sweep  # named gates only
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "tools" / "lite-runtime"
JAR = ROOT / "release" / "spigot-upload" / "ItemGuard-LITE-1.0.0.jar"
FIXTURES = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
ROOT_NAME = re.compile(r"itemguard-lite-isolated-[0-9a-f]{12}")
GATE_TIMEOUT_S = 900

# name -> (smoke scope or None, verifier argv after the root)
SMOKE_GATES = {
    "separate": ("run", []),
    "loss": ("loss", ["loss"]),
    "sweep": ("sweep", ["sweep"]),
}
# name -> (script, the verdict file it writes). The exit code is NOT the verdict: every one of these
# scripts records PASS_*/FAILED_* in a JSON file and exits 0 either way, so a driver that reads only
# the exit code reports PASS for a gate that failed. It did, for four of the seven, until 2026-09-17.
STANDALONE_GATES = {
    "reload": (RUNTIME / "reload.py", "reload.json", None),
    "multi": (RUNTIME / "multi.py", "multi.json", ["multi"]),
    "two-plugin": (RUNTIME / "two_plugin.py", "two-plugin.json", None),
    "power-cut": (RUNTIME / "power_cut.py", "power-cut.json", None),
}
# Only `multi` has an independent adjudicator today. For the other three the verdict is the scope's
# own JSON, which is a weaker claim and is recorded as such: `independent_verifier` is False in the
# report rather than left to be assumed.


def newest_fixture(before: set[Path]) -> Path:
    created = sorted(set(FIXTURES.glob("itemguard-lite-isolated-*")) - before)
    if len(created) != 1:
        raise RuntimeError(f"expected exactly one new fixture, saw {[p.name for p in created]}")
    return created[0]


def run(argv: list[str], timeout: int = GATE_TIMEOUT_S) -> tuple[int, str]:
    print(f"    $ {' '.join(argv)}", flush=True)
    finished = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    return finished.returncode, (finished.stdout or "") + (finished.stderr or "")


def smoke_gate(name: str, mode: str, verifier: list[str]) -> dict:
    before = set(FIXTURES.glob("itemguard-lite-isolated-*"))
    code, out = run([sys.executable, str(RUNTIME / "smoke.py"), "stage"])
    if code != 0:
        return {"gate": name, "status": "REJECTED", "reason": "stage failed", "tail": out[-400:]}
    root = newest_fixture(before)
    print(f"    fixture {root.name}", flush=True)
    code, out = run([sys.executable, str(RUNTIME / "smoke.py"), mode, str(root)])
    if code != 0:
        return {"gate": name, "fixture": root.name, "status": "REJECTED",
                "reason": "smoke run failed", "tail": out[-600:]}
    code, verified = run([sys.executable, str(RUNTIME / "verify.py"), *verifier, str(root)])
    verdict = verified.strip().splitlines()[-20:]
    return {
        "gate": name, "fixture": root.name,
        "status": "PASS" if code == 0 else "REJECTED",
        "verifier": "\n".join(verdict)[-800:],
    }


def standalone_gate(name: str, script: Path, verdict_file: str,
                    verifier: list[str] | None = None) -> dict:
    before = set(FIXTURES.glob("itemguard-lite-isolated-*"))
    code, out = run([sys.executable, str(script)])
    created = sorted(set(FIXTURES.glob("itemguard-lite-isolated-*")) - before)
    fixture = created[0] if len(created) == 1 else None
    result = {
        "gate": name,
        "fixture": fixture.name if fixture else [p.name for p in created],
        "exit_code": code,
    }
    if fixture is None:
        result.update(status="REJECTED", reason="expected exactly one new fixture root")
        return result
    path = fixture / verdict_file
    if not path.is_file():
        result.update(status="REJECTED", reason=f"no verdict file {verdict_file}")
        return result
    verdict = json.loads(path.read_text(encoding="utf-8"))
    recorded = str(verdict.get("status", ""))
    result["verdict"] = recorded
    result["status"] = "PASS" if recorded.startswith("PASS") else "REJECTED"
    # The smoke gates are adjudicated by `verify.py <mode> <root>` on every run - that call is
    # what decides PASS here - so they belong in the verified column. Recording them as False
    # understated the evidence in the JSON, which is the file people read instead of the log.
    result["independent_verifier"] = True
    if verifier and result["status"] == "PASS":
        # The scope's own summary is not the verdict when a verifier exists: verify.py re-derives the
        # claims from the raw logs and refuses a summary the logs do not support.
        code, verified = run([sys.executable, str(RUNTIME / "verify.py"), *verifier, str(fixture)])
        result["verifier"] = "\n".join(verified.strip().splitlines()[-12:])[-600:]
        if code != 0:
            result["status"] = "REJECTED"
            result["reason"] = "independent verifier rejected the scope's summary"
    if result["status"] == "REJECTED":
        result["tail"] = json.dumps(verdict)[-800:]
    else:
        result["tail"] = recorded
    return result


def main() -> int:
    wanted = sys.argv[1:] or list(SMOKE_GATES) + list(STANDALONE_GATES)
    # An unrecognised name used to be skipped with a printed line, which left `0/0 gates PASS` and
    # exit 0 - a caller reading the exit code saw success for a run that executed nothing, and a
    # typo beside a valid name silently dropped a gate from the total. A request this tool does not
    # understand is now an error before a single server starts.
    known = list(SMOKE_GATES) + list(STANDALONE_GATES)
    unknown = [name for name in wanted if name not in known]
    if unknown:
        print(f"unknown gate(s): {', '.join(unknown)}", file=sys.stderr)
        print(f"known gates: {', '.join(known)}", file=sys.stderr)
        return 2
    if not JAR.is_file():
        print(f"no jar at {JAR}", file=sys.stderr)
        return 2
    candidate = hashlib.sha256(JAR.read_bytes()).hexdigest()
    print(f"candidate {candidate}", flush=True)

    results = []
    started = time.time()
    for name in wanted:
        print(f"[{name}]", flush=True)
        try:
            if name in SMOKE_GATES:
                mode, verifier = SMOKE_GATES[name]
                result = smoke_gate(name, mode, verifier)
            elif name in STANDALONE_GATES:
                script, verdict_file, verifier = STANDALONE_GATES[name]
                result = standalone_gate(name, script, verdict_file, verifier)
            else:  # unreachable: the names are validated before the first gate runs
                result = {"gate": name, "status": "REJECTED", "reason": "unknown gate"}
        except Exception as failure:  # a crashed gate is a rejected gate, never a silent pass
            result = {"gate": name, "status": "REJECTED", "reason": repr(failure)}
        result["candidate_sha256"] = candidate
        results.append(result)
        print(f"  -> {result['status']}  {result.get('fixture', '')}", flush=True)

    report = {
        "candidate_sha256": candidate,
        "elapsed_s": round(time.time() - started, 1),
        "passed": sum(1 for r in results if r["status"] == "PASS"),
        "total": len(results),
        "results": results,
    }
    out = ROOT / "run" / f"runtime-gates-{time.strftime('%Y%m%d-%H%M%S')}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\n{report['passed']}/{report['total']} gates PASS in {report['elapsed_s']}s -> {out}")
    return 0 if report["passed"] == report["total"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
