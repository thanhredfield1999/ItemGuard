"""Controlled Premium MySQL reliability/failure fixture.

Phase 1 starts Paper with an unreachable MySQL endpoint and requires fail-closed startup.
Phase 2 starts Paper against disposable MySQL, stops MySQL during a write, requires the write to
fail without replay, restarts MySQL, and requires a clean read of the unchanged committed value.
All owned Paper/MySQL processes are stopped in finally.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SINGLE_RUNNER = ROOT / "tools/premium-runtime/paper_mysql_smoke.py"
PROBE_SOURCE = ROOT / "tools/premium-runtime/PremiumFailureProbe.java"
PROBE_BUILD = ROOT / "tools/premium-runtime/.failure-probe-build"
PROBE_JAR = ROOT / "tools/premium-runtime/.failure-probe.jar"
COMPILE_CLASSPATH_FILE = Path("C:/Users/thanh/AppData/Local/Temp/itemguard-premium-classpath.txt")
PAPER_CACHE = Path("E:/AI.WORK/itemguard-paper-smoke")
PAPER_JAR = PAPER_CACHE / "paper.jar"
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = Path("C:/Program Files/Java/jdk-21/bin/java.exe")
JAVAC = Path("C:/Program Files/Java/jdk-21/bin/javac.exe")
SERVER_ID = "paper-failure-1"


def load_single_runner():
    spec = importlib.util.spec_from_file_location("premium_single_runner", SINGLE_RUNNER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load single-server runner: {SINGLE_RUNNER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SINGLE = load_single_runner()
MYSQL = SINGLE.MYSQL
MYSQL_FIXTURE_PATH = SINGLE.MYSQL_FIXTURE_PATH
MYSQL_HOST = MYSQL.BIND
MYSQL_PORT = MYSQL.PORT
MYSQL_DATABASE = MYSQL.TEST_DATABASE
MYSQL_USER = MYSQL.TEST_USER
MYSQL_PASSWORD = MYSQL.TEST_PASSWORD


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def port_released(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(1.0)
        return probe.connect_ex(("127.0.0.1", port)) != 0


def run_mysql(action: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(MYSQL_FIXTURE_PATH), action],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=900,
    )


def mysql_query(sql: str) -> list[str]:
    result = subprocess.run(
        [
            str(MYSQL.bin_path("mysql")),
            "--protocol=tcp",
            "-h",
            MYSQL_HOST,
            "-P",
            str(MYSQL_PORT),
            "-u",
            "root",
            "--batch",
            "--skip-column-names",
            MYSQL_DATABASE,
            "-e",
            sql,
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if result.returncode != 0:
        raise RuntimeError(f"MySQL query failed: {result.stderr.strip()}")
    return result.stdout.strip().splitlines()


def stage_probe_jar() -> Path:
    if not PROBE_SOURCE.is_file():
        raise RuntimeError(f"failure probe source missing: {PROBE_SOURCE}")
    if not COMPILE_CLASSPATH_FILE.is_file():
        raise RuntimeError(f"Maven compile classpath missing: {COMPILE_CLASSPATH_FILE}")
    cleanup_probe_artifacts()
    PROBE_BUILD.mkdir(parents=True)
    dependency_classpath = COMPILE_CLASSPATH_FILE.read_text(encoding="utf-8").strip()

    def write_deterministic(archive: zipfile.ZipFile, name: str, content: bytes) -> None:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = 0o644 << 16
        archive.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    try:
        classpath = os.pathsep.join((dependency_classpath, str(PREMIUM_JAR)))
        completed = subprocess.run(
            [
                str(JAVAC),
                "--release",
                "21",
                "-cp",
                classpath,
                "-d",
                str(PROBE_BUILD),
                str(PROBE_SOURCE),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=180,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"failure probe javac failed:\n{completed.stdout}\n{completed.stderr}")
        PROBE_JAR.unlink(missing_ok=True)
        with zipfile.ZipFile(
            PROBE_JAR,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
        ) as archive:
            for class_file in sorted(PROBE_BUILD.rglob("*.class")):
                write_deterministic(
                    archive,
                    class_file.relative_to(PROBE_BUILD).as_posix(),
                    class_file.read_bytes(),
                )
            write_deterministic(
                archive,
                "plugin.yml",
                "\n".join(
                    [
                        "name: PremiumFailureProbe",
                        "version: '1.0.0'",
                        "main: premiumfailure.PremiumFailureProbe",
                        "api-version: '1.21'",
                        "depend: [ItemGuard]",
                        "commands:",
                        "  failureprobe:",
                        "    description: Premium failure fixture probe",
                        "    permission: itemguard.failureprobe",
                        "",
                    ]
                ).encode("utf-8"),
            )
        return PROBE_JAR
    except BaseException:
        cleanup_probe_artifacts()
        raise


def cleanup_probe_artifacts() -> dict[str, object]:
    removed: list[str] = []
    errors: list[str] = []
    for path in (PROBE_JAR, PROBE_BUILD):
        try:
            if path.is_dir():
                shutil.rmtree(path)
                removed.append(str(path))
            elif path.exists():
                path.unlink()
                removed.append(str(path))
        except OSError as failure:
            errors.append(f"{path}: {failure}")
    return {"removed": removed, "errors": errors, "clean": not errors}


def configure_mysql(port: int) -> str:
    config = SINGLE.configure_premium(SINGLE.CONFIG_SOURCE.read_text(encoding="utf-8"))
    config = config.replace(f"{MYSQL_PORT}/{MYSQL_DATABASE}", f"{port}/{MYSQL_DATABASE}", 1)
    config = config.replace('server-id: "paper-premium-1"', f'server-id: "{SERVER_ID}"', 1)
    scan_interval = "inventory-scan-interval: 12000  # controlled reliability fixture"
    config = config.replace(
        "inventory-scan-interval: 600  # 30 seconds at 20 TPS",
        scan_interval,
        1,
    )
    if f"{port}/{MYSQL_DATABASE}" not in config:
        raise RuntimeError(f"failed to bind MySQL port {port}")
    if f'server-id: "{SERVER_ID}"' not in config:
        raise RuntimeError("failed to bind failure server identity")
    if scan_interval not in config:
        raise RuntimeError("failed to disable automatic scan retention race")
    return config


def stage_server(
    root: Path,
    paper_port: int,
    mysql_port: int,
    probe_jar: Path | None,
) -> dict[str, object]:
    root.mkdir(parents=True, exist_ok=False)
    (root / "plugins").mkdir()
    shutil.copy2(PAPER_JAR, root / "paper.jar")
    shutil.copy2(PREMIUM_JAR, root / "plugins/ItemGuard-Premium.jar")
    if probe_jar is not None:
        shutil.copy2(probe_jar, root / "plugins/PremiumFailureProbe.jar")
    for directory in ("libraries", "versions", "cache"):
        source = PAPER_CACHE / directory
        if source.exists():
            shutil.copytree(source, root / directory)
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (root / "server.properties").write_text(
        "\n".join(
            [
                "server-ip=127.0.0.1",
                f"server-port={paper_port}",
                "online-mode=false",
                "enforce-secure-profile=false",
                "enable-rcon=false",
                "enable-query=false",
                "max-players=2",
                "view-distance=3",
                "simulation-distance=3",
                "spawn-protection=0",
                "gamemode=creative",
                "difficulty=peaceful",
                "level-type=minecraft:flat",
                'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}',
                "generate-structures=false",
                "allow-flight=true",
                "motd=ItemGuard Premium Reliability Fixture",
                "",
            ]
        ),
        encoding="utf-8",
    )
    data_folder = root / "plugins/ItemGuard"
    data_folder.mkdir()
    (data_folder / "config.yml").write_text(configure_mysql(mysql_port), encoding="utf-8")
    if probe_jar is not None:
        probe_data = root / "plugins/PremiumFailureProbe"
        probe_data.mkdir()
        (probe_data / "config.yml").write_text("probe:\n  enabled: true\n", encoding="utf-8")
    return {
        "root": str(root),
        "paper_port": paper_port,
        "mysql_port": mysql_port,
        "server_id": SERVER_ID,
        "paper_sha256": sha256(root / "paper.jar"),
        "premium_jar_sha256": sha256(root / "plugins/ItemGuard-Premium.jar"),
        "probe_jar_sha256": sha256(probe_jar) if probe_jar is not None else None,
        "config_sha256": sha256(data_folder / "config.yml"),
    }


def wait_marker(process, marker: str, timeout: float = 180.0) -> None:
    process.wait_for(marker, timeout)


def wait_command(process, command: str, marker: str, timeout: float = 90.0) -> None:
    before = len(process.lines)
    process.send(command)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(marker in line for line in process.lines[before:]):
            return
        if process.process.poll() is not None:
            raise RuntimeError(f"Paper exited with {process.process.returncode} during {command}")
        time.sleep(0.1)
    raise TimeoutError(f"marker timeout command={command} marker={marker}")


def main() -> int:
    for path, label in (
        (PAPER_JAR, "Paper jar"),
        (PREMIUM_JAR, "Premium jar"),
        (SINGLE.CONFIG_SOURCE, "config template"),
        (JAVA, "Java 21"),
        (JAVAC, "Javac 21"),
    ):
        if not path.is_file():
            raise RuntimeError(f"{label} missing: {path}")
    probe_jar = stage_probe_jar()
    fixture = EVIDENCE_BASE / f"itemguard-premium-failure-{uuid.uuid4().hex[:12]}"
    fixture.mkdir(parents=True, exist_ok=False)
    receipt: dict[str, object] = {
        "status": "FAILED",
        "fixture": str(fixture),
        "probe_sha256": sha256(probe_jar),
        "runner_sha256": sha256(Path(__file__).resolve()),
        "premium_jar_sha256": sha256(PREMIUM_JAR),
        "phases": {},
        "cleanup": [],
        "started_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    mysql_started = False
    paper = None
    try:
        status = run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing unowned reliability run")
        source_mysql_port = MYSQL_PORT
        unreachable_port = free_port()
        if unreachable_port == source_mysql_port:
            unreachable_port = free_port()
        startup_root = fixture / "startup-unreachable"
        startup_manifest = stage_server(startup_root, free_port(), unreachable_port, None)
        startup = SINGLE.PaperProcess(startup_root, 1, int(startup_manifest["paper_port"]))
        try:
            wait_marker(startup, "[ItemGuard] ItemGuard is disabled.", 120)
            if any("ItemGuard v1.0.0 is enabled." in line for line in startup.lines):
                raise RuntimeError("startup fail-closed probe saw an enabled marker")
            if any("MySQL database initialized" in line for line in startup.lines):
                raise RuntimeError("startup fail-closed probe saw MySQL initialization")
            if not any("Error occurred while enabling ItemGuard" in line for line in startup.lines):
                raise RuntimeError("startup failure marker missing")
            receipt["phases"]["startup_unreachable"] = {
                "status": "FAIL_CLOSED_PASS",
                "unreachable_mysql_port": unreachable_port,
                "enabled_marker_seen": False,
                "mysql_initialized_marker_seen": False,
                "lifecycle_disabled_marker_seen": True,
                "log_path": str(startup.log_path),
            }
        finally:
            receipt["cleanup"].append({"phase": "startup-unreachable", **startup.stop()})

        started = run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        runtime_root = fixture / "runtime-outage"
        runtime_manifest = stage_server(runtime_root, free_port(), MYSQL_PORT, probe_jar)
        receipt["runtime_manifest"] = runtime_manifest
        paper = SINGLE.PaperProcess(runtime_root, 2, int(runtime_manifest["paper_port"]))
        wait_marker(paper, "Done (", 180)
        wait_marker(paper, f"MySQL database initialized for server-id {SERVER_ID}", 45)
        wait_marker(paper, "ItemGuard v1.0.0 is enabled.", 45)
        wait_marker(paper, "PREMIUM_FAILURE_PROBE READY duplicates_detected=0", 45)
        paper.send("failureprobe baseline")
        wait_marker(paper, "PREMIUM_FAILURE_PROBE BASELINE_COMMITTED duplicates_detected=7", 45)
        if mysql_query("SELECT duplicates_detected FROM plugin_stats WHERE id=1;") != ["7"]:
            raise RuntimeError("fixture baseline was not committed as duplicates_detected=7")
        receipt["phases"]["runtime_ready"] = {
            "status": "PASS",
            "committed_baseline": 7,
            "duplicates_detected": 7,
        }
        paper.send("failureprobe outage-write")
        wait_marker(paper, "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_STARTED", 45)
        stopped = run_mysql("stop")
        if stopped.returncode != 0 or "process_gone=True" not in stopped.stdout or MYSQL.port_open():
            raise RuntimeError(f"MySQL outage stop was not clean: {stopped.stdout} {stopped.stderr}")
        receipt["phases"]["mysql_outage"] = {
            "status": "PORT_CLOSED_PASS",
            "stdout": stopped.stdout,
            "stderr": stopped.stderr,
        }
        mysql_started = False
        wait_marker(paper, "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_FAILED", 90)
        started_markers = sum(
            "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_STARTED" in line for line in paper.lines
        )
        failed_markers = sum(
            "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_FAILED" in line for line in paper.lines
        )
        unexpected_success_markers = sum(
            "PREMIUM_FAILURE_PROBE OUTAGE_WRITE_UNEXPECTED_SUCCESS" in line
            for line in paper.lines
        )
        if started_markers != 1 or failed_markers != 1 or unexpected_success_markers != 0:
            raise RuntimeError(
                "runtime outage write markers were not exactly one start/one failure/zero success: "
                f"started={started_markers} failed={failed_markers} "
                f"unexpected_success={unexpected_success_markers}"
            )
        receipt["phases"]["outage_write"] = {
            "status": "FAIL_CLOSED_NO_RETRY_PASS",
            "started_markers": started_markers,
            "failed_markers": failed_markers,
            "unexpected_success_markers": unexpected_success_markers,
        }
        restarted = run_mysql("start")
        if restarted.returncode != 0:
            raise RuntimeError(f"MySQL recovery start failed: {restarted.stdout}\n{restarted.stderr}")
        mysql_started = True
        wait_command(paper, "failureprobe recovery-read", "PREMIUM_FAILURE_PROBE RECOVERY_READ_PASS", 90)
        wait_marker(paper, "PREMIUM_FAILURE_PROBE RECOVERY_STABLE_PASS", 15)
        rows = mysql_query("SELECT duplicates_detected FROM plugin_stats WHERE id=1;")
        if rows != ["7"]:
            raise RuntimeError(f"unexpected post-outage state: {rows!r}")
        receipt["phases"]["recovery"] = {
            "status": "COMMITTED_STATE_PRESERVED_PASS",
            "expected_committed_value": 7,
            "rows": rows,
        }
        cleanup = paper.stop()
        receipt["cleanup"].append({"phase": "runtime-outage", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"runtime Paper cleanup was not clean: {cleanup}")
        receipt["status"] = "PASS"
    finally:
        if paper is not None:
            receipt["cleanup"].append({"phase": "runtime-outage", **paper.stop()})
        if mysql_started:
            stopped = run_mysql("stop")
            receipt["mysql_cleanup"] = {
                "exit": stopped.returncode,
                "port_closed": not MYSQL.port_open(),
                "process_gone": "process_gone=True" in stopped.stdout,
                "stdout": stopped.stdout,
                "stderr": stopped.stderr,
            }
        else:
            receipt["mysql_cleanup"] = {
                "exit": 0,
                "port_closed": not MYSQL.port_open(),
                "process_gone": True,
                "stdout": "outage phase already stopped MySQL",
                "stderr": "",
            }
        receipt["probe_temp_cleanup"] = cleanup_probe_artifacts()
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        save(fixture / "premium-failure-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
