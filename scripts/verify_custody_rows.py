"""Cross-check the custody claims against the fixture's own database, not the probe's markers.

The runtime scope reports `custody self transfers=0 holders=1`, `transfer transfers=1 holders=2` and
`pingpong transfers=1 holders=2`. Those numbers are computed by `CustodyChain`, which is unit-tested;
re-deriving them here would be a second implementation and weaker evidence than the one under test.
What a raw-row check *can* prove is that the numbers rest on real recorded events:

  * the identity in the database is the one the scope minted,
  * a genuine handover happened - a PICKUP by a different actor than the one holding it,
  * the pingpong sequence happened, with every swap inside the configured custody window, which is
    the precondition for "counted once" being the correct answer rather than a coincidence,
  * no third actor appears, so `holders=2` is not an undercount,
  * the identity's last action is the `/clear` the scope stages last.

At the scale this harness produces - handovers seconds apart against a 15-minute window - the window
clause is a precondition every run satisfies rather than something this check establishes (L3, review
#3). It is asserted anyway, against the window the fixture itself configured, so a fixture that runs
with a small window cannot quietly turn "counted once" into the wrong expected answer. The numbers in
the gate table are read as this script prints them.

    python scripts/verify_custody_rows.py E:/AI.WORK/30_KET_QUA_THU_NGHIEM/<fixture>
"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

DEFAULT_CUSTODY_WINDOW_MS = 15 * 60 * 1000   # CustodyWindow.DEFAULT_MILLIS
CUSTODY_WINDOW_KEY = "tracking.custody-window-ms"
KEY_TAIL = "custody-window-ms"   # the same key nested under `tracking:`


def custody_window_ms(root: Path) -> tuple[int, str]:
    """(window, where that number came from) for the run this fixture describes.

    L3 (review #3): the value used to be a constant in this script, so a fixture running with a
    different window would have been judged against a number that never applied to it. The fixture's
    own loaded config is the only file that describes that run.

    In the LITE edition this key is not in `config.yml` at all (checked: the dumped config carries
    neither `custody` nor the key), so the honest answer is the code default - and it says so, rather
    than reporting a default as if the fixture had configured it.
    """
    config = root / "plugins" / "ItemGuard" / "config.yml"
    if config.is_file():
        for line in config.read_text(encoding="utf-8", errors="replace").splitlines():
            stripped = line.strip()
            for name in (CUSTODY_WINDOW_KEY, KEY_TAIL):
                if stripped.startswith(name + ":"):
                    value = stripped.split(":", 1)[1].split("#")[0].strip()
                    if value.isdigit():
                        return int(value), f"configured in {config.name}"
    return DEFAULT_CUSTODY_WINDOW_MS, "CustodyWindow.DEFAULT_MILLIS (LITE config has no such key)"
SWAP_ACTIONS = {"DROP", "PICKUP"}


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[1])
    stage = json.loads((root / "stage.json").read_text())
    attempt = json.loads((root / "attempt.json").read_text())
    database = root / "plugins" / "ItemGuard" / "itemguard.db"
    print(f"fixture  {root.name}")
    print(f"scope    {attempt.get('scope')}  generations={attempt.get('generations')}")
    print(f"db       {database}")
    window, window_source = custody_window_ms(root)
    print(f"window   {window} ms  [{window_source}]")

    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    cursor = connection.cursor()
    integrity = cursor.execute("pragma integrity_check").fetchone()[0]
    assert integrity == "ok", f"database integrity: {integrity}"

    tracked = cursor.execute(
        "select code, item_uuid, owner_name, last_action from tracked_items").fetchall()
    assert len(tracked) == 1, f"expected exactly one tracked identity, saw {len(tracked)}"
    code, _uuid, owner, last_action = tracked[0]
    rows = cursor.execute(
        "select action, player_name, timestamp from item_history order by id").fetchall()
    connection.close()
    print(f"tracked  {code}  owner={owner}  last_action={last_action}")
    print(f"history  {len(rows)} rows")

    actors = [name for _action, name, _ts in rows if name and name != "server"]
    distinct = sorted(set(actors))
    assert len(distinct) == 2, f"expected exactly two actors, saw {distinct}"

    # The handover: the first PICKUP by someone other than the actor who spawned the identity.
    spawner = next(name for action, name, _ts in rows if action == "SPAWN")
    handover = next(((action, name, ts) for action, name, ts in rows
                     if action == "PICKUP" and name != spawner), None)
    assert handover, f"no PICKUP by a second actor: the handover case has no event behind it"
    assert spawner in distinct and handover[1] in distinct

    # How many genuine handovers the rows actually contain, by the definition the policy uses: a
    # PICKUP by someone other than whoever is holding it. A self drop and re-pick is not one.
    holder = spawner
    handovers = []
    for action, name, ts in rows:
        if action == "PICKUP" and name != holder:
            holder = name
            handovers.append((name, ts))
    assert len(handovers) >= 4, (
        f"expected a repeated handover sequence behind the pingpong case, saw {len(handovers)}")

    # Every one of them inside the custody window: that is what makes "counted once" the expected
    # answer rather than an accident, and it is why the raw row count below is larger than the
    # number the scope reports.
    gaps = [b[1] - a[1] for a, b in zip(handovers, handovers[1:])]
    assert max(gaps) < window, (
        f"a handover falls outside the {window} ms custody window ({max(gaps)} ms), so "
        "'counted once' would not be the expected answer")

    assert last_action == "CLEARED", f"the scope stages /clear last; last_action is {last_action}"
    print(f"actors   {distinct[0]} + {distinct[1]}")
    print(f"handover PICKUP by {handover[1]} after SPAWN by {spawner}")
    print(f"handovers {len(handovers)} PICKUPs by the other actor, largest gap {max(gaps)} ms, all")
    print(f"         inside the window - so the scope's 'transfers=1' is the throttled answer to")
    print(f"         {len(handovers)} real handovers rather than an accident.")
    print(f"         The derivation itself belongs to CustodyChain and its unit tests; what is")
    print(f"         checked here is that the events, the actors and the timing are real.")
    print(f"verdict  PASS_CUSTODY_ROWS — the numbers the scope reports rest on these rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
