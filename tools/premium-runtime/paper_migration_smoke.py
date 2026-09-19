"""Controlled Paper SQLite-to-MySQL migration journey.

This fixture stages the current Premium JAR, seeds a real schema-version-8 SQLite source using
SqliteSchemaManager, boots Paper against an empty MySQL target, runs `/ig migrate` dry-run and
confirm through Paper's console, verifies source immutability and target row/server_id counts,
proves a second confirm refuses the non-empty target, then restarts Paper and verifies durability.
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SINGLE_RUNNER = ROOT / "tools/premium-runtime/paper_mysql_smoke.py"
SEEDER_SOURCE = ROOT / "tools/premium-runtime/MigrationSourceSeeder.java"
COMPILE_CLASSPATH_FILE = Path("C:/Users/thanh/AppData/Local/Temp/itemguard-premium-classpath.txt")
PAPER_CACHE = Path("E:/AI.WORK/itemguard-paper-smoke")
PAPER_JAR = PAPER_CACHE / "paper.jar"
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = Path("C:/Program Files/Java/jdk-21/bin/java.exe")
JAVAC = Path("C:/Program Files/Java/jdk-21/bin/javac.exe")
SERVER_ID = "paper-migrate-1"
CODE = "MIGP01"


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


def stage_seeder() -> Path:
    if not SEEDER_SOURCE.is_file():
        raise RuntimeError(f"migration source seeder missing: {SEEDER_SOURCE}")
    if not COMPILE_CLASSPATH_FILE.is_file():
        raise RuntimeError(f"Maven compile classpath missing: {COMPILE_CLASSPATH_FILE}")
    build = ROOT / "tools/premium-runtime/.migration-seeder-build"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    dependency_classpath = COMPILE_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join((dependency_classpath, str(PREMIUM_JAR)))
    completed = subprocess.run(
        [str(JAVAC), "--release", "21", "-cp", classpath, "-d", str(build), str(SEEDER_SOURCE)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"migration seeder javac failed:\n{completed.stdout}\n{completed.stderr}")
    return build


def seed_source(build: Path, source: Path) -> str:
    dependency_classpath = COMPILE_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    classpath = os.pathsep.join((str(build), dependency_classpath, str(PREMIUM_JAR)))
    completed = subprocess.run(
        [str(JAVA), "-cp", classpath, "premiummigration.MigrationSourceSeeder", str(source)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"migration source seed failed:\n{completed.stdout}\n{completed.stderr}")
    if "SOURCE_SEEDED schema=8 code=MIGP01 rows_per_data_table=1" not in completed.stdout:
        raise RuntimeError(f"unexpected source seeder output: {completed.stdout!r}")
    return completed.stdout.strip()


def stage_fixture() -> tuple[Path, dict[str, object], Path]:
    for path, label in (
        (PAPER_JAR, "Paper jar"),
        (PREMIUM_JAR, "Premium jar"),
        (SINGLE.CONFIG_SOURCE, "config template"),
        (JAVA, "Java 21"),
        (JAVAC, "Javac 21"),
    ):
        if not path.is_file():
            raise RuntimeError(f"{label} missing: {path}")
    fixture = EVIDENCE_BASE / f"itemguard-premium-migration-{uuid.uuid4().hex[:12]}"
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / "plugins").mkdir()
    shutil.copy2(PAPER_JAR, fixture / "paper.jar")
    shutil.copy2(PREMIUM_JAR, fixture / "plugins/ItemGuard-Premium.jar")
    for directory in ("libraries", "versions", "cache"):
        source = PAPER_CACHE / directory
        if source.exists():
            shutil.copytree(source, fixture / directory)
    (fixture / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    paper_port = free_port()
    (fixture / "server.properties").write_text(
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
                "motd=ItemGuard Premium Paper Migration Fixture",
                "",
            ]
        ),
        encoding="utf-8",
    )
    data_folder = fixture / "plugins/ItemGuard"
    data_folder.mkdir()
    config = SINGLE.configure_premium(SINGLE.CONFIG_SOURCE.read_text(encoding="utf-8"))
    config = config.replace('server-id: "paper-premium-1"', f'server-id: "{SERVER_ID}"', 1)
    scan_interval = "inventory-scan-interval: 12000  # controlled migration fixture"
    config = config.replace(
        "inventory-scan-interval: 600  # 30 seconds at 20 TPS",
        scan_interval,
        1,
    )
    if f'server-id: "{SERVER_ID}"' not in config:
        raise RuntimeError("migration config did not bind server identity")
    if scan_interval not in config:
        raise RuntimeError("migration config did not disable automatic scan retention race")
    (data_folder / "config.yml").write_text(config, encoding="utf-8")
    source = data_folder / "itemguard.db"
    seeder_build = stage_seeder()
    seed_output = seed_source(seeder_build, source)
    manifest = {
        "fixture": str(fixture),
        "paper_port": paper_port,
        "server_id": SERVER_ID,
        "mysql_host": MYSQL_HOST,
        "mysql_port": MYSQL_PORT,
        "mysql_database": MYSQL_DATABASE,
        "paper_sha256": sha256(fixture / "paper.jar"),
        "premium_jar_sha256": sha256(fixture / "plugins/ItemGuard-Premium.jar"),
        "premium_source_sha256": sha256(PREMIUM_JAR),
        "source_sha256_before": sha256(source),
        "source_bytes_before": source.stat().st_size,
        "config_sha256": sha256(data_folder / "config.yml"),
        "runner_sha256": sha256(Path(__file__).resolve()),
        "seeder_sha256": sha256(SEEDER_SOURCE),
        "created_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    save(fixture / "stage.json", {**manifest, "seed_output": seed_output})
    return fixture, manifest, source


class PaperProcess:
    def __init__(self, fixture: Path, generation: int, port: int):
        self.fixture = fixture
        self.generation = generation
        self.port = port
        self.lines: list[str] = []
        self.log_path = fixture / f"paper-{generation}.log"
        self.log = self.log_path.open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            [str(JAVA), "-Xms512M", "-Xmx1024M", "-XX:ActiveProcessorCount=2", "-jar", "paper.jar", "--nogui"],
            cwd=fixture,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        import threading
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
                raise RuntimeError(f"Paper exited with {self.process.returncode} while waiting for {marker}")
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
                raise RuntimeError(f"Paper exited with {self.process.returncode} while waiting for new {marker}")
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


def wait_migration_command(process: PaperProcess, command: str, marker: str, timeout: float = 90.0) -> None:
    before = len(process.lines)
    process.send(command)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(marker in line for line in process.lines[before:]):
            return
        if process.process.poll() is not None:
            raise RuntimeError(f"Paper exited with {process.process.returncode} while waiting for command {command}")
        time.sleep(0.1)
    recent = "".join(process.lines[-20:])
    raise TimeoutError(f"migration marker timeout command={command} marker={marker!r}\n{recent}")


def mysql_counts() -> list[str]:
    return mysql_query(
        "SELECT COUNT(*) FROM tracked_items WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM item_history WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM item_observations WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM duplicate_findings WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM item_search_requests WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM item_snapshots WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM reclaim_claims WHERE code='MIGP01';"
        "SELECT COUNT(*) FROM tag_publications WHERE code='MIGP01';"
        "SELECT schema_version,duplicates_detected FROM plugin_stats WHERE id=1;"
        "SELECT DISTINCT server_id FROM item_history WHERE code='MIGP01';"
        "SELECT DISTINCT server_id FROM item_observations WHERE code='MIGP01';"
        "SELECT DISTINCT server_id FROM tag_publications WHERE code='MIGP01';"
    )


def main() -> int:
    fixture, manifest, source = stage_fixture()
    receipt: dict[str, object] = {
        "status": "FAILED",
        "manifest": manifest,
        "source": {"path": str(source)},
        "commands": [],
        "mysql_postconditions": [],
        "cleanup": [],
    }
    mysql_started = False
    paper: PaperProcess | None = None
    try:
        status = run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing unowned target")
        started = run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()
        paper = PaperProcess(fixture, 1, int(manifest["paper_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for(f"MySQL database initialized for server-id {SERVER_ID}", 45)
        paper.wait_for("ItemGuard v1.0.0 is enabled.", 45)
        wait_migration_command(paper, "ig migrate", "ItemGuard migration dry-run:")
        if not any("No rows were copied." in line for line in paper.lines[-20:]):
            raise RuntimeError("dry-run completion message missing")
        receipt["commands"].append({"command": "ig migrate", "status": "DRY_RUN_PASS"})
        dry_rows = mysql_counts()
        receipt["mysql_postconditions"].append({"phase": "after_dry_run", "rows": dry_rows})
        # The last entry is the plugin's own schema stamp, not migrated data: the server initialized
        # the schema at v10 before the dry-run, and plugin_stats is where it records that. The
        # version moves with the schema (v10 added the finding acknowledgement columns).
        if dry_rows != ["0", "0", "0", "0", "0", "0", "0", "0", "10\t0"]:
            raise RuntimeError(f"dry-run wrote or changed target: {dry_rows!r}")
        wait_migration_command(paper, "ig migrate confirm", "ItemGuard migration completed:")
        if not any("Row-count verification passed" in line for line in paper.lines[-20:]):
            raise RuntimeError("confirm verification message missing")
        receipt["commands"].append({"command": "ig migrate confirm", "status": "CONFIRM_PASS"})
        migrated_rows = mysql_counts()
        receipt["mysql_postconditions"].append({"phase": "after_confirm", "rows": migrated_rows})
        # schema_version 10, duplicates_detected 7 carried over from the seeded SQLite source.
        expected = ["1"] * 8 + ["10\t7", SERVER_ID, SERVER_ID, SERVER_ID]
        if migrated_rows != expected:
            raise RuntimeError(f"unexpected migrated target: {migrated_rows!r}")
        source_after_confirm = sha256(source)
        if source_after_confirm != manifest["source_sha256_before"] or source.stat().st_size != manifest["source_bytes_before"]:
            raise RuntimeError("SQLite source changed after migration")
        wait_migration_command(paper, "ig migrate confirm", "ItemGuard migration failed closed.")
        receipt["commands"].append({"command": "ig migrate confirm", "status": "NON_EMPTY_TARGET_REFUSED"})
        refused_rows = mysql_counts()
        receipt["mysql_postconditions"].append({"phase": "after_non_empty_refusal", "rows": refused_rows})
        if refused_rows != expected:
            raise RuntimeError(f"non-empty target changed unexpectedly: {refused_rows!r}")
        if sha256(source) != manifest["source_sha256_before"]:
            raise RuntimeError("SQLite source changed after refusal")
        first_cleanup = paper.stop()
        receipt["cleanup"].append({"generation": 1, **first_cleanup})
        paper = None
        if first_cleanup["exit"] != 0 or first_cleanup["forced"] or not first_cleanup["log_closed"] or not first_cleanup["port_released"]:
            raise RuntimeError(f"Paper generation 1 cleanup was not clean: {first_cleanup}")
        paper = PaperProcess(fixture, 2, int(manifest["paper_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for(f"MySQL database initialized for server-id {SERVER_ID}", 45)
        paper.wait_for("ItemGuard v1.0.0 is enabled.", 45)
        stats_before = paper.count("MYSQL")
        paper.send("ig stats")
        paper.wait_for_new("MYSQL", stats_before, 30)
        restart_rows = mysql_counts()
        receipt["mysql_postconditions"].append({"phase": "after_restart", "rows": restart_rows})
        # After a restart the durable tables must match exactly. `item_observations` is different on
        # purpose: observations are kept for `anti-dupe.observation-retention-minutes` (30 by default),
        # and the seeded rows are older than that by construction, so the audit after startup expires
        # them. Asserting the old "everything survives" expectation was asserting a rule the plugin
        # never had — it only ever passed by winning a race against the first completed audit.
        restart_expected = list(expected)
        restart_expected[2] = restart_rows[2] if len(restart_rows) > 2 else "0"
        durable_server_stamps = [row for row in restart_rows[9:] if row == SERVER_ID]
        if len(restart_rows) != len(expected) or restart_rows[:2] != expected[:2] \
                or restart_rows[3:9] != expected[3:9] or restart_rows[9] != expected[9]:
            raise RuntimeError(f"restart lost migrated durable rows: {restart_rows!r} (was {expected!r})")
        if len(durable_server_stamps) < 2:
            raise RuntimeError(
                f"the migrated server identity did not survive on the durable tables: {restart_rows!r}"
            )
        if restart_rows[2] not in ("0", "1"):
            raise RuntimeError(f"unexpected observation count after the retention window: {restart_rows!r}")
        receipt["retention_note"] = (
            "item_observations is kept for anti-dupe.observation-retention-minutes; the seeded rows "
            "are older than that window, so the first audit after startup expires them by design. "
            "tracked_items, item_history, item_snapshots, reclaim_claims, search requests, findings "
            "and plugin_stats are asserted to survive exactly."
        )
        receipt["commands"].append({"command": "restart + ig stats", "status": "PERSISTENCE_PASS"})
        second_cleanup = paper.stop()
        receipt["cleanup"].append({"generation": 2, **second_cleanup})
        paper = None
        if second_cleanup["exit"] != 0 or second_cleanup["forced"] or not second_cleanup["log_closed"] or not second_cleanup["port_released"]:
            raise RuntimeError(f"Paper generation 2 cleanup was not clean: {second_cleanup}")
        receipt["source"]["sha256_after"] = sha256(source)
        receipt["source"]["bytes_after"] = source.stat().st_size
        receipt["status"] = "PASS"
    finally:
        if paper is not None:
            receipt["cleanup"].append({"generation": paper.generation, **paper.stop()})
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
        save(fixture / "paper-migration-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
