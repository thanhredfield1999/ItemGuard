"""Bind the current artifact's receipts into run/ and retire the previous artifact's.

Run from the repository root. Reads the fixture receipts the gates wrote under
30_KET_QUA_THU_NGHIEM/, keeps the ones whose JSON carries the current JAR hash, copies them into run/
with the established names, and moves the superseded set aside. Prints what it did so the ledger can
be updated from real output rather than from memory.
"""
from __future__ import annotations

import datetime
import json
import pathlib
import shutil

TARGET = "73b82f5a412b9cc55465981522c66f774c2f7036aee126d5c901f5947c5ac49e"
ARTIFACTS = pathlib.Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
RUN = pathlib.Path("run")
SUPERSEDED = RUN / "superseded"

RECEIPTS = (
    ("premium-paper-mysql-receipt.json", "premium-paper-mysql"),
    ("two-paper-mysql-receipt.json", "premium-two-paper-mysql"),
    ("paper-migration-receipt.json", "premium-paper-migration"),
    ("premium-failure-receipt.json", "premium-paper-failure"),
    ("premium-gameplay-receipt.json", "premium-paper-gameplay"),
    ("premium-backup-restore-receipt.json", "premium-backup-restore"),
    ("premium-reclaim-receipt.json", "premium-reclaim-handover"),
    ("premium-dupe-receipt.json", "premium-duplicate-detection"),
)


def carries_target(node: object) -> bool:
    if isinstance(node, dict):
        return any(carries_target(value) for value in node.values())
    if isinstance(node, list):
        return any(carries_target(value) for value in node)
    return node == TARGET


def stamp(receipt: pathlib.Path, data: dict) -> str:
    when = str(data.get("generated_at") or "")
    if when:
        return when[:19].replace("-", "").replace(":", "").replace("T", "-")
    return datetime.datetime.fromtimestamp(receipt.stat().st_mtime).strftime("%Y%m%d-%H%M%S")


def main() -> int:
    if not RUN.is_dir():
        raise SystemExit("run/ not found; run from the repository root")
    SUPERSEDED.mkdir(exist_ok=True)

    for receipt_name, dest_prefix in RECEIPTS:
        picked = None
        for receipt in sorted(
            ARTIFACTS.rglob(receipt_name), key=lambda p: p.stat().st_mtime, reverse=True
        ):
            try:
                data = json.loads(receipt.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if data.get("status") == "PASS" and carries_target(data):
                picked = (receipt, data)
                break
        if picked is None:
            print(f"MISSING  {dest_prefix}: no PASS receipt carrying {TARGET[:12]}")
            continue
        receipt, data = picked
        dest = RUN / f"{dest_prefix}-{stamp(receipt, data)}.json"
        shutil.copy2(receipt, dest)
        print(f"BOUND    {dest.name}  <- {receipt.parent.name}/{receipt.name}")

    # Retire receipts for earlier artifacts; keep the two that document real defects.
    keep = {
        "mysql-schema-gate-20260919-024126.json",
        "mysql-schema-gate-20260919-024353.json",
    }
    # The newest schema-gate receipt belongs to the current tree even though it carries no JAR hash
    # (it runs the MySQL-tagged tests, not Paper), so it is kept by pattern rather than by hash.
    gate_receipts = sorted(RUN.glob("mysql-schema-gate-*.json"))
    keep.update({candidate.name for candidate in gate_receipts[-1:]})
    for candidate in sorted(RUN.glob("*.json")):
        if candidate.name in keep:
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            # A zero-byte receipt is not evidence of anything: retire it rather than crash, and say so.
            shutil.move(str(candidate), str(SUPERSEDED / candidate.name))
            print(f"RETIRED  {candidate.name}  (unreadable or empty)")
            continue
        if carries_target(data):
            continue
        shutil.move(str(candidate), str(SUPERSEDED / candidate.name))
        print(f"RETIRED  {candidate.name}")

    print("\ncurrent set:")
    for candidate in sorted(RUN.glob("*.json")):
        print(f"  {candidate.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
