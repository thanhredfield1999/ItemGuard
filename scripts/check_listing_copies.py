"""Fail when a listing or form copy still quotes a jar that is not the jar in this folder.

Seven copies of this listing lived in the tree and every one of them drifted: two lines hardcoded
in `build_spigot_handoff.py`, a handoff nobody generated, a `PREVIEW.html` nobody generated,
`SHA256SUMS.txt`, a second `release/upload/` jar, a description copy under `docs/release/paste/`,
and several docs under `docs/release/`. None of them surfaced by regenerating anything — the false
claims were only found by grepping the whole handover surface by hand.

Discipline is not a fix for that. This is: any file that talks to the buyer about the artifact has
to name the artifact that exists, and a file that is deliberately a historical record has to say so.

A copy is allowed to quote a different SHA only when it is one of:

  * a dated record - filename starts with `YYYY-MM-DD`, kept as a snapshot of what was handed over
  * a self-declared record - one of its first 20 lines *begins* with `SUPERSEDED`,
    `HISTORICAL RECORD` or `RECORD:` once leading markdown quoting (``#``, ``>``, ``*``) is
    stripped, as in `> **SUPERSEDED 2026-09-17 - do not paste this file.**`

The second rule used to be "the word SUPERSEDED appears anywhere in the first 20 lines", and that
let two live files through while they called a dead candidate their current one:
`LITE_RELEASE_GATES.md` said "Current candidate for every row below: a952d161…", and
`SCREENSHOT_STATUS.md` said "Shipping: a952d161…" — each of them merely *mentioning* that some
other candidate was superseded. A marker now has to be an announcement about the file it sits in.
`scripts/test_check_listing_copies.py` pins both behaviours.

    python scripts/check_listing_copies.py
"""
from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# Where a listing, form answer or preview can live. Historical records are recognised by name or
# by a marker they carry themselves.
SCAN = [
    REPO / "release" / "spigot-upload",
    REPO / "release" / "upload",
    REPO / "release" / "SHA256SUMS.txt",
    REPO / "release",  # the folder itself: a handover archive lived there, outside the scan
    REPO / "docs" / "release",
]
SUFFIXES = {".txt", ".md", ".html", ".bbcode"}
SKIP_DIRS = {"assets"}
SHA = re.compile(r"\b[0-9a-fA-F]{64}\b")
# A digest written short, which is how this tree writes them everywhere ("28aed104…"). The first
# version of the gate only matched 64 characters, so every truncated quote — including the ones that
# fooled its predecessor — was invisible to it. A short token only counts when it is a prefix of a
# full digest that appears somewhere in the same scan, which keeps fixture names
# (`itemguard-lite-isolated-8ca467a3aef8`) out of the results.
TRUNCATED = re.compile(r"\b[0-9a-fA-F]{8,63}\b")
DATED = re.compile(r"^\d{4}-\d{2}-\d{2}")
MARKER = re.compile(r"^(?:SUPERSEDED|HISTORICAL RECORD|RECORD:)(?:\s|$)", re.IGNORECASE)
# A past digest may be *named* in a live document, as long as the same line says it is past. The
# rebinding history in LITE_RELEASE_GATES.md is the reason this exception exists: deleting those
# lines would be rewriting the record, and leaving them unexplained is how a dead hash gets quoted as
# current.
#
# M5 (review #3): the first version of this list matched `void` inside `avoid` and the ordinary words
# `earlier` and `previously`, so a live instruction like "To avoid confusion, upload <old>" was
# exempted from its own warning. The phrases below all assert something, and the Vietnamese ones are
# here because half of these documents are written in Vietnamese.
HISTORIC = re.compile(
    r"\bsupersed\w*\b|\bvoid(?:ed)?\b|\bno longer\b|\brebind\w*\b|\bpreviously shipped\b|"
    r"\bearlier build\b|\bhistoric(?:al)? record\b|\bbản cũ\b|\bđã thay\b",
    re.IGNORECASE)

# Every candidate this project has replaced, declared one line at a time. A truncated quote of a
# retired build is drift whatever else the tree happens to contain - the `known` rule below can only
# see a digest that survives somewhere as 64 characters, so `SCREENSHOT_STATUS.md` could call a build
# that never shipped "the build actually running" while the gate printed 0 findings (H2, review #3).
RETIRED_PREFIXES = (
    "a952d161",   # the jar that drifted into four documents
    "94eb0dad",   # 835 tests, replaced after the first review round
    "28aed104",   # 846 tests, replaced after the second
    "65f3e1d1",   # intermediate build, never shipped
    "e11ada6a",   # intermediate build, never shipped
    "34d7fab2",   # the build the screenshot stats line was misread on
    "c776a020",   # the build the shipping screenshots were captured on
    "00b40fb1",   # 851 tests, replaced by the same review round's mapping fix
)
ZIP_SUFFIX = ".zip"
SHIPPING_JAR_NAME = "ItemGuard-LITE-1.0.0.jar"
MARKER_WINDOW = 20
LEADING = "#>* \t"


def record_marker(path: Path) -> str | None:
    """The self-declared record marker, or None.

    Only a line that starts with the marker counts: `Candidate X is SUPERSEDED` is a statement
    about X, and treating it as a statement about the file is what hid two stale candidates.
    """
    try:
        head = path.read_text(encoding="utf-8", errors="replace").splitlines()[:MARKER_WINDOW]
    except OSError:
        return None
    for line in head:
        stripped = line.lstrip(LEADING)
        if MARKER.match(stripped):
            return stripped[:80]
    return None


