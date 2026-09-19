"""Controlled two-Paper-server shared-MySQL Premium fixture.

Stages two isolated Paper roots with the exact Premium JAR, starts one disposable MySQL fixture,
starts Paper server-1 and server-2 concurrently against the same schema, and runs the fixture-only
TwoServerProbe plugin. The probe uses ItemGuard's real DatabaseManager and CrossServerFindingPolicy
inside each Paper JVM. All owned Paper/MySQL processes are stopped in finally.
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
PROBE_SOURCE = ROOT / "tools/premium-runtime/TwoServerProbe.java"
COMPILE_CLASSPATH_FILE = Path("C:/Users/thanh/AppData/Local/Temp/itemguard-premium-classpath.txt")
PAPER_CACHE = Path("E:/AI.WORK/itemguard-paper-smoke")
PAPER_JAR = PAPER_CACHE / "paper.jar"
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = Path("C:/Program Files/Java/jdk-21/bin/java.exe")
JAVAC = Path("C:/Program Files/Java/jdk-21/bin/javac.exe")


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
        raise RuntimeError(f"probe source missing: {PROBE_SOURCE}")
    if not COMPILE_CLASSPATH_FILE.is_file():
        raise RuntimeError(f"Maven compile classpath missing: {COMPILE_CLASSPATH_FILE}")
    build = ROOT / "tools/premium-runtime/.two-server-probe-build"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    dependency_classpath = COMPILE_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join((dependency_classpath, str(PREMIUM_JAR)))
    completed = subprocess.run(
        [str(JAVAC), "--release", "21", "-cp", classpath, "-d", str(build), str(PROBE_SOURCE)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"probe javac failed:\n{completed.stdout}\n{completed.stderr}")
    jar = ROOT / "tools/premium-runtime/.two-server-probe.jar"
    if jar.exists():
        jar.unlink()
    def write_deterministic(archive: zipfile.ZipFile, name: str, content: bytes) -> None:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = 0o644 << 16
        archive.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for class_file in sorted(build.rglob("*.class")):
            write_deterministic(
                archive,
                class_file.relative_to(build).as_posix(),
                class_file.read_bytes(),
            )
        write_deterministic(
            archive,
            "plugin.yml",
            "\n".join(
                [
                    "name: PremiumTwoServerProbe",
                    "version: '1.0.0'",
                    "main: premiumprobe.TwoServerProbe",
                    "api-version: '1.21'",
                    "depend: [ItemGuard]",
                    "",
                ]
            ).encode("utf-8"),
        )
    return jar


def stage_server(root: Path, server_id: str, paper_port: int, probe_jar: Path) -> dict[str, object]:
    root.mkdir(parents=True, exist_ok=False)
    (root / "plugins").mkdir()
    shutil.copy2(PAPER_JAR, root / "paper.jar")
    shutil.copy2(PREMIUM_JAR, root / "plugins/ItemGuard-Premium.jar")
    shutil.copy2(probe_jar, root / "plugins/PremiumTwoServerProbe.jar")
    for directory in ("libraries", "versions", "cache"):
        source = PAPER_CACHE / directory
        if source.exists():
            shutil.copytree(source, root / directory)
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    properties = "\n".join(
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
            f"motd=ItemGuard Premium Two Server {server_id}",
            "",
        ]
    )
    (root / "server.properties").write_text(properties, encoding="utf-8")
    data_folder = root / "plugins/ItemGuard"
    data_folder.mkdir()
    config = SINGLE.configure_premium(SINGLE.CONFIG_SOURCE.read_text(encoding="utf-8"))
    config = config.replace('server-id: "paper-premium-1"', f'server-id: "{server_id}"', 1)
    scan_interval = "inventory-scan-interval: 12000  # controlled two-server fixture"
    config = config.replace(
        "inventory-scan-interval: 600  # 30 seconds at 20 TPS",
        scan_interval,
        1,
    )
    if f'server-id: "{server_id}"' not in config:
        raise RuntimeError(f"staged config did not bind server identity {server_id}")
    if scan_interval not in config:
        raise RuntimeError("staged config did not disable automatic scan retention race")
    (data_folder / "config.yml").write_text(config, encoding="utf-8")
    probe_data = root / "plugins/PremiumTwoServerProbe"
    probe_data.mkdir()
    (probe_data / "config.yml").write_text(
        "\n".join(
            [
                "probe:",
                f"  server-id: {server_id}",
                f'  jdbc-url: "jdbc:mysql://{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DATABASE}?allowPublicKeyRetrieval=true&sslMode=PREFERRED&connectionTimeZone=UTC"',
                f"  user: {MYSQL_USER}",
                f"  password: {MYSQL_PASSWORD}",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return {
        "root": str(root),
        "server_id": server_id,
        "paper_port": paper_port,
        "paper_sha256": sha256(root / "paper.jar"),
        "premium_jar_sha256": sha256(root / "plugins/ItemGuard-Premium.jar"),
        "probe_jar_sha256": sha256(probe_jar),
        "config_sha256": sha256(data_folder / "config.yml"),
    }


def read_json(path: Path, timeout: float = 60.0) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass
        time.sleep(0.1)
    raise TimeoutError(f"receipt timeout: {path}")


def main() -> int:
    for path, label in (
        (PAPER_JAR, "Paper jar"),
        (PREMIUM_JAR, "Premium jar"),
        (JAVA, "Java 21"),
        (JAVAC, "Javac 21"),
    ):
        if not path.is_file():
            raise RuntimeError(f"{label} missing: {path}")
    probe_jar = stage_probe_jar()
    fixture = EVIDENCE_BASE / f"itemguard-premium-two-paper-{uuid.uuid4().hex[:12]}"
    server_one_root = fixture / "server-1"
    server_two_root = fixture / "server-2"
    fixture.mkdir(parents=True, exist_ok=False)
    manifest = {
        "fixture": str(fixture),
        "paper_sha256": sha256(PAPER_JAR),
        "premium_jar_sha256": sha256(PREMIUM_JAR),
        "probe_jar_sha256": sha256(probe_jar),
        "runner_sha256": sha256(Path(__file__).resolve()),
        "mysql_host": MYSQL_HOST,
        "mysql_port": MYSQL_PORT,
        "mysql_database": MYSQL_DATABASE,
        "created_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    receipt: dict[str, object] = {
        "status": "FAILED",
        "manifest": manifest,
        "server_one": None,
        "server_two": None,
        "mysql_cleanup": None,
        "cleanup": [],
    }
    mysql_started = False
    first = None
    second = None
    try:
        status = run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing unowned shared server")
        started = run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()
        first_manifest = stage_server(server_one_root, "server-1", free_port(), probe_jar)
        second_manifest = stage_server(server_two_root, "server-2", free_port(), probe_jar)
        receipt["server_one"] = first_manifest
        receipt["server_two"] = second_manifest
        first = SINGLE.PaperProcess(server_one_root, 1, int(first_manifest["paper_port"]))
        first.wait_for("Done (", 180)
        first.wait_for("PREMIUM_TWO_SERVER_PROBE READY server-1", 60)
        second = SINGLE.PaperProcess(server_two_root, 2, int(second_manifest["paper_port"]))
        second.wait_for("Done (", 180)
        # This waits for the cross-server sighting, which is answered from `item_observations`. It
        # could never arrive while each server's completed audit deleted every row older than its own
        # current epoch — including the rows the other server had just written. The retention rule is
        # time-based now (anti-dupe.observation-retention-minutes), so rows inside the window survive
        # whoever wrote them, and this marker is the proof.
        second.wait_for("PREMIUM_TWO_SERVER_PROBE PASS server-2", 90)
        first_receipt = read_json(server_one_root / "plugins/PremiumTwoServerProbe/probe-receipt.json")
        second_receipt = read_json(server_two_root / "plugins/PremiumTwoServerProbe/probe-receipt.json")
        if first_receipt.get("status") != "READY" or second_receipt.get("status") != "PASS":
            raise RuntimeError(f"probe receipts: {first_receipt!r}, {second_receipt!r}")
        rows = mysql_query(
            "SELECT server_id, COUNT(*) FROM item_observations WHERE code='PAPER2S' "
            "GROUP BY server_id ORDER BY server_id;"
            "SELECT COUNT(*) FROM tracked_items WHERE code='PAPER2S';"
            "SELECT COUNT(*) FROM item_observations WHERE code='PAPER2S';"
        )
        if rows != ["server-1\t2", "server-2\t1", "1", "3"]:
            raise RuntimeError(f"unexpected shared MySQL postcondition: {rows!r}")
        receipt["probe_one"] = first_receipt
        receipt["probe_two"] = second_receipt
        receipt["mysql_postcondition"] = rows
        receipt["status"] = "PASS"
    finally:
        if second is not None:
            receipt["cleanup"].append({"server": "server-2", **second.stop()})
        if first is not None:
            receipt["cleanup"].append({"server": "server-1", **first.stop()})
        if mysql_started:
            stopped = run_mysql("stop")
            receipt["mysql_cleanup"] = {
                "exit": stopped.returncode,
                "port_closed": not MYSQL.port_open(),
                "process_gone": "process_gone=True" in stopped.stdout,
                "stdout": stopped.stdout,
                "stderr": stopped.stderr,
            }
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        save(fixture / "two-paper-mysql-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
