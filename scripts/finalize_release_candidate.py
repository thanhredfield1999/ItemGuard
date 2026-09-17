"""Take the freshly built jar through every step that has to agree on one hash.

A rebuild leaves several copies of the same claim in the tree — the jar in three folders, the digest
in `SHA256SUMS.txt`, the digest printed inside the listing for downloaders, and the generated preview
and handoff — and every one of them has drifted at least once in this project's history. Doing them
by hand is how that happens; this does them in order and fails loudly if any of them ends up
disagreeing with the jar.

    python scripts/finalize_release_candidate.py

Steps: repackage + repin, rewrite the digest in the listing and the sums file, regenerate the preview
and the handoff, then run every offline gate. It does not run the runtime gates or the version
matrix; those are `scripts/run_release_runtime_gates.py` and `scripts/version_matrix.py`.
"""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPLOAD = ROOT / "release" / "spigot-upload"
JAR = UPLOAD / "ItemGuard-LITE-1.0.0.jar"
SUMS = ROOT / "release" / "SHA256SUMS.txt"
DESCRIPTION_COPIES = (
    UPLOAD / "description.bbcode.txt",
    ROOT / "docs" / "release" / "paste" / "03-description.bbcode.txt",
)

OFFLINE_GATES = (
    [sys.executable, str(ROOT / "scripts" / "check_listing_copies.py")],
    [sys.executable, str(ROOT / "scripts" / "check_no_hardcoded_vietnamese.py")],
    [sys.executable, str(ROOT / "scripts" / "check_no_hardcoded_vietnamese.py"), "--jar", str(JAR)],
    [sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"],
    [sys.executable, str(ROOT / "scripts" / "verify_lite_artifact.py")],
    [sys.executable, str(ROOT / "tools" / "lite-runtime" / "test_contracts.py")],
)


def run(argv: list[str]) -> str:
    print(f"\n$ {' '.join(argv)}", flush=True)
    finished = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True)
    tail = (finished.stdout or "").strip().splitlines()[-6:]
    for line in tail:
        print("   " + line)
    if finished.returncode != 0:
        print(f"FAILED (exit {finished.returncode})", file=sys.stderr)
        print((finished.stderr or "")[-2000:], file=sys.stderr)
        raise SystemExit(1)
    return finished.stdout or ""


def main() -> int:
    run([sys.executable, str(ROOT / "scripts" / "repackage_and_repin.py")])
    digest = hashlib.sha256(JAR.read_bytes()).hexdigest()
    size = JAR.stat().st_size
    print(f"\ncandidate {digest} ({size:,} bytes)")

    for path in DESCRIPTION_COPIES:
        text = path.read_text(encoding="utf-8")
        replaced = 0
        for token in set(re.findall(r"\b[0-9a-f]{64}\b", text)):
            if token != digest:
                text = text.replace(token, digest)
                replaced += 1
        path.write_text(text, encoding="utf-8")
        # The listing quotes one digest: its own download. A second, different digest in there is a
        # claim about some other artifact, and rewriting it to the current jar would manufacture
        # agreement instead of reporting disagreement. Refuse instead.
        remaining = {token for token in re.findall(r"\b[0-9a-f]{64}\b", text)} - {digest}
        if remaining:
            print(f"  {path.relative_to(ROOT)} quotes another artifact: {sorted(remaining)}",
                  file=sys.stderr)
            print("  the packager updates this file's own digest, not someone else's", file=sys.stderr)
            return 2
        print(f"  digest replaced in {path.relative_to(ROOT)} ({replaced} old hash(es))")

    SUMS.write_text(
        f"{digest} *target/ItemGuard-LITE-1.0.0.jar\n"
        f"{digest} *release/upload/ItemGuard-LITE-1.0.0.jar\n"
        f"{digest} *release/spigot-upload/ItemGuard-LITE-1.0.0.jar\n",
        encoding="utf-8",
    )
    print("  SHA256SUMS.txt rewritten")

    run([sys.executable, str(ROOT / "scripts" / "build_listing_preview.py")])
    run([sys.executable, str(ROOT / "scripts" / "build_spigot_handoff.py")])

    print("\noffline gates")
    for gate in OFFLINE_GATES:
        run(gate)
    print(f"\nevery copy and every offline gate agrees on {digest}")
    print("next: scripts/run_release_runtime_gates.py, then scripts/version_matrix.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
