"""Tests for the listing-copy gate, written because the gate itself had a hole.

`check_listing_copies.py` exempted any file whose first 20 lines contained the word
"SUPERSEDED". Two live files exploited that without meaning to: `LITE_RELEASE_GATES.md`
("Candidate e11ada6a… is **SUPERSEDED**") and `SCREENSHOT_STATUS.md` (a table cell reading
"Superseded") were both exempted while quoting `a952d161…` as the current/shipping jar. The
gate printed "0 findings" and a stale candidate stayed on the page.

The rule is now "a line that *starts* with a record marker", and `test_word_mentioned_in_prose_
is_not_a_record` fails if anyone loosens it back.

    python -m unittest scripts.test_check_listing_copies -v
"""
from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.check_listing_copies import scan  # noqa: E402

# A SHA that is definitely not the temp jar's, to stand in for a stale candidate.
OLD = "a" * 64


class ListingCopyGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.jar = self.root / "ItemGuard-LITE-1.0.0.jar"
        self.jar.write_bytes(b"jar")
        self.current = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.docs = self.root / "docs"
        self.docs.mkdir()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, name: str, text: str) -> Path:
        path = self.docs / name
        path.write_text(text, encoding="utf-8")
        return path

    def run_scan(self):
        return scan(self.jar, [self.docs])

    def test_file_quoting_the_current_jar_is_clean(self):
        self.write("current.md", f"sha256 {self.current}\n")
        digest, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)
        self.assertEqual([], exemptions)

    def test_a_short_quote_of_a_declared_retired_build_is_drift(self):
        """H2 (review #3): the digest-full-elsewhere rule cannot see a build whose 64 characters
        survive nowhere in the tree, so `SCREENSHOT_STATUS.md` could name a build that never shipped
        while the gate printed 0 findings."""
        self.write("shots.md", "| Captured on | `c776a020…8fdf` | The build actually running |\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual(["shots.md"], [f[0].name for f in findings])

    def test_a_retired_build_named_as_past_is_not_drift(self):
        self.write("shots.md", "| Superseded | `c776a020…8fdf` | An earlier build |\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)

    def test_avoiding_confusion_is_not_a_statement_that_the_jar_is_past(self):
        """M5 (review #3): `void` used to match inside `avoid`, so this live instruction exempted
        itself while sending the buyer to the wrong file."""
        self.write("readme.md", f"To avoid confusion, upload `{OLD}`\n")
        _, findings, _ = self.run_scan()
        self.assertEqual(["readme.md"], [f[0].name for f in findings])

    def test_as_mentioned_earlier_is_not_a_statement_that_the_jar_is_past(self):
        self.write("readme.md", f"As mentioned earlier, the file to upload is `{OLD}`\n")
        _, findings, _ = self.run_scan()
        self.assertEqual(["readme.md"], [f[0].name for f in findings])

    def test_a_same_name_jar_with_other_bytes_is_drift(self):
        """L6 (review #3): `release/upload/` was one of the seven copies that drifted, and the gate
        only ever read the sums file that describes it."""
        (self.docs / "ItemGuard-LITE-1.0.0.jar").write_bytes(b"an older build")
        _, findings, _ = self.run_scan()
        self.assertEqual(["ItemGuard-LITE-1.0.0.jar"], [f[0].name for f in findings])

    def test_a_differently_named_jar_is_a_different_product_and_is_left_alone(self):
        (self.docs / "ItemGuard-1.0.0.jar").write_bytes(b"the FULL build")
        _, findings, _ = self.run_scan()
        self.assertEqual([], findings)

    def test_stale_sha_without_a_marker_is_a_finding(self):
        self.write("stale.md", f"Current candidate: {OLD}\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual(["stale.md"], [f[0].name for f in findings])
        self.assertEqual([], exemptions)

    def test_self_declared_record_is_exempt(self):
        self.write("handoff.md", f"> **SUPERSEDED 2026-09-17 — do not paste this file.**\n\n{OLD}\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)
        self.assertEqual(["handoff.md"], [e[0].name for e in exemptions])

    def test_dated_filename_is_exempt(self):
        self.write("2026-09-12-listing-draft.md", f"another {OLD}\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)
        self.assertEqual(["2026-09-12-listing-draft.md"], [e[0].name for e in exemptions])

    def test_word_mentioned_in_prose_is_not_a_record(self):
        """The exact shape that hid two stale candidates: the word about *another* artifact."""
        self.write(
            "gates.md",
            "Candidate `e11ada6a` and its fixtures are **SUPERSEDED**: two defects were fixed.\n"
            f"Current candidate for every row below: `{OLD}`.\n",
        )
        _, findings, exemptions = self.run_scan()
        self.assertEqual(["gates.md"], [f[0].name for f in findings])
        self.assertEqual([], exemptions)

    def test_table_cell_mentioning_a_superseded_build_is_not_a_record(self):
        self.write(
            "status.md",
            "| Build | SHA-256 |\n|---|---|\n"
            f"| Superseded | `34d7fab2…9e0cd` |\n| **Shipping** | `{OLD}` |\n",
        )
        _, findings, exemptions = self.run_scan()
        self.assertEqual(["status.md"], [f[0].name for f in findings])
        self.assertEqual([], exemptions)

    def test_a_truncated_quote_of_a_dead_jar_is_a_finding(self):
        """M4 (review 2026-09-17): the gate only matched 64-character digests, while every document
        in the tree writes them short."""
        self.write("gates.md", "Current candidate: `aaaaaaaa…deadbeef`\n")
        # The full digest, in a dated record, is what makes the short quote recognisable as stale.
        self.write("2026-09-01-handover.md", f"an older handover: {OLD}\n")
        _, findings, _ = self.run_scan()
        self.assertEqual(["gates.md"], [f[0].name for f in findings])

    def test_a_short_token_that_names_no_known_jar_is_ignored(self):
        """Fixture names are hex-looking too (`itemguard-lite-isolated-8ca467a3aef8`)."""
        self.write("work.md", "fixture itemguard-lite-isolated-8ca467a3aef8 passed\n")
        _, findings, _ = self.run_scan()
        self.assertEqual([], findings)

    def test_a_past_jar_named_where_the_line_says_it_is_past_is_not_drift(self):
        self.write("gates.md", "`aaaaaaaa…` was superseded by the fixes below.\n")
        _, findings, _ = self.run_scan()
        self.assertEqual([], findings)

    def test_an_archive_holding_a_stale_jar_is_a_finding(self):
        import zipfile

        archive = self.docs / "handover.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("ItemGuard-LITE-1.0.0.jar", b"a different jar")
        _, findings, _ = self.run_scan()
        self.assertEqual(["handover.zip"], [f[0].name for f in findings])

    def test_a_dated_archive_is_a_record(self):
        import zipfile

        archive = self.docs / "2026-09-15-handover.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("ItemGuard-LITE-1.0.0.jar", b"a different jar")
        _, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)
        self.assertEqual(["2026-09-15-handover.zip"], [e[0].name for e in exemptions])

    def test_a_bare_record_word_does_not_exempt_a_file(self):
        """L4: `RECORD` alone used to match, so a heading like `## Record of handovers` exempted the
        whole file from the hash check."""
        self.write("notes.md", f"## Record of handovers\n\nstill quoting {OLD}\n")
        _, findings, _ = self.run_scan()
        self.assertEqual(["notes.md"], [f[0].name for f in findings])

    def test_marker_deeper_than_the_window_is_not_a_record(self):
        """A stale file cannot hide its marker below the lines the gate reads."""
        self.write("buried.md", ("filler\n" * 20) + f"SUPERSEDED\n{OLD}\n")
        _, findings, _ = self.run_scan()
        self.assertEqual(["buried.md"], [f[0].name for f in findings])

    def test_record_marker_ignores_quoted_markdown(self):
        self.write("quoted.md", f"## HISTORICAL RECORD — kept as handed over\n\n{OLD}\n")
        _, findings, exemptions = self.run_scan()
        self.assertEqual([], findings)
        self.assertEqual(["quoted.md"], [e[0].name for e in exemptions])


if __name__ == "__main__":
    unittest.main()
