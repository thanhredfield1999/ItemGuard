"""Tests for the custody row cross-check, because a checker nobody can fail is not a check.

The three ways this one could pass while proving nothing: a third actor in the rows (so `holders=2`
would be an undercount), a handover outside the custody window (so "counted once" would not be the
expected answer), and a fixture whose database belongs to a different scope's shape entirely.

    python -m unittest scripts.test_verify_custody_rows -v
"""
from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.verify_custody_rows import main  # noqa: E402

WINDOW = 15 * 60 * 1000


class CustodyRowCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "plugins" / "ItemGuard").mkdir(parents=True)
        (self.root / "stage.json").write_text(json.dumps({"port": 1, "candidate_sha256": "x"}))
        (self.root / "attempt.json").write_text(json.dumps(
            {"scope": "separate-lite-smoke", "generations": 2}))

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write_rows(self, rows, last_action="CLEARED"):
        database = self.root / "plugins" / "ItemGuard" / "itemguard.db"
        if database.exists():
            database.unlink()
        connection = sqlite3.connect(database)
        connection.execute("create table tracked_items (code text, item_uuid text,"
                           " owner_name text, last_action text)")
        connection.execute("create table item_history (id integer primary key, action text,"
                           " player_name text, timestamp integer)")
        connection.execute("insert into tracked_items values ('AAAAAA','u','LiteMember',?)",
                           (last_action,))
        for index, (action, name, ts) in enumerate(rows, start=1):
            connection.execute("insert into item_history values (?,?,?,?)",
                               (index, action, name, ts))
        connection.commit()
        connection.close()

    def run_check(self):
        return main(["verify_custody_rows.py", str(self.root)])

    def test_rows_with_one_handover_and_a_throttled_swap_loop_pass(self):
        rows = [("SPAWN", "LiteStaff", 1_000)]
        rows += [("PICKUP", "LiteMember", 2_000)]
        for step in range(6):
            actor, other = (("LiteMember", "LiteStaff") if step % 2 == 0
                            else ("LiteStaff", "LiteMember"))
            rows.append(("DROP", actor, 3_000 + step * 4_000))
            rows.append(("PICKUP", other, 3_500 + step * 4_000))
        self.write_rows(rows)
        self.assertEqual(0, self.run_check())

    def test_a_third_actor_rejects_because_holders_2_would_be_an_undercount(self):
        rows = [("SPAWN", "LiteStaff", 1_000),
                ("PICKUP", "LiteMember", 2_000),
                ("DROP", "LiteMember", 3_000),
                ("PICKUP", "LiteThird", 4_000),
                ("DROP", "LiteThird", 5_000),
                ("PICKUP", "LiteStaff", 6_000),
                ("DROP", "LiteStaff", 7_000),
                ("PICKUP", "LiteMember", 8_000)]
        self.write_rows(rows)
        with self.assertRaisesRegex(AssertionError, "exactly two actors"):
            self.run_check()

    def test_a_handover_outside_the_window_rejects(self):
        rows = [("SPAWN", "LiteStaff", 1_000)]
        for step in range(5):
            actor, other = (("LiteMember", "LiteStaff") if step % 2 == 0
                            else ("LiteStaff", "LiteMember"))
            rows.append(("DROP", actor, 10_000 + step * WINDOW))
            rows.append(("PICKUP", other, 10_500 + step * WINDOW))
        self.write_rows(rows)
        with self.assertRaisesRegex(AssertionError, "custody window"):
            self.run_check()

    def test_the_window_comes_from_the_fixture_not_from_this_script(self):
        """L3 (review #3): the window used to be a constant here, so a fixture configured with a
        smaller one would have been judged against a number that never applied to it."""
        (self.root / "plugins" / "ItemGuard").mkdir(parents=True, exist_ok=True)
        (self.root / "plugins" / "ItemGuard" / "config.yml").write_text(
            "tracking:\n  custody-window-ms: 900\n", encoding="utf-8")
        rows = [("SPAWN", "LiteStaff", 1_000)]
        for step in range(5):
            actor, other = (("LiteMember", "LiteStaff") if step % 2 == 0
                            else ("LiteStaff", "LiteMember"))
            rows.append(("DROP", actor, 2_000 + step * 1_000))
            rows.append(("PICKUP", other, 2_500 + step * 1_000))
        self.write_rows(rows)
        with self.assertRaisesRegex(AssertionError, "custody window"):
            self.run_check()

    def test_a_missing_last_clear_rejects(self):
        rows = [("SPAWN", "LiteStaff", 1_000), ("PICKUP", "LiteMember", 2_000)]
        rows += [("DROP", "LiteMember", 3_000), ("PICKUP", "LiteStaff", 4_000),
                 ("DROP", "LiteStaff", 5_000), ("PICKUP", "LiteMember", 6_000),
                 ("DROP", "LiteMember", 7_000), ("PICKUP", "LiteStaff", 8_000)]
        self.write_rows(rows, last_action="PICKUP")
        with self.assertRaisesRegex(AssertionError, "stages /clear last"):
            self.run_check()


if __name__ == "__main__":
    unittest.main()
