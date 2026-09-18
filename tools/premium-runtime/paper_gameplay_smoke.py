"""Controlled Premium real-player gameplay journey on Paper + MySQL.

Stages the frozen Premium shaded jar into a fresh fixture root, starts the disposable local MySQL
fixture, and runs two Paper generations: generation 1 performs the full real-client journey
(/give, real equip, real drop, real walk-pickup, real chest put/take, /ig commands, legacy browser
GUI and Premium catalog GUI); generation 2 proves the identity, inventory and MySQL rows survived a
clean stop/restart. Both client bots are real Mineflayer protocol clients. Every owned process is
stopped in finally and the receipt is written even when the run fails.
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
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SINGLE_RUNNER = ROOT / "tools/premium-runtime/paper_mysql_smoke.py"
PROBE_SOURCE = ROOT / "tools/premium-runtime/PremiumGameplayProbe.java"
CLIENT_SCRIPT = ROOT / "tools/premium-runtime/premium_gameplay_client.cjs"
COMPILE_CLASSPATH_FILE = Path("C:/Users/thanh/AppData/Local/Temp/itemguard-premium-classpath.txt")
PAPER_CACHE = Path("E:/AI.WORK/itemguard-paper-smoke")
PAPER_JAR = PAPER_CACHE / "paper.jar"
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVA = Path("C:/Program Files/Java/jdk-21/bin/java.exe")
JAVAC = Path("C:/Program Files/Java/jdk-21/bin/javac.exe")
SERVER_ID = "paper-premium-1"


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
NODE = shutil.which("node") or "node"


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


def offline_uuid(name: str) -> str:
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def stage_probe_jar() -> Path:
    if not PROBE_SOURCE.is_file():
        raise RuntimeError(f"probe source missing: {PROBE_SOURCE}")
    if not COMPILE_CLASSPATH_FILE.is_file():
        raise RuntimeError(f"Maven compile classpath missing: {COMPILE_CLASSPATH_FILE}")
    build = ROOT / "tools/premium-runtime/.gameplay-probe-build"
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
        raise RuntimeError(f"gameplay probe javac failed:\n{completed.stdout}\n{completed.stderr}")
    jar = ROOT / "tools/premium-runtime/.gameplay-probe.jar"
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
                    "name: PremiumGameplayProbe",
                    "version: '1.0.0'",
                    "main: premiumgameplay.PremiumGameplayProbe",
                    "api-version: '1.21'",
                    "depend: [ItemGuard]",
                    "commands:",
                    "  premiumgameplay:",
                    "    description: Fixture-only gameplay observer",
                    "",
                ]
            ).encode("utf-8"),
        )
    return jar


def stage_fixture(probe_jar: Path) -> tuple[Path, dict[str, object]]:
    for path, label in (
        (PAPER_JAR, "Paper jar"),
        (PREMIUM_JAR, "Premium jar"),
        (JAVA, "JDK 21 java"),
        (JAVAC, "JDK 21 javac"),
        (CLIENT_SCRIPT, "gameplay client script"),
    ):
        if not path.is_file():
            raise RuntimeError(f"{label} missing: {path}")
    fixture = EVIDENCE_BASE / f"itemguard-premium-gameplay-{uuid.uuid4().hex[:12]}"
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / "plugins").mkdir()
    shutil.copy2(PAPER_JAR, fixture / "paper.jar")
    shutil.copy2(PREMIUM_JAR, fixture / "plugins/ItemGuard-Premium.jar")
    shutil.copy2(probe_jar, fixture / "plugins/PremiumGameplayProbe.jar")
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
            "max-players=4",
            "view-distance=4",
            "simulation-distance=4",
            "spawn-protection=0",
            "gamemode=survival",
            "difficulty=peaceful",
            "level-type=minecraft:flat",
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}',
            "generate-structures=false",
            "allow-flight=true",
            "motd=ItemGuard Premium gameplay journey fixture",
            "",
        ]
    )
    (fixture / "server.properties").write_text(properties, encoding="utf-8")

    # Both fixture clients are real operator-level players: the journey issues /give, /tp and /clear
    # exactly as an admin would, and the GUI surfaces check permissions rather than role state.
    ops = [
        {
            "uuid": offline_uuid(name),
            "name": name,
            "level": 4,
            "bypassesPlayerLimit": False,
        }
        for name in ("PremiumStaff", "PremiumMember")
    ]
    save(fixture / "ops.json", ops)

    data_folder = fixture / "plugins/ItemGuard"
    data_folder.mkdir()
    config = SINGLE.configure_premium(SINGLE.CONFIG_SOURCE.read_text(encoding="utf-8"))
    if f'server-id: "{SERVER_ID}"' not in config:
        raise RuntimeError("staged Premium config did not bind the fixture server identity")
    (data_folder / "config.yml").write_text(config, encoding="utf-8")
    (fixture / "plugins/PremiumGameplayProbe").mkdir()

    manifest = {
        "root": str(fixture),
        "server_port": port,
        "server_id": SERVER_ID,
        "mysql_host": MYSQL_HOST,
        "mysql_port": MYSQL_PORT,
        "mysql_database": MYSQL_DATABASE,
        "paper_sha256": sha256(fixture / "paper.jar"),
        "premium_jar_sha256": sha256(fixture / "plugins/ItemGuard-Premium.jar"),
        "probe_jar_sha256": sha256(fixture / "plugins/PremiumGameplayProbe.jar"),
        "probe_source_sha256": sha256(PROBE_SOURCE),
        "client_script_sha256": sha256(CLIENT_SCRIPT),
        "config_sha256": sha256(data_folder / "config.yml"),
        "ops_sha256": sha256(fixture / "ops.json"),
        "runner_sha256": sha256(Path(__file__).resolve()),
        "node": NODE,
        "java": str(JAVA),
        "created_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    save(fixture / "stage.json", manifest)
    return fixture, manifest


class ClientProcess:
    """The real Mineflayer driver. Its own log is the raw evidence for this half of the journey."""

    def __init__(self, fixture: Path, generation: int, mode: str, code: str | None, port: int):
        self.fixture = fixture
        self.generation = generation
        self.mode = mode
        self.lines: list[str] = []
        self.result: dict[str, object] | None = None
        self.failure: str | None = None
        self.events: list[dict[str, object]] = []
        self.log_path = fixture / f"client-{generation}.log"
        self.log = self.log_path.open("w", encoding="utf-8")
        args = [NODE, str(CLIENT_SCRIPT), str(port), mode]
        if code:
            args.append(code)
        self.process = subprocess.Popen(
            args,
            cwd=fixture,
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
                stripped = line.strip()
                if not stripped.startswith("{"):
                    if "BOT_ERROR" in stripped:
                        self.failure = self.failure or stripped
                    continue
                try:
                    payload = json.loads(stripped)
                except json.JSONDecodeError:
                    continue
                self.events.append(payload)
                event = payload.get("event")
                if event == "BOT_ERROR":
                    self.failure = self.failure or json.dumps(payload, ensure_ascii=False)
                elif event == "CLIENT_FAIL":
                    self.failure = self.failure or json.dumps(payload, ensure_ascii=False)
                elif event in ("CLIENT_RESULT", "RESTART_RESULT"):
                    self.result = payload
        finally:
            self.process.stdout.close()
            self.log.close()

    def wait_result(self, timeout: float = 420.0) -> dict[str, object]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.result is not None:
                return self.result
            if self.failure is not None:
                raise RuntimeError(f"gameplay client reported failure: {self.failure}")
            if self.process.poll() is not None and self.result is None:
                raise RuntimeError(
                    f"gameplay client exited with {self.process.returncode} without a result"
                )
            time.sleep(0.2)
        raise TimeoutError(f"gameplay client mode={self.mode} produced no result in {timeout}s")

    def marker_count(self, marker: str) -> int:
        return sum(marker in line for line in self.lines)

    def stop(self) -> dict[str, object]:
        forced = False
        if self.process.poll() is None:
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=15)
                forced = True
        self.reader.join(timeout=15)
        return {
            "pid": self.process.pid,
            "exit": self.process.returncode,
            "forced": forced,
            "log_closed": not self.reader.is_alive(),
            "mode": self.mode,
        }


EVIDENCE_EVENTS = {
    "identity",
    "item-equipped",
    "commands",
    "legacy-gui",
    "real-drop",
    "real-pickup",
    "real-chest-put",
    "real-chest-take",
    "member-history-gui",
    "catalog-inspected",
    "catalog-gui",
    "CLIENT_RESULT",
    "RESTART_RESULT",
}


def client_evidence(client: "ClientProcess") -> list[dict[str, object]]:
    return [event for event in client.events if event.get("event") in EVIDENCE_EVENTS]


def probe_history(paper, code: str) -> list[str]:
    marker = "PREMIUM_GAMEPLAY_HISTORY_READY code=" + code
    before = paper.count(marker)
    paper.send(f"premiumgameplay history {code}")
    paper.wait_for_new(marker, before, 30)
    line = next(line for line in reversed(paper.lines) if marker in line)
    actions = line.split("actions=", 1)[1].strip() if "actions=" in line else ""
    return actions.split(",") if actions else []


def mysql_postcondition(code: str) -> dict[str, object]:
    # One labelled result set: the mysql client concatenates every result set without a separator,
    # so each row carries its own key.
    sql = (
        f"SELECT 'tracked', COUNT(*) FROM tracked_items WHERE code='{code}'"
        f" UNION ALL SELECT 'history', COUNT(*) FROM item_history WHERE code='{code}'"
        f" UNION ALL SELECT 'snapshots', COUNT(*) FROM item_snapshots WHERE code='{code}'"
        f" UNION ALL SELECT CONCAT('server:', server_id), COUNT(*) FROM item_history"
        f"   WHERE code='{code}' GROUP BY server_id"
        f" UNION ALL SELECT CONCAT('member:', action), COUNT(*) FROM item_history"
        f"   WHERE code='{code}' AND player_name='PremiumMember' GROUP BY action"
        f" UNION ALL SELECT CONCAT('staff:', action), COUNT(*) FROM item_history"
        f"   WHERE code='{code}' AND player_name='PremiumStaff' GROUP BY action;"
    )
    counters: dict[str, int] = {}
    keys: list[str] = []
    for row in mysql_query(sql):
        if not row:
            continue
        key, _, value = row.partition("\t")
        keys.append(key)
        counters[key] = counters.get(key, 0) + int(value)
    tracked = counters.get("tracked", 0)
    history = counters.get("history", 0)
    snapshots = counters.get("snapshots", 0)
    servers = sorted(key.split(":", 1)[1] for key in keys if key.startswith("server:"))
    member_actions = sorted(key.split(":", 1)[1] for key in keys if key.startswith("member:"))
    staff_actions = sorted(key.split(":", 1)[1] for key in keys if key.startswith("staff:"))
    if tracked != 1 or snapshots != 1:
        raise RuntimeError(f"unexpected MySQL identity state: tracked={tracked} snapshots={snapshots}")
    if servers != [SERVER_ID]:
        raise RuntimeError(f"history rows are not stamped with {SERVER_ID}: {servers!r}")
    if history < 5:
        raise RuntimeError(f"expected at least five history rows for {code}, saw {history}")
    if "PICKUP" not in member_actions:
        raise RuntimeError(f"member custody actions missing PICKUP: {member_actions!r}")
    if "SPAWN" not in staff_actions or "DROP" not in staff_actions:
        raise RuntimeError(f"staff actions missing SPAWN/DROP: {staff_actions!r}")
    return {
        "tracked_items": tracked,
        "item_history": history,
        "item_snapshots": snapshots,
        "servers": servers,
        "member_actions": member_actions,
        "staff_actions": staff_actions,
    }


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
    fixture, manifest = stage_fixture(probe_jar)
    receipt: dict[str, object] = {
        "status": "FAILED",
        "fixture": str(fixture),
        "stage": manifest,
        "generations": [],
        "cleanup": [],
        "started_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    mysql_started = False
    paper = None
    client = None
    code: str | None = None
    try:
        status = run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing an unowned shared server")
        started = run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()

        # -- generation 1: full real-player journey ------------------------------------------------
        paper = SINGLE.PaperProcess(fixture, 1, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for("PREMIUM_GAMEPLAY_PROBE READY", 90)
        paper.wait_for(f"MySQL database initialized for server-id {SERVER_ID}", 60)
        client = ClientProcess(fixture, 1, "full", None, int(manifest["server_port"]))
        result = client.wait_result()
        code = str(result.get("code") or "")
        if result.get("status") != "PASS" or not code:
            raise RuntimeError(f"generation 1 client result was not a pass: {result!r}")
        # Let the last queued async history writes commit before the SQL postcondition and the
        # server-side history read.
        time.sleep(2.0)
        actions = probe_history(paper, code)
        receipt["generations"].append(
            {
                "generation": 1,
                "mode": "full",
                "client_result": result,
                "client_events": client_evidence(client),
                "server_history_actions": actions,
                "mysql": mysql_postcondition(code),
                "client_log": str(client.log_path),
            }
        )
        for required in ("SPAWN", "DROP", "PICKUP"):
            if required not in actions:
                raise RuntimeError(f"server-side history is missing {required}: {actions!r}")
        by_event = {event.get("event"): event for event in client.events}
        for required in ("legacy-gui", "member-history-gui", "catalog-gui", "commands"):
            if required not in by_event:
                raise RuntimeError(f"client never reported the {required} evidence row")
        catalog = by_event["catalog-gui"]
        receipt["generations"][-1]["catalog_history_retries"] = catalog.get("catalog_history_retries")
        if not catalog.get("catalog_history_retries") and catalog.get("catalog_history_retries") != 0:
            raise RuntimeError(f"catalog evidence row carries no retry count: {catalog!r}")
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-1", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"gameplay client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-1", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"Paper cleanup was not clean: {cleanup}")

        # -- generation 2: clean restart reads the same identity back ------------------------------
        paper = SINGLE.PaperProcess(fixture, 2, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for("PREMIUM_GAMEPLAY_PROBE READY", 90)
        client = ClientProcess(fixture, 2, "restart", code, int(manifest["server_port"]))
        restart = client.wait_result(timeout=300)
        if restart.get("status") != "PASS":
            raise RuntimeError(f"generation 2 restart result was not a pass: {restart!r}")
        time.sleep(2.0)
        post = mysql_postcondition(code)
        receipt["generations"].append(
            {
                "generation": 2,
                "mode": "restart",
                "client_result": restart,
                "client_events": client_evidence(client),
                "mysql": post,
                "client_log": str(client.log_path),
            }
        )
        for required in ("PICKUP",):
            if required not in post["member_actions"]:
                raise RuntimeError(f"restart lost the member custody row: {post!r}")
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-2", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"restart client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-2", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"restart Paper cleanup was not clean: {cleanup}")
        receipt["identity_code"] = code
        receipt["status"] = "PASS"
    except BaseException as failure:
        receipt["error"] = repr(failure)
        raise
    finally:
        if client is not None:
            receipt["cleanup"].append({"child": f"client-{client.generation}", **client.stop()})
        if paper is not None:
            receipt["cleanup"].append({"child": f"paper-{paper.generation}", **paper.stop()})
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
        save(fixture / "premium-gameplay-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
