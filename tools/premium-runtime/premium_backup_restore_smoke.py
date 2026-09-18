"""Controlled Premium MySQL backup/restore + durability gate.

Phase A seeds one real tracked identity through the real player path on the disposable MySQL
fixture. Phase B takes a logical dump (server stopped), restores it into a second schema, points the
same Paper fixture at that restored schema and proves the plugin reads the same identity, history
and counts back. Phase C measures what the plugin does with a *partial* restore (one data table
missing) instead of assuming it refuses. Phase D measures the future-schema-version refusal.

Every phase runs against the frozen shaded jar, records raw logs, and stops every owned process in
finally. Findings are recorded as measured, including any that are uncomfortable.
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
GAMEPLAY_RUNNER = ROOT / "tools/premium-runtime/paper_gameplay_smoke.py"
EVIDENCE_BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
RESTORE_DATABASE = "itemguard_restore"
BROKEN_DATABASE = "itemguard_partial_restore"
TABLES = [
    "tracked_items",
    "item_history",
    "item_observations",
    "duplicate_findings",
    "item_search_requests",
    "item_snapshots",
    "reclaim_claims",
    "tag_publications",
    "plugin_stats",
]


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


G = load_module(GAMEPLAY_RUNNER, "premium_gameplay_runner")
SINGLE = G.SINGLE
MYSQL = G.MYSQL
MYSQL_HOST = MYSQL.BIND
MYSQL_PORT = MYSQL.PORT
SOURCE_DATABASE = MYSQL.TEST_DATABASE
SERVER_ID = G.SERVER_ID


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def mysql_exec(sql: str, database: str | None = None) -> str:
    command = [
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
    ]
    if database:
        command.append(database)
    command += ["-e", sql]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise RuntimeError(f"MySQL statement failed: {result.stderr.strip()}")
    return result.stdout.strip()


def dump_database(database: str, out_path: Path) -> dict[str, object]:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as stream:
        result = subprocess.run(
            [
                str(MYSQL.bin_path("mysqldump")),
                "--protocol=tcp",
                "-h",
                MYSQL_HOST,
                "-P",
                str(MYSQL_PORT),
                "-u",
                "root",
                "--routines",
                "--events",
                "--single-transaction",
                # Deliberately NOT --databases: that form writes CREATE DATABASE/USE for the source
                # schema, so loading the dump into a restore schema would silently write back into
                # the source instead. Without it the dump is a set of table statements.
                database,
            ],
            stdout=stream,
            stderr=subprocess.PIPE,
            timeout=600,
        )
    if result.returncode != 0:
        raise RuntimeError(f"mysqldump failed: {result.stderr.decode('utf-8', 'replace').strip()}")
    text = out_path.read_text(encoding="utf-8", errors="replace")
    if f"USE `{database}`" in text or "CREATE DATABASE" in text:
        raise RuntimeError("dump carries database-selection statements; it could restore into the source")
    return {
        "path": str(out_path),
        "sha256": sha256(out_path),
        "bytes": out_path.stat().st_size,
    }


def grant_fixture_user(database: str) -> None:
    """The plugin connects as the fixture user, which is only granted on the seeded schema.

    A real restore has the same step: the operator has to give the plugin's database user rights on
    the restored schema before the plugin can open it. Recorded rather than hidden.
    """
    mysql_exec(f"GRANT ALL PRIVILEGES ON {database}.* TO '{MYSQL.TEST_USER}'@'%'")
    mysql_exec("FLUSH PRIVILEGES")


def load_dump(path: Path, database: str) -> None:
    mysql_exec(f"DROP DATABASE IF EXISTS {database}")
    mysql_exec(
        f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"
    )
    grant_fixture_user(database)
    with path.open("rb") as stream:
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
                database,
            ],
            stdin=stream,
            capture_output=True,
            text=True,
            timeout=600,
        )
    if result.returncode != 0:
        raise RuntimeError(f"restore failed: {result.stderr.strip()}")


def row_counts(database: str) -> dict[str, object]:
    sql = " UNION ALL ".join(f"SELECT '{table}', COUNT(*) FROM {table}" for table in TABLES)
    counts: dict[str, int] = {}
    missing: list[str] = []
    for row in mysql_exec(sql, database).splitlines():
        if not row:
            continue
        table, _, value = row.partition("\t")
        counts[table] = int(value)
    for table in TABLES:
        if table not in counts:
            missing.append(table)
    schema_version = None
    if "plugin_stats" in counts:
        schema_version = mysql_exec("SELECT schema_version FROM plugin_stats WHERE id=1", database)
    return {"counts": counts, "missing_tables": missing, "schema_version": schema_version}


def table_exists(database: str, table: str) -> bool:
    rows = mysql_exec(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE TABLE_SCHEMA='{database}' AND TABLE_NAME='{table}'"
    )
    return rows.strip() == "1"


def point_server_at(fixture: Path, database: str) -> str:
    """Rewrite the staged config to the given schema. The database under test is the variable."""
    config_path = fixture / "plugins/ItemGuard/config.yml"
    configured = SINGLE.configure_premium(SINGLE.CONFIG_SOURCE.read_text(encoding="utf-8"))
    configured = configured.replace(
        f"/{SOURCE_DATABASE}?",
        f"/{database}?",
        1,
    )
    if f"/{database}?" not in configured:
        raise RuntimeError(f"staged config did not bind database {database}")
    config_path.write_text(configured, encoding="utf-8")
    return sha256(config_path)


def observe(paper, marker: str, timeout: float) -> bool:
    try:
        paper.wait_for(marker, timeout)
        return True
    except (TimeoutError, RuntimeError):
        return False


def wait_started(paper, timeout: float = 180.0) -> bool:
    return observe(paper, "Done (", timeout)


def main() -> int:
    probe_jar = G.stage_probe_jar()
    fixture, manifest = G.stage_fixture(probe_jar)
    port = int(manifest["server_port"])
    receipt: dict[str, object] = {
        "status": "FAILED",
        "fixture": str(fixture),
        "stage": manifest,
        "phases": {},
        "findings": [],
        "cleanup": [],
        "started_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    mysql_started = False
    paper = None
    client = None
    try:
        status = G.run_mysql("status")
        if status.returncode == 0:
            raise RuntimeError("MySQL fixture already running; refusing an unowned shared server")
        started = G.run_mysql("start")
        if started.returncode != 0:
            raise RuntimeError(f"MySQL start failed: {started.stdout}\n{started.stderr}")
        mysql_started = True
        reset = G.run_mysql("reset-database")
        if reset.returncode != 0:
            raise RuntimeError(f"MySQL reset failed: {reset.stdout}\n{reset.stderr}")
        receipt["mysql_reset"] = reset.stdout.strip()

        # -- Phase A: seed a real identity through the real player path ---------------------------
        paper = SINGLE.PaperProcess(fixture, 1, port)
        if not wait_started(paper):
            raise RuntimeError("generation 1 Paper did not reach Done")
        paper.wait_for("PREMIUM_GAMEPLAY_PROBE READY", 90)
        paper.wait_for(f"MySQL database initialized for server-id {SERVER_ID}", 60)
        client = G.ClientProcess(fixture, 1, "seed", None, port)
        seed = client.wait_result(timeout=300)
        code = str(seed.get("code") or "")
        if seed.get("status") != "PASS" or not code:
            raise RuntimeError(f"seed generation did not pass: {seed!r}")
        time.sleep(2.0)
        seeded_actions = G.probe_history(paper, code)
        before = row_counts(SOURCE_DATABASE)
        receipt["phases"]["A_seed"] = {
            "client_result": seed,
            "client_events": G.client_evidence(client),
            "server_history_actions": seeded_actions,
            "source_rows": before,
        }
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-1", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"seed client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-1", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"seed Paper cleanup was not clean: {cleanup}")

        # -- Phase B1: logical dump with the server stopped ---------------------------------------
        dump_path = fixture / "dump" / f"{SOURCE_DATABASE}.sql"
        dump = dump_database(SOURCE_DATABASE, dump_path)
        receipt["phases"]["B1_dump"] = dump
        dump_text = dump_path.read_text(encoding="utf-8", errors="replace")
        for required in ("CREATE TABLE `tracked_items`", "CREATE TABLE `item_history`", code):
            if required not in dump_text:
                raise RuntimeError(f"dump is missing {required!r}")
        receipt["phases"]["B1_dump"]["contains_code"] = True

        # -- Phase B2: restore into a second schema and compare counts ----------------------------
        load_dump(dump_path, RESTORE_DATABASE)
        after = row_counts(RESTORE_DATABASE)
        receipt["phases"]["B2_restore"] = {
            "database": RESTORE_DATABASE,
            "rows": after,
        }
        if after["counts"] != before["counts"]:
            raise RuntimeError(f"restored counts differ: before={before['counts']} after={after['counts']}")
        if after["schema_version"] != before["schema_version"]:
            raise RuntimeError(
                f"restored schema version differs: {after['schema_version']!r} vs {before['schema_version']!r}"
            )

        # -- Phase B3: point Paper at the restored schema and read the identity back --------------
        config_sha = point_server_at(fixture, RESTORE_DATABASE)
        paper = SINGLE.PaperProcess(fixture, 2, port)
        if not wait_started(paper):
            raise RuntimeError("restored-schema Paper did not reach Done")
        enabled = observe(paper, "ItemGuard v1.0.0 is enabled.", 90)
        if not enabled:
            raise RuntimeError(
                "ItemGuard did not enable against the restored schema: "
                + " | ".join(line.strip() for line in paper.lines[-25:])
            )
        paper.wait_for("PREMIUM_GAMEPLAY_PROBE READY", 60)
        client = G.ClientProcess(fixture, 2, "restart", code, port)
        readback = client.wait_result(timeout=300)
        if readback.get("status") != "PASS":
            raise RuntimeError(f"restored-schema read-back did not pass: {readback!r}")
        time.sleep(2.0)
        restored_actions = G.probe_history(paper, code)
        receipt["phases"]["B3_readback"] = {
            "database": RESTORE_DATABASE,
            "config_sha256": config_sha,
            "client_result": readback,
            "client_events": G.client_evidence(client),
            "server_history_actions": restored_actions,
            "rows": row_counts(RESTORE_DATABASE),
        }
        if set(seeded_actions) - set(restored_actions):
            raise RuntimeError(
                f"restored schema lost history actions: seeded={seeded_actions} restored={restored_actions}"
            )
        cleanup = client.stop()
        receipt["cleanup"].append({"child": "client-2", **cleanup})
        client = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"]:
            raise RuntimeError(f"read-back client cleanup was not clean: {cleanup}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-2", **cleanup})
        paper = None
        if cleanup["exit"] != 0 or cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"read-back Paper cleanup was not clean: {cleanup}")

        # -- Phase C: a partial restore is refused, not silently re-created -----------------------
        partial_database = BROKEN_DATABASE
        load_dump(dump_path, partial_database)
        mysql_exec("DROP TABLE item_history", partial_database)
        if table_exists(partial_database, "item_history"):
            raise RuntimeError("partial-restore fixture did not drop item_history")
        point_server_at(fixture, partial_database)
        paper = SINGLE.PaperProcess(fixture, 3, port)
        started_server = wait_started(paper)
        plugin_enabled = observe(paper, "ItemGuard v1.0.0 is enabled.", 45)
        time.sleep(2.0)
        recreated = table_exists(partial_database, "item_history")
        refusal_lines = [
            line.strip()
            for line in paper.lines
            if "Refusing to start" in line
            or "Error occurred while enabling ItemGuard" in line
            or "ItemGuard is disabled" in line
        ]
        receipt["phases"]["C_partial_restore"] = {
            "database": partial_database,
            "server_started": started_server,
            "plugin_enabled": plugin_enabled,
            "item_history_recreated": recreated,
            "refusal_lines": refusal_lines[:8],
            "observed_verdict": "REFUSED_FAIL_CLOSED" if not plugin_enabled else "ENABLED_UNEXPECTEDLY",
        }
        if plugin_enabled:
            raise RuntimeError(
                "a partial restore was accepted instead of refused: " + " | ".join(refusal_lines[:4])
            )
        if recreated:
            raise RuntimeError("item_history was re-created on a partial restore despite the refusal")
        if not any("missing item_history" in line for line in refusal_lines):
            raise RuntimeError(f"the refusal did not name the missing table: {refusal_lines[:4]}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-3", **cleanup})
        paper = None
        if cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"partial-restore Paper cleanup was not clean: {cleanup}")

        # -- Phase D: future schema version must be refused ---------------------------------------
        future_database = partial_database
        load_dump(dump_path, future_database)
        mysql_exec("UPDATE plugin_stats SET schema_version = 99 WHERE id = 1", future_database)
        point_server_at(fixture, future_database)
        paper = SINGLE.PaperProcess(fixture, 4, port)
        started_server = wait_started(paper)
        plugin_enabled = observe(paper, "ItemGuard v1.0.0 is enabled.", 45)
        time.sleep(2.0)
        refusal_lines = [
            line.strip()
            for line in paper.lines
            if "Refusing to start" in line
            or "Unsupported future ItemGuard schema version" in line
            or "Error occurred while enabling ItemGuard" in line
            or "ItemGuard is disabled" in line
        ]
        receipt["phases"]["D_future_schema"] = {
            "database": future_database,
            "server_started": started_server,
            "plugin_enabled": plugin_enabled,
            "refusal_lines": refusal_lines[:8],
            "observed_verdict": "REFUSED_FAIL_CLOSED" if not plugin_enabled else "ENABLED_UNEXPECTEDLY",
        }
        if plugin_enabled:
            raise RuntimeError("future schema version did not fail closed")
        if not any("Unsupported future ItemGuard schema version: 99" in line for line in refusal_lines):
            raise RuntimeError(f"the future-version refusal was not named: {refusal_lines[:4]}")
        cleanup = paper.stop()
        receipt["cleanup"].append({"child": "paper-4", **cleanup})
        paper = None
        if cleanup["forced"] or not cleanup["log_closed"] or not cleanup["port_released"]:
            raise RuntimeError(f"future-schema Paper cleanup was not clean: {cleanup}")

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
            stopped = G.run_mysql("stop")
            receipt["mysql_cleanup"] = {
                "exit": stopped.returncode,
                "port_closed": not MYSQL.port_open(),
                "process_gone": "process_gone=True" in stopped.stdout,
                "stdout": stopped.stdout,
                "stderr": stopped.stderr,
            }
        receipt["finished_at"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        save(fixture / "premium-backup-restore-receipt.json", receipt)
    print(json.dumps(receipt, indent=2, ensure_ascii=False))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
