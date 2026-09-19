"""Controlled duplicate-detection gate on Paper + MySQL.

The one Premium feature whose end-to-end path had never been run: an identity observed in two distinct
locations inside one scan epoch, producing one finding, one staff alert and one Discord payload — with
nothing removed, because the shipped action is NOTIFY.

The fixture stages its own probe (`PremiumDupeProbe.java`) for the two things no client can do: put a
second stack carrying the same code and item UUID into another player's inventory, and force a scan
epoch on demand. Everything the gate then asserts comes from the product: the `duplicate_findings` row
in MySQL, the alert the staff client actually receives, and the HTTP body the plugin posts to a
webhook URL this runner serves on localhost.

Generations:

    1  duplicate + epoch      -> one CONFIRMED finding, one alert, one payload, both stacks intact
       a second epoch         -> the consecutive-epoch rule refuses to re-report, so still one finding
    2  clean restart          -> the finding survives; an epoch after the restart produces no storm

Every owned process is stopped in `finally` and the receipt is written even when the run fails.
"""
from __future__ import annotations

import datetime as dt
import http.server
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
GAMEPLAY_RUNNER = ROOT / "tools/premium-runtime/paper_gameplay_smoke.py"
PROBE_SOURCE = ROOT / "tools/premium-runtime/PremiumDupeProbe.java"
CLIENT_SCRIPT = ROOT / "tools/premium-runtime/premium_gameplay_client.cjs"
COMPILE_CLASSPATH_FILE = Path("C:/Users/thanh/AppData/Local/Temp/itemguard-premium-classpath.txt")
PREMIUM_JAR = ROOT / "target/ItemGuard-1.0.0-shaded.jar"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
JAVAC = Path("C:/Program Files/Java/jdk-21/bin/javac.exe")

CONFIG_SWITCHES = (
    ("  enabled: false\n\n  # MVP only permits NOTIFY", "  enabled: true\n\n  # MVP only permits NOTIFY"),
    ("  detection-cooldown-ms: 300000", "  detection-cooldown-ms: 9000"),
    ("  sweep:\n    enabled: true", "  sweep:\n    enabled: false"),
    ("  inventory-scan-interval: 600  # 30 seconds at 20 TPS",
     "  inventory-scan-interval: 10  # fixture: fast epochs"),
    ("discord:\n  # Enable Discord webhook notifications\n  enabled: false",
     "discord:\n  # Enable Discord webhook notifications\n  enabled: true"),
)

EVIDENCE_EVENTS = {"identity", "dupe-ready", "dupe-alert", "CLIENT_RESULT"}


