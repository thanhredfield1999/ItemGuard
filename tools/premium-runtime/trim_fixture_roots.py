"""Free the disk without destroying evidence: keep receipts, logs and stage manifests, drop the bulk.

Every runtime gate stages a full Paper server plus a Paper cache copy, so each fixture root is
hundreds of megabytes that nothing reads again once its receipt is written. This keeps the small files
(receipts, client/server logs, stage.json) in every root and removes the heavy parts, and it never
touches anything outside the fixture base.

Run from the repository root:  python tools/premium-runtime/trim_fixture_roots.py [--keep-newest N]
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

BASE = Path("E:/AI.WORK/30_KET_QUA_THU_NGHIEM")
HEAVY_DIRS = ("world", "world_nether", "world_the_end", "libraries", "versions", "cache", "logs")
HEAVY_FILES = ("paper.jar",)


def du(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep-newest", type=int, default=3,
                        help="fixture roots left completely untouched")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    roots = sorted(
        [item for item in BASE.glob("itemguard-*") if item.is_dir()],
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    if not roots:
        print(f"no fixture roots under {BASE}")
        return 0

    before = shutil.disk_usage("E:/").free
    print(f"roots: {len(roots)} | keep-newest: {args.keep_newest} | free now: {before / 1e9:.2f} GB")
    freed = 0
    for index, root in enumerate(roots):
        if index < args.keep_newest:
            print(f"KEEP   {root.name}")
            continue
        removed_here = 0
        for name in HEAVY_DIRS:
            target = root / name
            if target.is_dir():
                removed_here += du(target)
                if not args.dry_run:
                    shutil.rmtree(target, ignore_errors=True)
        for name in HEAVY_FILES:
            target = root / name
            if target.is_file():
                removed_here += target.stat().st_size
                if not args.dry_run:
                    target.unlink(missing_ok=True)
        plugins = root / "plugins"
        if plugins.is_dir():
            for jar in plugins.glob("*.jar"):
                removed_here += jar.stat().st_size
                if not args.dry_run:
                    jar.unlink(missing_ok=True)
        freed += removed_here
        kept = sorted(item.name for item in root.iterdir()) if root.is_dir() else []
        print(f"TRIM   {root.name}  freed {removed_here / 1e6:.0f} MB  kept {kept}")

    after = shutil.disk_usage("E:/").free
    print(f"freed {freed / 1e9:.2f} GB | free now: {after / 1e9:.2f} GB"
          + (" (dry run, nothing changed)" if args.dry_run else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