def is_record(path: Path) -> str | None:
    if DATED.match(path.name):
        return f"filename dated ({path.name[:10]})"
    return record_marker(path)


def text_of(path: Path) -> str:
    """The text of a file, or of the text entries inside it when it is an archive.

    Archives were outside the first version of this gate while
    `release/ItemGuard-LITE-1.0.0-Spigot.zip` sat in the tree with the listing inside it: a copy of
    the listing the gate could not see, which is the exact failure its docstring describes.
    """
    if path.suffix.lower() == ZIP_SUFFIX:
        try:
            with zipfile.ZipFile(path) as archive:
                return "\n".join(
                    archive.read(name).decode("utf-8", errors="replace")
                    for name in archive.namelist()
                    if Path(name).suffix.lower() in SUFFIXES and not name.endswith("/")
                )
        except (zipfile.BadZipFile, OSError):
            return ""
    return path.read_text(encoding="utf-8", errors="replace")


def stale_jars_inside(path: Path, digest: str) -> list[str]:
    """Digests of `.jar` entries inside an archive that are not the jar this folder ships.

    A zip is a handover package, and the failure it can carry is the one that already happened once:
    a folder whose jar had been replaced while the documents beside it still said to upload the old
    one. Reading only the text of an archive would miss the artifact itself.
    """
    if path.suffix.lower() != ZIP_SUFFIX:
        return []
    stale = []
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.lower().endswith(".jar"):
                    found = hashlib.sha256(archive.read(name)).hexdigest()
                    if found != digest:
                        stale.append(f"{name}={found[:16]}")
    except (zipfile.BadZipFile, OSError):
        return ["unreadable archive"]
    return stale


def candidates(roots: list[Path]) -> list[Path]:
    out = []
    for root in roots:
        files = [root] if root.is_file() else sorted(root.rglob("*"))
        for path in files:
            if not path.is_file():
                continue
            if SKIP_DIRS & set(path.parts):
                continue
            if path.suffix.lower() in SUFFIXES or path.suffix.lower() == ZIP_SUFFIX:
                out.append(path)
            elif path.suffix.lower() == ".jar" and path.name == SHIPPING_JAR_NAME:
                # L6 (review #3): `release/upload/ItemGuard-LITE-1.0.0.jar` is one of the seven copies
                # that drifted, and it was never hashed - the only evidence for it was
                # `release/SHA256SUMS.txt`, a text file the gate read as text. A jar of the shipping
                # name is that same claim, so it is hashed like one. A differently named jar
                # (`target/ItemGuard-1.0.0.jar`, the FULL build) is a different product and is left
                # alone.
                out.append(path)
    return out


def scan(jar: Path, roots: list[Path]) -> tuple[str, list[tuple[Path, list[str]]], list[tuple[Path, str]]]:
    """Return (jar digest, findings, exemptions). No printing, so tests can call it directly."""
    digest = hashlib.sha256(jar.read_bytes()).hexdigest().lower()
    paths = candidates(roots)
    texts = {path: text_of(path) for path in paths}

    # Every full digest the scan can see, so a truncated quote can be judged against the artifact it
    # names rather than against nothing.
    known = {digest}
    for text in texts.values():
        known |= {token.lower() for token in SHA.findall(text)}

    findings: list[tuple[Path, list[str]]] = []
    exemptions: list[tuple[Path, str]] = []
    for path, text in texts.items():
        found = set()
        for line in text.splitlines():
            if HISTORIC.search(line):
                continue  # naming a dead jar while saying it is dead is not drift
            for token in SHA.findall(line):
                if token.lower() != digest:
                    found.add(token.lower())
            for token in TRUNCATED.findall(line):
                short = token.lower()
                if len(short) == 64 or digest.startswith(short):
                    continue  # a prefix of the current jar names the current jar
                if any(short.startswith(retired) for retired in RETIRED_PREFIXES):
                    found.add(short)  # a declared retired build, wherever its full digest lives
                    continue
                if any(other != digest and other.startswith(short) for other in known):
                    found.add(short)
        if path.suffix.lower() == ".jar" and path.name == SHIPPING_JAR_NAME:
            same_name = hashlib.sha256(path.read_bytes()).hexdigest().lower()
            if same_name != digest:
                found.add(f"same-name jar: {same_name}")
        found |= set(stale_jars_inside(path, digest))
        if not found:
            continue
        why = is_record(path)
        if why:
            exemptions.append((path, why))
            continue
        findings.append((path, sorted(t[:16] for t in found)))
    return digest, findings, exemptions


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)


def main() -> int:
    jar = REPO / "release" / "spigot-upload" / "ItemGuard-LITE-1.0.0.jar"
    if not jar.is_file():
        print(f"no jar to compare against: {jar}", file=sys.stderr)
        return 2
    digest, findings, exemptions = scan(jar, SCAN)

    print(f"jar {digest}")
    print(f"files quoting an older artifact and not marked as a record: {len(findings)}")
    for path, tokens in findings:
        print(f"  {_rel(path)}  ->  {', '.join(tokens)}")
    print(f"deliberate records (exempt, and told to say so): {len(exemptions)}")
    for path, why in exemptions:
        print(f"  {_rel(path)}  ->  {why}")
    if findings:
        print("\nEither update the file, or mark it SUPERSEDED in its own first 20 lines "
              "(a line that *starts* with the word - see this file's docstring), or date its "
              "filename if it is a record of a past handover. Do not edit it by hand and hope it "
              "stays in step - that is how all seven copies of this listing drifted.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
