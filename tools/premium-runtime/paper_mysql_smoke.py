"""Controlled Premium Paper + MySQL startup/restart fixture.

This runner is deliberately separate from the frozen LITE smoke runner. It stages the current
Premium shaded jar into a fresh fixture root, starts the disposable local MySQL fixture, runs Paper
through two clean generations against the same schema, and stops both owned processes in finally.
It records raw Paper logs and a derived receipt under 30_KET_QUA_THU_NGHIEM/.
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
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MYSQL_FIXTURE_PATH = ROOT / "tools/mysql-runtime/mysql_fixture.py"
PAPER_CACHE = Path("E:/AI.WORK/itemguard-paper-smoke")
PAPER_JAR = PAPER_CACHE / "paper.jar"
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = Path("C:/Program Files/Java/jdk-21/bin/java.exe")
CONFIG_SOURCE = ROOT / "src/main/resources/config.yml"


def load_mysql_fixture():
    spec = importlib.util.spec_from_file_location("itemguard_mysql_fixture", MYSQL_FIXTURE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load MySQL fixture module: {MYSQL_FIXTURE_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MYSQL = load_mysql_fixture()
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


def run_mysql_fixture(action: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(MYSQL_FIXTURE_PATH), action],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=900,
    )


def mysql_query(sql: str) -> str:
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
            "-e",
            sql,
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if result.returncode != 0:
        raise RuntimeError(f"MySQL verification query failed: {result.stderr.strip()}")
    return result.stdout.strip()


def configure_premium(source: str) -> str:
    replacements = (
        ("  type: SQLITE", "  type: MYSQL"),
        ("  language: vi", "  language: en"),
        ('    url: ""', f'    url: "jdbc:mysql://{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DATABASE}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"'),
        ('    user: ""', f'    user: "{MYSQL_USER}"'),
        ('    password: ""', f'    password: "{MYSQL_PASSWORD}"'),
        ("    maximum-pool-size: 10", "    maximum-pool-size: 2"),
        ("    minimum-idle: 2", "    minimum-idle: 1"),
        ("    connection-timeout-ms: 30000", "    connection-timeout-ms: 5000"),
        ('  server-id: ""', '  server-id: "paper-premium-1"'),
    )
    configured = source
    for old, new in replacements:
        if old not in configured:
            raise RuntimeError(f"Premium config template fragment missing: {old!r}")
        configured = configured.replace(old, new, 1)
    return configured


def stage_fixture() -> tuple[Path, dict[str, object]]:
    for path, label in (
        (PAPER_JAR, "Paper jar"),
        (PREMIUM_JAR, "Premium jar"),
        (CONFIG_SOURCE, "config template"),
        (JAVA, "JDK 21 java"),
    ):
        if not path.is_file():
            raise RuntimeError(f"{label} missing: {path}")
    fixture = EVIDENCE_BASE / f"itemguard-premium-paper-{uuid.uuid4().hex[:12]}"
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / "plugins").mkdir()
    shutil.copy2(PAPER_JAR, fixture / "paper.jar")
    shutil.copy2(PREMIUM_JAR, fixture / "plugins/ItemGuard-Premium.jar")
    for directory in ("libraries", "versions", "cache"):
        source = PAPER_CACHE / directory
        if source.exists():
            shutil.copytree(source, fixture / directory)

    (fixture / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    port = free_port()
    properties = "\n".join(
        [
            "server-ip=127.0.0.1",
            f"server-port={port}",
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
            "motd=ItemGuard Premium MySQL Controlled Fixture",
            "",
        ]
    )
    (fixture / "server.properties").write_text(properties, encoding="utf-8")
    data_folder = fixture / "plugins/ItemGuard"
    data_folder.mkdir()
    (data_folder / "config.yml").write_text(
        configure_premium(CONFIG_SOURCE.read_text(encoding="utf-8")),
        encoding="utf-8",
    )

    manifest = {
        "root": str(fixture),
        "server_port": port,
        "mysql_host": MYSQL_HOST,
        "mysql_port": MYSQL_PORT,
        "mysql_database": MYSQL_DATABASE,
        "paper_sha256": sha256(fixture / "paper.jar"),
        "premium_jar_sha256": sha256(fixture / "plugins/ItemGuard-Premium.jar"),
        "premium_source_sha256": sha256(PREMIUM_JAR),
        "config_sha256": sha256(data_folder / "config.yml"),
        "runner_sha256": sha256(Path(__file__).resolve()),
        "java": str(JAVA),
        "created_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    save(fixture / "stage.json", manifest)
    return fixture, manifest


class PaperProcess:
    def __init__(self, fixture: Path, generation: int, port: int):
        self.fixture = fixture
        self.generation = generation
        self.port = port
        self.lines: list[str] = []
        self.log_path = fixture / f"paper-{generation}.log"
        self.log = self.log_path.open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            [
                str(JAVA),
                "-Xms512M",
                "-Xmx1024M",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                "paper.jar",
                "--nogui",
            ],
            cwd=fixture,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        try:
            for line in self.process.stdout:
                self.lines.append(line)
                self.log.write(line)
                self.log.flush()
        finally:
            self.process.stdout.close()
            self.log.close()

    def send(self, command: str) -> None:
        if self.process.stdin is None:
            raise RuntimeError("Paper stdin is unavailable")
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait_for(self, marker: str, timeout: float = 180.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if any("Could not load plugin" in line or "Failed to load plugin" in line for line in self.lines):
                raise RuntimeError(f"Paper plugin load failure while waiting for {marker}")
            if any(marker in line for line in self.lines):
                return
            if self.process.poll() is not None:
                raise RuntimeError(
                    f"Paper exited with {self.process.returncode} while waiting for {marker}"
                )
            time.sleep(0.1)
        raise TimeoutError(f"Paper marker timeout: {marker}")

    def count(self, marker: str) -> int:
        return sum(marker in line for line in self.lines)

    def wait_for_new(self, marker: str, previous: int, timeout: float = 180.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if any("Could not load plugin" in line or "Failed to load plugin" in line for line in self.lines):
                raise RuntimeError(f"Paper plugin load failure while waiting for new {marker}")
            if self.count(marker) > previous:
                return
            if self.process.poll() is not None:
                raise RuntimeError(
                    f"Paper exited with {self.process.returncode} while waiting for new {marker}"
                )
            time.sleep(0.1)
        raise TimeoutError(f"Paper new-marker timeout: {marker}")

    def stop(self) -> dict[str, object]:
        forced = False
        if self.process.poll() is None:
            try:
                self.send("stop")
            except (BrokenPipeError, OSError, ValueError):
                pass
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=15)
                forced = True
        if self.process.stdin is not None:
            try:
                self.process.stdin.close()
            except (BrokenPipeError, OSError, ValueError):
                pass
        self.reader.join(timeout=15)
        return {
            "pid": self.process.pid,
            "exit": self.process.returncode,
            "forced": forced,
            "log_closed": not self.reader.is_alive(),
            "port_released": port_released(self.port),
        }


def verify_generation(process: PaperProcess) -> dict[str, object]:
    process.wait_for("Done (", 180)
    process.wait_for("MySQL database initialized for server-id paper-premium-1", 30)
    process.wait_for("ItemGuard v1.0.0 is enabled.", 30)
    slf4j_errors = [
        line.strip()
        for line in process.lines
        if "SLF4J: No SLF4J providers were found" in line
        or "SLF4J: Defaulting to no-operation" in line
    ]
    if slf4j_errors:
        raise RuntimeError(f"Premium shaded jar has SLF4J provider errors: {slf4j_errors}")
    info_before = process.count("MYSQL")
    process.send("ig info")
    process.wait_for_new("MYSQL", info_before, 30)
    stats_before = process.count("MYSQL")
    process.send("ig stats")
    process.wait_for_new("MYSQL", stats_before, 30)
    rows = mysql_query(
        "SELECT schema_version, duplicates_detected FROM itemguard_premium.plugin_stats WHERE id = 1;"
        "SELECT COUNT(*) FROM information_schema.tables "
        "WHERE table_schema = 'itemguard_premium' "
        "AND table_name IN ('tracked_items','item_history','item_observations','duplicate_findings',"
        "'item_search_requests','item_snapshots','reclaim_claims','tag_publications','plugin_stats');"
        "SELECT @@innodb_flush_log_at_trx_commit, @@sql_mode;"
    ).splitlines()
    if len(rows) < 3 or rows[0] != "9\t0" or rows[1] != "9":
        raise RuntimeError(f"Unexpected MySQL runtime state: {rows!r}")
    if "STRICT_TRANS_TABLES" not in rows[2] or not rows[2].startswith("1\t"):
        raise RuntimeError(f"Unexpected MySQL durability/session state: {rows!r}")
    return {
        "mysql_query_rows": rows,
        "info_marker": "Cơ sở dữ liệu: MYSQL",
        "stats_marker": "Database: MYSQL",
        "slf4j_provider_errors": slf4j_errors,
        "paper_log": str(process.log_path),
    }


def main() -> int:
    fixture, manifest = stage_fixture()
    receipt: dict[str, object] = {
        "status": "FAILED",
        "fixture": str(fixture),
        "stage": manifest,
        "generations": [],
        "cleanup": [],
        "started_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    mysql_started = False
    paper: PaperProcess | None = None
    try:
        status = run_mysql_fixture("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture is already running; refusing to use an unowned server")
        started = run_mysql_fixture("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL fixture start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = run_mysql_fixture("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL fixture reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()
        for generation in (1, 2):
            paper = PaperProcess(fixture, generation, int(manifest["server_port"]))
            receipt["generations"].append(
                {"generation": generation, "verification": verify_generation(paper)}
            )
            cleanup = paper.stop()
            receipt["cleanup"].append({"generation": generation, **cleanup})
            paper = None
            if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
                raise RuntimeError(f"Paper cleanup was not clean: {cleanup}")
        receipt["status"] = "PASS"
    except BaseException as failure:
        receipt["error"] = repr(failure)
        raise
    finally:
        if paper is not None:
            receipt["cleanup"].append({"generation": paper.generation, **paper.stop()})
        if mysql_started:
            stopped = run_mysql_fixture("stop")
            receipt["mysql_cleanup"] = {
                "exit": stopped.returncode,
                "port_closed": not MYSQL.port_open(),
                "process_gone": "process_gone=True" in stopped.stdout,
                "stdout": stopped.stdout,
                "stderr": stopped.stderr,
            }
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        save(fixture / "premium-paper-mysql-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