def load_gameplay_runner():
    spec = importlib.util.spec_from_file_location("premium_gameplay_runner", GAMEPLAY_RUNNER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load gameplay runner: {GAMEPLAY_RUNNER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GP = load_gameplay_runner()
SINGLE = GP.SINGLE
MYSQL = GP.MYSQL


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def require_free_space(minimum_gb: float = 4.0) -> None:
    free_gb = shutil.disk_usage(EVIDENCE_BASE).free / 1e9
    if free_gb < minimum_gb:
        raise RuntimeError(
            f"only {free_gb:.2f} GB free on the fixture drive, need {minimum_gb:.1f} GB; "
            "run tools/premium-runtime/trim_fixture_roots.py first"
        )


class PayloadSink:
    """A local HTTP server standing in for Discord: it records exactly what the plugin posts."""

    def __init__(self) -> None:
        self.port = free_port()
        self.bodies: list[str] = []
        sink = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 - http.server's own naming
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode("utf-8", errors="replace")
                sink.bodies.append(body)
                self.send_response(204)
                self.end_headers()

            def log_message(self, *args) -> None:  # keep the runner's log readable
                return

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/itemguard"

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def stage_probe_jar() -> Path:
    if not PROBE_SOURCE.is_file():
        raise RuntimeError(f"probe source missing: {PROBE_SOURCE}")
    if not COMPILE_CLASSPATH_FILE.is_file():
        raise RuntimeError(f"Maven compile classpath missing: {COMPILE_CLASSPATH_FILE}")
    build = ROOT / "tools/premium-runtime/.dupe-probe-build"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    classpath = COMPILE_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    classpath = __import__("os").pathsep.join((classpath, str(PREMIUM_JAR)))
    completed = subprocess.run(
        [str(JAVAC), "--release", "21", "-cp", classpath, "-d", str(build), str(PROBE_SOURCE)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"dupe probe javac failed:\n{completed.stdout}\n{completed.stderr}")
    jar = ROOT / "tools/premium-runtime/.dupe-probe.jar"
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
            write_deterministic(archive, class_file.relative_to(build).as_posix(),
                                class_file.read_bytes())
        write_deterministic(
            archive,
            "plugin.yml",
            "\n".join(
                [
                    "name: PremiumDupeProbe",
                    "version: '1.0.0'",
                    "main: premiumdupe.PremiumDupeProbe",
                    "api-version: '1.21'",
                    "depend: [ItemGuard]",
                    "commands:",
                    "  premiumdupe:",
                    "    description: Fixture-only duplicate gate driver",
                    "",
                ]
            ).encode("utf-8"),
        )
    return jar


def stage_fixture(sink: PayloadSink):
    probe_jar = stage_probe_jar()
    fixture, manifest = GP.stage_fixture(probe_jar)

    config_path = fixture / "plugins/ItemGuard/config.yml"
    config = config_path.read_text(encoding="utf-8")
    for old, new in CONFIG_SWITCHES:
        if old not in config:
            raise RuntimeError(f"staged config does not carry the key this gate must switch: {old!r}")
        config = config.replace(old, new, 1)
    if 'webhook-url: ""' not in config:
        raise RuntimeError("staged config has no webhook-url to point at the sink")
    config = config.replace('webhook-url: ""', f'webhook-url: "{sink.url()}"', 1)
    config_path.write_text(config, encoding="utf-8")

    manifest["config_sha256"] = GP.sha256(config_path)
    manifest["probe_jar_sha256"] = GP.sha256(probe_jar)
    manifest["probe_source_sha256"] = GP.sha256(PROBE_SOURCE)
    manifest["probe_source"] = str(PROBE_SOURCE)
    manifest["client_script_sha256"] = GP.sha256(CLIENT_SCRIPT)
    manifest["runner_sha256"] = GP.sha256(Path(__file__).resolve())
    manifest["discord_sink"] = sink.url()
    manifest["dupe_config"] = {
        "anti-dupe.enabled": True,
        "anti-dupe.detection-cooldown-ms": 9000,
        "anti-dupe.sweep.enabled": False,
        "performance.inventory-scan-interval": 10,
        "discord.enabled": True,
    }
    GP.save(fixture / "stage.json", manifest)
    return fixture, manifest


def finding_rows(code: str) -> list[str]:
    return GP.mysql_query(
        "SELECT status, distinct_locations, action FROM duplicate_findings "
        f"WHERE code = '{code}' ORDER BY scan_epoch;"
    )


def history_rows(code: str) -> str:
    rows = GP.mysql_query(f"SELECT COUNT(*) FROM item_history WHERE code = '{code}';")
    return rows[0] if rows else "?"


def client_evidence(client) -> list[dict[str, object]]:
    return [event for event in client.events if event.get("event") in EVIDENCE_EVENTS]


def wait_for_finding(code: str, minimum: int, timeout: float) -> list[str]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        rows = finding_rows(code)
        if len(rows) >= minimum:
            return rows
        time.sleep(0.5)
    return finding_rows(code)


def main() -> int:
    require_free_space()
    # The dupe client must outlive the post-detection assertions: its inventory is one of the two
    # locations the finding is about, so a client that quits early makes the harness read "absent"
    # for both players and blame the plugin.
    os.environ["ITEMGUARD_BOT_HOLD_MS"] = "35000"

    sink = PayloadSink()
    fixture, manifest = stage_fixture(sink)
    receipt: dict[str, object] = {
        "status": "FAILED",
        "gate": "premium-duplicate-detection",
        "started_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "fixture": str(fixture),
        "manifest": manifest,
        "generations": [],
        "cleanup": [],
    }
    mysql_started = False
    paper = None
    client = None
    code: str | None = None
    try:
        status = GP.run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing an unowned shared server")
        started = GP.run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = GP.run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()

        # -- generation 1: the duplicate, the finding, the two alerts ------------------------------
        paper = SINGLE.PaperProcess(fixture, 1, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for("PREMIUM_DUPE_PROBE READY", 90)
        paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
        client = GP.ClientProcess(fixture, 1, "dupe", None, int(manifest["server_port"]))
        ready = wait_for_event(client, "dupe-ready", 240.0)
        code = str(ready.get("code") or "")
        if not code:
            raise RuntimeError(f"the client never reported an identity: {ready!r}")

        stacks_before = probe_marker(paper, "premiumdupe stacks", "PREMIUM_DUPE_STACKS")

        cloned = probe_marker(paper, "premiumdupe duplicate", "PREMIUM_DUPE_CLONED")

        findings_first = wait_for_finding(code, 1, 60.0)
        if len(findings_first) != 1:
            raise RuntimeError(f"expected exactly one finding after the duplicate: {findings_first!r}")
        if "CONFIRMED" not in findings_first[0]:
            raise RuntimeError(f"the finding is not confirmed: {findings_first[0]!r}")

        # Both stacks must still be there once the finding exists: the shipped action is NOTIFY, so
        # detection must not have removed anything.
        stacks_after = probe_marker(paper, "premiumdupe stacks", "PREMIUM_DUPE_STACKS")
        if "same=true" not in stacks_after:
            raise RuntimeError(
                f"detection changed the stacks, but the shipped action is NOTIFY: {stacks_after!r}"
            )

        alert = wait_for_event(client, "dupe-alert", 120.0)
        payloads = list(sink.bodies)
        if not payloads:
            raise RuntimeError("the configured webhook never received a payload")

        # A second epoch, while both players are still connected and still hold a copy, must not
        # re-report the same identity.
        probe_marker(paper, "premiumdupe epoch", "PREMIUM_DUPE_EPOCH_DONE")
        time.sleep(4.0)
        findings_second = finding_rows(code)
        if len(findings_second) != len(findings_first):
            raise RuntimeError(
                "the anti-spam rule did not hold: a second consecutive epoch re-reported the identity "
                f"{findings_second!r} (was {findings_first!r})"
            )
        stacks_still = probe_marker(paper, "premiumdupe stacks", "PREMIUM_DUPE_STACKS")
        if "same=true" not in stacks_still:
            raise RuntimeError(f"the stacks did not survive the second epoch: {stacks_still!r}")

        receipt["generations"].append(
            {
                "generation": 1,
                "mode": "dupe",
                "client_events": client_evidence(client),
                "probe": {
                    "stacks_before": stacks_before,
                    "cloned": cloned,
                    "stacks_after_detection": stacks_after,
                    "stacks_after_second_epoch": stacks_still,
                },
                "findings_after_duplicate": findings_first,
                "findings_after_second_epoch": findings_second,
                "staff_alert": alert,
                "webhook_payloads": payloads,
                "webhook_payload_mentions_code": [code in body for body in payloads],
                "history_rows": history_rows(code),
                "paper_log": str(paper.log_path),
                "client_log": str(client.log_path),
            }
        )
        if not any(code in body for body in payloads):
            raise RuntimeError(f"no webhook payload carries the code {code}: {payloads!r}")

        result = client.wait_result(timeout=120)
        if result.get("status") != "PASS":
            raise RuntimeError(f"the dupe client did not pass: {result!r}")
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-1", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"dupe client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-1", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"Paper cleanup was not clean: {cleanup}")

        # -- generation 2: the finding survives a restart, and nothing storms ----------------------
        paper = SINGLE.PaperProcess(fixture, 2, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for("PREMIUM_DUPE_PROBE READY", 90)
        paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
        after_restart = finding_rows(code)
        if len(after_restart) < len(findings_first):
            raise RuntimeError(f"the findings did not survive the restart: {after_restart!r}")
        probe_marker(paper, "premiumdupe epoch", "PREMIUM_DUPE_EPOCH_DONE")
        # The rate, not the total. The fixture's scan interval is 10 ticks, so an unthrottled plugin
        # writes about twenty rows in the ten seconds below; the cooldown is 9000 ms, so at most two
        # are legitimate. This is the assertion that says "staff are not spammed".
        window_start = len(finding_rows(code))
        time.sleep(10.0)
        window_end = len(finding_rows(code))
        growth = window_end - window_start
        if growth > 2:
            raise RuntimeError(
                f"the throttle is not bounding alerts: {growth} new findings in 10 s with a 9 s "
                f"cooldown and a 0.5 s scan interval (rows now {window_end})"
            )
        findings_after_restart_epoch = finding_rows(code)
        receipt["generations"].append(
            {
                "generation": 2,
                "mode": "restart",
                "findings_after_restart": after_restart,
                "findings_after_restart_epoch": findings_after_restart_epoch,
                "growth_in_10s_window": growth,
                "throttle_margin_note": (
                    "scan interval 10 ticks (0.5 s), detection cooldown 9000 ms: an unthrottled "
                    "plugin would write about twenty rows in this window"
                ),
                "paper_log": str(paper.log_path),
            }
        )
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-2", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"Paper restart cleanup was not clean: {cleanup}")

        receipt["status"] = "PASS"
        receipt["code"] = code
        return 0
    except Exception as failure:  # noqa: BLE001 - the receipt must carry whatever happened
        receipt["failure"] = f"{type(failure).__name__}: {failure}"
        return 1
    finally:
        for name, owned in (("client", client), ("paper", paper)):
            if owned is not None:
                try:
                    receipt["cleanup"].append({"child": f"leaked-{name}", **owned.stop()})
                except Exception as cleanup_failure:  # noqa: BLE001
                    receipt["cleanup"].append({"child": f"leaked-{name}", "error": str(cleanup_failure)})
        if mysql_started:
            stopped = GP.run_mysql("stop")
            receipt["mysql_stopped"] = {"exit": stopped.returncode}
        receipt["webhook_total"] = len(sink.bodies)
        sink.stop()
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        out_dir = EVIDENCE_BASE / fixture.name
        out_dir.mkdir(parents=True, exist_ok=True)
        GP.save(out_dir / "premium-dupe-receipt.json", receipt)
        print(json.dumps(receipt, indent=2, ensure_ascii=False))
        print(f"receipt written to {out_dir / 'premium-dupe-receipt.json'}")


def probe_marker(paper, command: str, marker: str) -> str:
    """Send a probe command and wait for a *new* marker line, then return that line.

    Learned from this gate's first run: `wait_for` is satisfied by an occurrence that already exists,
    so a second read of the same marker returned the line written before the command — the harness
    read "member=none" from before the clone and blamed the plugin for it.
    """
    before = paper.count(marker)
    paper.send(command)
    paper.wait_for_new(marker, before, 60)
    return last_marker(paper, marker)


def last_marker(paper, marker: str) -> str:
    lines = [line.strip() for line in paper.lines if marker in line]
    return lines[-1] if lines else ""


def wait_for_event(client, event: str, timeout: float) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for payload in client.events:
            if payload.get("event") == event:
                return payload
        if client.failure is not None:
            raise RuntimeError(f"the dupe client reported failure: {client.failure}")
        if client.process.poll() is not None and not client.events:
            raise RuntimeError(f"the dupe client exited with {client.process.returncode}")
        time.sleep(0.2)
    raise TimeoutError(f"the dupe client never reported {event}")


if __name__ == "__main__":
    sys.exit(main())
