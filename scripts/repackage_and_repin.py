"""Repackage LITE, then repin every tool that names the candidate by hash.

Two failures this replaces, both real:

  1. A shell one-liner ran `sed` on the pin files even when the build had failed, replacing the
     hash with an EMPTY string. The artifact gate then rejected every fixture with "Candidate
     mismatch" — the gate was right, the caller was broken.
  2. The same one-liner copied a jar that did not exist, so `release/upload/` silently kept the
     PREVIOUS build while the docs described the new one.

So: build first, refuse to touch anything unless a jar actually exists, and verify each pin
after writing it.
"""
from __future__ import annotations

import hashlib
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / "target" / "ItemGuard-LITE-1.0.0.jar"
PINS = [ROOT / "tools/lite-runtime/smoke.py", ROOT / "tools/lite-runtime/manual.py"]
COPIES = [ROOT / "release/upload", ROOT / "release/spigot-upload"]


def main() -> int:
    build = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "package_lite.py")],
        cwd=ROOT, capture_output=True, text=True,
    )
    if build.returncode != 0:
        # Stop here. Everything below writes state that is wrong if the build failed.
        print("BUILD FAILED — nothing repinned, nothing copied.")
        print((build.stdout or "")[-800:])
        print((build.stderr or "")[-800:])
        return 1

    if not JAR.is_file():
        print(f"BUILD reported success but {JAR} does not exist — refusing to continue.")
        return 1

    digest = hashlib.sha256(JAR.read_bytes()).hexdigest()
    size = JAR.stat().st_size
    print(f"candidate {digest}")
    print(f"size      {size:,} bytes")

    for pin in PINS:
        text = pin.read_text(encoding="utf-8")
        # Keep each file's own spacing style; only the hash changes.
        patched, count = re.subn(
            r"(EXPECTED\s*=\s*')[a-f0-9]{0,64}(')",
            lambda m: m.group(1) + digest + m.group(2),
            text,
            count=1,
        )
        if count != 1:
            print(f"  FAILED: no EXPECTED assignment found in {pin.name}")
            return 1
        pin.write_text(patched, encoding="utf-8")

        # Read back. A pin that silently did not land is how the empty-hash bug survived.
        confirmed = re.search(r"EXPECTED\s*=\s*'([a-f0-9]{64})'", pin.read_text(encoding="utf-8"))
        if not confirmed or confirmed.group(1) != digest:
            print(f"  FAILED: {pin.name} does not hold the new hash after writing")
            return 1
        print(f"  repinned {pin.name}")

    for folder in COPIES:
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / JAR.name
        shutil.copy2(JAR, target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            print(f"  FAILED: copy in {folder.name} does not match the built jar")
            return 1
        print(f"  copied to {folder.relative_to(ROOT)}")

    print("\nAll pins and copies verified against the built jar.")
    print("Runtime evidence for the previous candidate is now void — re-run the fixtures.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
