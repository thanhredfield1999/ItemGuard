"""M1's gate: run the MySQL schema invariant tests against the controlled fixture.

Sequence, and it is a sequence on purpose:

    1. start the fixture            (tools/mysql-runtime/mysql_fixture.py start)
    2. run `mvnw -Pmysql test`      (only the mysql-tagged tests)
    3. read the verdict from surefire's own XML, not from the exit code alone
    4. stop the fixture             (always, in a finally, even when the tests fail)

The step that is easiest to get wrong is the last one. A database server left running after a
failed gate is exactly the leak the workspace rules were written about, so the stop is in a
`finally`, its outcome is verified (port closed, process gone) and the verification is part of
the receipt rather than a line of prose.

Usage:
    python scripts/run_mysql_schema_gate.py
"""
from __future__ import annotations

import datetime
import json
import os
import re
import socket
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'tools/mysql-runtime/mysql_fixture.py'
HOST, PORT = '127.0.0.1', 33316
REPORTS = ROOT / 'target/surefire-reports'
EXPECTED_CLASSES = (
    'com.itemguard.persistence.MySqlSchemaInvariantTest',
    'com.itemguard.persistence.MySqlIdentityLockTest',
    'com.itemguard.persistence.MySqlCrossServerFindingTest',
    'com.itemguard.persistence.MySqlConnectionOwnerTest',
    'com.itemguard.catalog.MySqlCatalogRepositoryTest',
    'com.itemguard.persistence.MySqlMigrationServiceTest',
    'com.itemguard.persistence.MySqlRepositoryRuntimeTest',
    'com.itemguard.persistence.MySqlLossJournalRuntimeTest',
    'com.itemguard.persistence.MySqlTwoServerRuntimeTest',
)
VERDICT_DIR = ROOT / 'run'
LOG = ROOT / 'run/mysql-schema-gate.log'


def port_open() -> bool:
    with socket.socket() as probe:
        probe.settimeout(1.5)
        return probe.connect_ex((HOST, PORT)) == 0


def fixture(action: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(FIXTURE), action],
                          capture_output=True, text=True, timeout=900)


def java_home() -> str:
    configured = os.environ.get('JAVA_HOME', '')
    if configured and Path(configured).is_dir():
        return configured
    for candidate in ('C:/Program Files/Java/jdk-21', 'C:/Program Files/Eclipse Adoptium/jdk-21'):
        if Path(candidate).is_dir():
            return candidate
    raise SystemExit('no JDK 21 found; set JAVA_HOME')


def run_tests() -> int:
    environment = dict(os.environ, JAVA_HOME=java_home())
    command = ['cmd.exe', '/d', '/s', '/c', str(ROOT / 'mvnw.cmd'), '-o', '-Pmysql', 'test',
               '--no-transfer-progress'] if os.name == 'nt' else \
              ['sh', str(ROOT / 'mvnw'), '-o', '-Pmysql', 'test', '--no-transfer-progress']
    VERDICT_DIR.mkdir(parents=True, exist_ok=True)
    # Reports from an earlier run are removed first: surefire does not clean this directory, and a
    # class that was deleted or renamed would otherwise keep contributing its old green file to a
    # verdict nobody re-derived.
    if REPORTS.exists():
        for stale in REPORTS.glob('TEST-*.xml'):
            stale.unlink()
    with LOG.open('w', encoding='utf-8') as output:
        completed = subprocess.run(command, cwd=ROOT, env=environment, stdout=output,
                                   stderr=subprocess.STDOUT)
    return completed.returncode


def test_totals() -> dict:
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'classes': []}
    if not REPORTS.exists():
        return totals
    for path in sorted(REPORTS.glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(root.get(key, 0))
        totals['classes'].append(root.get('name', path.stem))
    return totals


def missing_classes(totals: dict) -> list:
    return [name for name in EXPECTED_CLASSES if name not in totals['classes']]


def server_version() -> str:
    """The fixture writes its own receipt; the start output only repeats it when it starts."""
    receipt = ROOT / 'tools/mysql-runtime/run/mysql-fixture-receipt.json'
    if receipt.exists():
        try:
            return json.loads(receipt.read_text(encoding='utf-8')).get('version', 'unknown')
        except json.JSONDecodeError:
            pass
    return 'unknown'


def main() -> int:
    receipt: dict = {
        'gate': 'mysql-schema-invariants',
        'started_at': datetime.datetime.now().astimezone().isoformat(timespec='seconds'),
        'port': PORT,
        'maven_exit': None,
        'totals': None,
        'fixture_stopped': None,
    }
    started = fixture('start')
    receipt['fixture_start_exit'] = started.returncode
    receipt['server'] = server_version()
    print(started.stdout.strip().splitlines()[-1] if started.stdout.strip() else started.stderr)
    try:
        if started.returncode != 0:
            receipt['verdict'] = 'FAILED_FIXTURE_START'
        else:
            receipt['maven_exit'] = run_tests()
            receipt['totals'] = test_totals()
            totals = receipt['totals']
            absent = missing_classes(totals)
            if absent:
                receipt['verdict'] = 'FAILED_EXPECTED_CLASS_MISSING'
                receipt['missing_classes'] = absent
            elif totals['tests'] < 1:
                receipt['verdict'] = 'FAILED_NO_TESTS_RAN'
            elif receipt['maven_exit'] != 0 or totals['failures'] or totals['errors'] \
                    or totals['skipped']:
                receipt['verdict'] = 'FAILED_TESTS'
            else:
                receipt['verdict'] = 'PASS_MYSQL_SCHEMA_INVARIANTS'
    finally:
        stopped = fixture('stop')
        closed, gone = (not port_open()), 'process_gone=True' in stopped.stdout
        receipt['fixture_stopped'] = {'exit': stopped.returncode, 'port_closed': closed,
                                      'process_gone': gone}
        if not closed or not gone:
            receipt['verdict'] = 'FAILED_FIXTURE_NOT_STOPPED'
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    path = VERDICT_DIR / f'mysql-schema-gate-{stamp}.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, indent=2))
    print(f'verdict written to {path}')
    return 0 if receipt.get('verdict') == 'PASS_MYSQL_SCHEMA_INVARIANTS' else 1


if __name__ == '__main__':
    sys.exit(main())
