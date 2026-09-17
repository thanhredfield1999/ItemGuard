"""A/B tick-time benchmark: the same workload with and without ItemGuard.

Every performance statement made about this plugin so far came from reading source. Reading
finds defects. It does not produce a number, and a number is what "does it lag" actually asks
for.

So: stand up one server twice on identical worlds. Run A has ItemGuard installed, run B does
not. Both build the same hopper workload — the path that fires once per hopper per tick — and
both sample tick duration with the same independent probe. The difference between the two is
the plugin's cost, measured rather than estimated.

Honest limits, stated up front and repeated in the output:
  * Hoppers moving tracked items are a heavy, focused workload. They are not a replica of a
    hundred humans playing, and this does not claim to be one.
  * One machine, one run each. Percentiles from a single run are indicative, not authoritative.
  * The server is otherwise empty; a real server runs other plugins that compete for the tick.
"""
from __future__ import annotations

import json
import pathlib
import re
import shutil
import subprocess
import sys
import time
import uuid

REPO = pathlib.Path(__file__).resolve().parents[2]
HOME = pathlib.Path(__file__).resolve().parent
BASE = pathlib.Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = "C:/Program Files/Java/jdk-21/bin/java.exe"
JAVAC = "C:/Program Files/Java/jdk-21/bin/javac.exe"
PAPER_CACHE = pathlib.Path("E:/AI.WORK/itemguard-paper-smoke")
ITEMGUARD_JAR = REPO / "release/upload/ItemGuard-LITE-1.0.0.jar"

# 400 hoppers put tick time at 0.28ms of a 50ms budget — under 1% utilisation, where
# ItemGuard's cost sat below measurement noise (the p50 delta even came out negative).
# A number buried in noise is not a result, so the workload is raised until the server is
# doing real work and a difference has room to appear.
HOPPERS = 3000
WARMUP_SECONDS = 20
SAMPLE_SECONDS = 60


def build_probe(workdir: pathlib.Path) -> pathlib.Path:
    """Compiles LoadProbe against the Paper API and packages it as a plugin.

    The server jar is a launcher, not an API bundle — compiling against it fails with
    "package org.bukkit does not exist". The lite-runtime tooling already resolves the real
    API classpath into `classpath.txt`, so reuse that rather than re-deriving it here and
    getting it subtly different.
    """
    classpath_file = REPO / "tools/lite-runtime/classpath.txt"
    if not classpath_file.is_file():
        raise RuntimeError(f"missing {classpath_file}; run the lite-runtime tooling once first")
    classpath = classpath_file.read_text(encoding="utf-8").strip()

    out = workdir / "probe-classes"
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [JAVAC, "--release", "21", "-cp", classpath,
         "-d", str(out), str(HOME / "LoadProbe.java")],
        check=True, capture_output=True, text=True,
    )
    jar = workdir / "LoadProbe.jar"
    import zipfile
    with zipfile.ZipFile(jar, "w") as z:
        for f in out.rglob("*.class"):
            z.write(f, f.relative_to(out).as_posix())
        z.writestr("plugin.yml",
                   "name: LoadProbe\nversion: '1'\nmain: load.LoadProbe\n"
                   "api-version: '1.21'\ncommands:\n  loadprobe:\n    description: benchmark\n")
    return jar


def stage(with_itemguard: bool) -> pathlib.Path:
    root = BASE / ("itemguard-load-" + uuid.uuid4().hex[:12])
    (root / "plugins").mkdir(parents=True)

    shutil.copy2(PAPER_CACHE / "paper.jar", root / "paper.jar")
    for directory in ("libraries", "versions", "cache"):
        if (PAPER_CACHE / directory).exists():
            shutil.copytree(PAPER_CACHE / directory, root / directory)

    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (root / "server.properties").write_text(
        "online-mode=false\nspawn-protection=0\nmax-players=200\n"
        "level-type=flat\nview-distance=6\nsimulation-distance=6\n",
        encoding="utf-8",
    )

    shutil.copy2(build_probe(root), root / "plugins/LoadProbe.jar")
    if with_itemguard:
        shutil.copy2(ITEMGUARD_JAR, root / "plugins/ItemGuard.jar")
    return root


