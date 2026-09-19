"""Controlled Premium reclaim hand-over gate on Paper + MySQL.

This is the runtime gate the issuance design doc names as owed: it is the only one that actually hands
an item back. Staged on top of the gameplay fixture (same staging, same real-client driver) with two
config keys switched, because without them the fixture would only ever measure the refusal path — a
green gate that never issues anything.

Scenario, all with a real Mineflayer protocol client:

    generation 1  /give + equip + /ig check -> identity and snapshot exist
                  /matdo check              -> the item is listed as eligible
                  /matdo sos <code>         -> REFUSED while the item is held (absence not proven)
                  /clear <player>           -> the item is destroyed; the snapshot is the only copy
                  /matdo sos <code>         -> ISSUED, and the stack is in the client's inventory
                  /matdo sos <code> again   -> refused by the claim lock; the sword count must not grow
    generation 2  restart, /matdo sos       -> still refused; the count still must not grow

MySQL postconditions are read from the fixture's own database, and every owned process is stopped in
`finally` with the receipt written even on failure.
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GAMEPLAY_RUNNER = ROOT / "tools/premium-runtime/paper_gameplay_smoke.py"
CLIENT_SCRIPT = ROOT / "tools/premium-runtime/premium_gameplay_client.cjs"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")

RECLAIM_CONFIG_SWITCHES = (
    ("  issuance-enabled: false", "  issuance-enabled: true"),
    ("  external-absence-mode: STRICT", "  external-absence-mode: INSTALLED_ONLY"),
)

EVIDENCE_EVENTS = {
    "identity",
    "reclaim-refused-full",
    "reclaim-full-retry",
    "giveoldid-ready",
    "giveoldid-issued",
    "item-equipped",
    "reclaim-item",
    "reclaim-refused-present",
    "reclaim-issued",
    "reclaim-lock",
    "reclaim-restart",
    "CLIENT_RESULT",
}


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


def stage_fixture():
    probe_jar = GP.stage_probe_jar()
    fixture, manifest = GP.stage_fixture(probe_jar)

    config_path = fixture / "plugins/ItemGuard/config.yml"
    config = config_path.read_text(encoding="utf-8")
    for old, new in RECLAIM_CONFIG_SWITCHES:
        if old not in config:
            raise RuntimeError(f"staged config does not carry the key this gate must switch: {old!r}")
        config = config.replace(old, new, 1)
    config_path.write_text(config, encoding="utf-8")

    manifest["config_sha256"] = GP.sha256(config_path)
    manifest["reclaim_config"] = {
        "issuance-enabled": True,
        "external-absence-mode": "INSTALLED_ONLY",
    }
    manifest["client_script_sha256"] = GP.sha256(CLIENT_SCRIPT)
    manifest["runner_sha256"] = GP.sha256(Path(__file__).resolve())
    GP.save(fixture / "stage.json", manifest)
    return fixture, manifest


def claim_rows(code: str) -> list[str]:
    return GP.mysql_query(
        "SELECT state, IFNULL(detail, '') FROM reclaim_claims "
        f"WHERE code = '{code}' ORDER BY updated_at;"
    )


def scalar(sql: str) -> str:
    rows = GP.mysql_query(sql)
    return rows[0] if rows else ""


def client_evidence(client) -> list[dict[str, object]]:
    return [event for event in client.events if event.get("event") in EVIDENCE_EVENTS]


def verify_claims(code: str, require_committed: int = 1, require_denied: bool = True) -> dict[str, object]:
    rows = claim_rows(code)
    committed = [row for row in rows if row.startswith("COMMITTED\t")]
    denied = [row for row in rows if row.startswith("DENIED\t")]
    if len(committed) != require_committed:
        raise RuntimeError(f"expected {require_committed} committed claim(s), got {rows!r}")
    if "issued to PremiumStaff" not in committed[0]:
        raise RuntimeError(f"the committed claim does not name the actor: {committed[0]!r}")
    if require_denied and not denied:
        raise RuntimeError(
            "the refusal this generation expects left no denied claim, so the negative case is "
            f"unproven: {rows!r}"
        )
    return {
        "rows": rows,
        "committed": len(committed),
        "denied": len(denied),
        "history_reclaim_rows": scalar(
            "SELECT COUNT(*) FROM item_history "
            f"WHERE code = '{code}' AND action = 'RECLAIM_ISSUED';"
        ),
        "snapshot_rows": scalar(f"SELECT COUNT(*) FROM item_snapshots WHERE code = '{code}';"),
    }


def require_free_space(minimum_gb: float = 4.0) -> None:
    """Refuse to stage when the drive cannot hold the fixture.

    Learned the hard way on 2026-09-19: a Paper fixture root is a few hundred MB, and when E: filled up
    three gates in a row failed with `OSError: [WinError 112] There is not enough space on the disk`
    *after* twenty minutes of work each. The failure was environmental, but it looked like a gate
    failure; a pre-flight check turns that into one sentence before anything is staged.
    `tools/premium-runtime/trim_fixture_roots.py` is the remedy.
    """
    free_gb = shutil.disk_usage(EVIDENCE_BASE).free / 1e9
    if free_gb < minimum_gb:
        raise RuntimeError(
            f"only {free_gb:.2f} GB free on the fixture drive, need {minimum_gb:.1f} GB; "
            "run tools/premium-runtime/trim_fixture_roots.py first"
        )


def wait_for_client_event(client, event: str, timeout: float) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for payload in client.events:
            if payload.get("event") == event:
                return payload
        if client.failure is not None:
            raise RuntimeError(f"the client reported failure: {client.failure}")
        time.sleep(0.2)
    raise TimeoutError(f"the client never reported {event}")


def run_generation(fixture, manifest, receipt, generation, mode, code, timeout, required_events, label):
    """One Paper generation plus one real-client run, with the same cleanup discipline as the others."""
    paper = SINGLE.PaperProcess(fixture, generation, int(manifest["server_port"]))
    paper.wait_for("Done (", 180)
    paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
    client = GP.ClientProcess(fixture, generation, mode, code, int(manifest["server_port"]))
    try:
        result = client.wait_result(timeout=timeout)
        if result.get("status") != "PASS":
            raise RuntimeError(f"generation {generation} ({label}) did not pass: {result!r}")
        by_event = {event.get("event"): event for event in client.events}
        for required in required_events:
            if required not in by_event:
                raise RuntimeError(f"generation {generation} never reported {required}")
        time.sleep(2.0)
        receipt["generations"].append(
            {
                "generation": generation,
                "mode": mode,
                "client_result": result,
                "client_events": client_evidence(client),
                "client_log": str(client.log_path),
                "paper_log": str(paper.log_path),
            }
        )
        return str(result.get("code") or "")
    finally:
        cleanup = client.stop()
        receipt["cleanup"].append({"child": f"client-{generation}", **cleanup})
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"generation {generation} client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": f"paper-{generation}", **cleanup})
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"generation {generation} Paper cleanup was not clean: {cleanup}")


def main() -> int:
    require_free_space()
    fixture, manifest = stage_fixture()
    receipt: dict[str, object] = {
        "status": "FAILED",
        "gate": "premium-reclaim-handover",
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

        # -- generation 1: the hand-over itself ----------------------------------------------------
        paper = SINGLE.PaperProcess(fixture, 1, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for("PREMIUM_GAMEPLAY_PROBE READY", 90)
        paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
        client = GP.ClientProcess(fixture, 1, "reclaim", None, int(manifest["server_port"]))
        result = client.wait_result(timeout=300)
        if result.get("status") != "PASS":
            raise RuntimeError(f"generation 1 reclaim client did not pass: {result!r}")
        code = str(result.get("code") or "")
        if not code:
            raise RuntimeError("generation 1 did not report the identity code")
        time.sleep(2.0)
        mysql_after_issue = verify_claims(code)
        receipt["generations"].append(
            {
                "generation": 1,
                "mode": "reclaim",
                "client_result": result,
                "client_events": client_evidence(client),
                "mysql": mysql_after_issue,
                "client_log": str(client.log_path),
                "paper_log": str(paper.log_path),
            }
        )
        by_event = {event.get("event"): event for event in client.events}
        for required in ("reclaim-item", "reclaim-refused-present", "reclaim-issued", "reclaim-lock"):
            if required not in by_event:
                raise RuntimeError(f"the client never reported the {required} evidence row")
        if int(mysql_after_issue["history_reclaim_rows"]) != 1:
            raise RuntimeError(
                f"expected one RECLAIM_ISSUED history row, got {mysql_after_issue['history_reclaim_rows']}"
            )
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-1", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"reclaim client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-1", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"Paper cleanup was not clean: {cleanup}")

        # -- generation 2: the committed claim survives a restart -----------------------------------
        paper = SINGLE.PaperProcess(fixture, 2, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
        client = GP.ClientProcess(fixture, 2, "reclaim-restart", code, int(manifest["server_port"]))
        restart = client.wait_result(timeout=300)
        if restart.get("status") != "PASS":
            raise RuntimeError(f"generation 2 restart client did not pass: {restart!r}")
        time.sleep(2.0)
        mysql_after_restart = verify_claims(code)
        receipt["generations"].append(
            {
                "generation": 2,
                "mode": "reclaim-restart",
                "client_result": restart,
                "client_events": client_evidence(client),
                "mysql": mysql_after_restart,
                "client_log": str(client.log_path),
                "paper_log": str(paper.log_path),
            }
        )
        if mysql_after_restart["committed"] != 1:
            raise RuntimeError(f"the restart changed the claim set: {mysql_after_restart!r}")
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-2", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"restart client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-2", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"Paper restart cleanup was not clean: {cleanup}")

        # -- generation 3: the inventory-full retry (the failure path that protects the item) ---------
        code_full = run_generation(
            fixture, manifest, receipt, 3, "reclaim-full", None, 300,
            required_events=("reclaim-refused-full", "reclaim-full-retry"),
            label="full-inventory retry",
        )
        full_claims = verify_claims(code_full, require_committed=1)
        denied_rows = [row for row in full_claims["rows"] if row.startswith("DENIED")]
        if not any("inventory full" in row for row in denied_rows):
            raise RuntimeError(
                f"the full-inventory refusal left no row naming the reason: {full_claims['rows']!r}"
            )
        receipt["generations"][-1]["mysql"] = full_claims
        receipt["generations"][-1]["reclaim_code"] = code_full

        # -- generation 4: /finditem giveoldid, the admin path --------------------------------------
        paper = SINGLE.PaperProcess(fixture, 4, int(manifest["server_port"]))
        paper.wait_for("Done (", 180)
        paper.wait_for(f"MySQL database initialized for server-id {GP.SERVER_ID}", 60)
        client = GP.ClientProcess(fixture, 4, "giveoldid", None, int(manifest["server_port"]))
        ready = wait_for_client_event(client, "giveoldid-ready", 240.0)
        code_admin = str(ready.get("code") or "")
        if not code_admin:
            raise RuntimeError("the giveoldid client never reported an identity")
        paper.send(f"finditem giveoldid {code_admin}")
        issued = wait_for_client_event(client, "giveoldid-issued", 180.0)
        time.sleep(2.0)
        # The admin path destroys the item before issuing, so there is no while-held refusal to
        # leave a denied claim; requiring one here failed the run after it had already issued.
        admin_claims = verify_claims(code_admin, require_committed=1, require_denied=False)
        receipt["generations"].append(
            {
                "generation": 4,
                "mode": "giveoldid",
                "client_events": client_evidence(client),
                "admin_command": f"finditem giveoldid {code_admin}",
                "client_result": issued,
                "mysql": admin_claims,
                "client_log": str(client.log_path),
                "paper_log": str(paper.log_path),
            }
        )
        result = client.wait_result(timeout=120)
        if result.get("status") != "PASS":
            raise RuntimeError(f"the giveoldid client did not pass: {result!r}")
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-4", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"giveoldid client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-4", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["port_released"]:
            raise RuntimeError(f"giveoldid Paper cleanup was not clean: {cleanup}")

        receipt["status"] = "PASS"
        receipt["code"] = code
        receipt["reclaim_codes"] = {"primary": code, "full_inventory": code_full, "giveoldid": code_admin}
        return 0
    except Exception as failure:  # noqa: BLE001 - the receipt must carry whatever happened
        receipt["failure"] = f"{type(failure).__name__}: {failure}"
        return 1
    finally:
        for name, process_attr in (("client", "client"), ("paper", "paper")):
            owned = locals().get(process_attr)
            if owned is not None:
                try:
                    receipt["cleanup"].append({"child": f"leaked-{name}", **owned.stop()})
                except Exception as cleanup_failure:  # noqa: BLE001
                    receipt["cleanup"].append(
                        {"child": f"leaked-{name}", "error": str(cleanup_failure)}
                    )
        if mysql_started:
            stopped = GP.run_mysql("stop")
            receipt["mysql_stopped"] = {
                "exit": stopped.returncode,
                "stdout": stopped.stdout.strip().splitlines()[-1:] if stopped.stdout else [],
            }
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        out_dir = EVIDENCE_BASE / fixture.name
        out_dir.mkdir(parents=True, exist_ok=True)
        GP.save(out_dir / "premium-reclaim-receipt.json", receipt)
        print(json.dumps(receipt, indent=2, ensure_ascii=False))
        print(f"receipt written to {out_dir / 'premium-reclaim-receipt.json'}")


if __name__ == "__main__":
    sys.exit(main())