def run(root: pathlib.Path, label: str) -> dict:
    """Boots the server, builds the workload, samples, and returns the parsed report."""
    process = subprocess.Popen(
        [JAVA, "-Xms2G", "-Xmx2G", "-jar", "paper.jar", "--nogui"],
        cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    report = None
    try:
        def send(line: str) -> None:
            process.stdin.write(line + "\n")
            process.stdin.flush()

        booted = False
        deadline = time.time() + 300
        for line in process.stdout:
            if time.time() > deadline:
                raise TimeoutError(f"{label}: server did not reach the report stage")

            if not booted and "LOADPROBE_READY" in line:
                booted = True
                send(f"loadprobe build {HOPPERS}")

            elif "LOADPROBE_BUILT" in line:
                # Let the JIT warm and hopper cooldowns settle before measuring; the first
                # seconds after a bulk block placement are not representative of steady state.
                time.sleep(WARMUP_SECONDS)
                send("loadprobe start")

            elif "LOADPROBE_SAMPLING_START" in line:
                time.sleep(SAMPLE_SECONDS)
                send("loadprobe report")

            elif "LOADPROBE_REPORT" in line:
                report = dict(re.findall(r"(\w+)=([0-9.]+)ms?", line))
                send("stop")
                break
    finally:
        try:
            process.stdin.write("stop\n")
            process.stdin.flush()
        except Exception:
            pass
        try:
            process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.kill()

    if report is None:
        raise RuntimeError(f"{label}: no report produced")
    return report


def main() -> int:
    if not ITEMGUARD_JAR.is_file():
        print(f"missing {ITEMGUARD_JAR}")
        return 1

    import hashlib
    candidate = hashlib.sha256(ITEMGUARD_JAR.read_bytes()).hexdigest()

    # Alternate the arms across repeats. A single run each is too noisy to trust — one arm
    # catching a GC pause moved `max` from 38ms to 105ms in an earlier attempt, which looked
    # like a finding and was not. Interleaving also cancels machine drift: if the laptop
    # thermally throttles partway through, both arms absorb it equally.
    REPEATS = 3
    samples = {"without": [], "with": []}
    roots = {}
    for repeat in range(REPEATS):
        for label, with_ig in (("without", False), ("with", True)):
            root = stage(with_ig)
            roots[f"{label}-{repeat}"] = root
            print(f"repeat {repeat + 1}/{REPEATS}, {label} ItemGuard: {root.name}")
            measured = run(root, label)
            samples[label].append(measured)
            print(f"  {measured}")

    def median_of(label, key):
        values = sorted(float(s[key]) for s in samples[label])
        return values[len(values) // 2]

    results = {
        label: {k: f"{median_of(label, k):.2f}" for k in ("p50", "p95", "p99", "max", "mean")}
        for label in samples
    }
    print("\nmedian across repeats:")
    for label in ("without", "with"):
        print(f"  {label}: {results[label]}")

    without = results["without"]
    with_ig = results["with"]

    print("\n=== TICK TIME, 400 hoppers moving tracked items ===")
    print(f"{'':10} {'p50':>9} {'p95':>9} {'p99':>9} {'max':>9}")
    for label, data in (("without", without), ("with", with_ig)):
        print(f"{label:10} {data['p50']:>8}ms {data['p95']:>8}ms "
              f"{data['p99']:>8}ms {data['max']:>8}ms")

    delta_p50 = float(with_ig["p50"]) - float(without["p50"])
    delta_p95 = float(with_ig["p95"]) - float(without["p95"])
    delta_mean = float(with_ig["mean"]) - float(without["mean"])
    overhead = delta_mean / float(without["mean"]) * 100 if float(without["mean"]) else 0
    print(f"\nItemGuard cost: mean {delta_mean:+.2f}ms/tick ({overhead:+.0f}% of the work")
    print(f"this workload already costs), p95 {delta_p95:+.2f}ms, p50 {delta_p50:+.2f}ms.")
    print("A Minecraft tick has a 50ms budget; this workload uses "
          f"{float(without['mean']):.2f}ms of it before ItemGuard.")

    # A measurement that cannot move is not a measurement. The first version of this harness
    # sampled the interval between ticks, which a keeping-up server pins at exactly 50ms; it
    # printed 49.99 vs 50.01 and looked like a real result. Refuse to report that again.
    baseline = float(without["p50"])
    if abs(baseline - 50.0) < 0.5 and float(without["p95"]) < 52.0:
        print("\nINVALID: the no-plugin arm sat at the 50ms tick interval, meaning tick")
        print("duration was never actually sampled - measure getTickTimes, not the gap")
        print("between scheduler runs.")
        return 2

    # Second trap, met for real at 400 hoppers: the measurement worked but the server was
    # idle (0.28ms of 50ms), so the plugin's cost fell below noise and the p50 delta came
    # out NEGATIVE. A negative cost is not a finding, it is an unloaded machine.
    # p50 is the wrong gate for this workload and the earlier version of this check was
    # wrong to use it. Hoppers transfer on an 8-tick cooldown, so most ticks genuinely have
    # no transfer work and the median sits near zero no matter how many hoppers exist. The
    # work is bursty; the MEAN captures total work per tick, which is what a plugin adds to.
    baseline_mean = float(without["mean"])
    if baseline_mean < 0.30:
        print(f"\nWEAK: baseline mean is {baseline_mean}ms - too little work to measure "
              "against. Raise HOPPERS and re-run.")
        return 3

    out = REPO / f"run/load-benchmark-{candidate[:8]}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({
        "candidate_sha256": candidate,
        "hoppers": HOPPERS,
        "sample_seconds": SAMPLE_SECONDS,
        "without_itemguard": without,
        "with_itemguard": with_ig,
        "delta_p50_ms": delta_p50,
        "delta_p95_ms": delta_p95,
        "limits": [
            "Hopper-heavy synthetic workload, not a replica of real players.",
            "One run per arm on one machine; indicative, not authoritative.",
            "Server otherwise empty; a real server shares the tick with other plugins.",
        ],
        "roots": {k: str(v) for k, v in roots.items()},
        "repeats": REPEATS,
        "raw_samples": samples,
    }, indent=2), encoding="utf-8")
    print(f"\nreport: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
