"""Controlled MySQL 8.4.6 fixture for the Premium backend work.

This is a *test* server: one datadir, one pinned port, no service registration, no
machine-wide configuration, no admin rights. It exists because the MySQL work
(design: docs/design/2026-09-16-premium-mysql-contract.md) has a gate that says
"same invariants proven by test on both backends", and that cannot be met by a
mock. Until one exists, every MySQL claim would be unverified.

Lifecycle, per the workspace rule that the agent owns cleanup:

    python tools/mysql-runtime/mysql_fixture.py start     # starts, waits for ready, writes a receipt
    python tools/mysql-runtime/mysql_fixture.py status     # is it up, which PID, which port
    python tools/mysql-runtime/mysql_fixture.py stop       # graceful shutdown, then proves it is gone

`stop` never kills a process it cannot identify as ours: it checks the recorded
PID against the process's own command line (it must name this datadir) before
asking it to shut down, and it verifies afterwards that the port is closed.

The server directory, datadir, download and run state are all under
tools/mysql-runtime/ and all git-ignored. Nothing here is production.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASEDIR = ROOT / 'server' / 'mysql-8.4.6-winx64'
DATADIR = ROOT / 'data'
RUNDIR = ROOT / 'run'
LOG = RUNDIR / 'mysqld.err'
PIDFILE = RUNDIR / 'mysqld.pid'
RECEIPT = RUNDIR / 'mysql-fixture-receipt.json'

BIND = '127.0.0.1'
PORT = 33316
TEST_DATABASE = 'itemguard_premium'
TEST_USER = 'itemguard'
TEST_PASSWORD = 'itemguard_test_password'


def bin_path(name: str) -> Path:
    suffix = '.exe' if os.name == 'nt' else ''
    path = BASEDIR / 'bin' / (name + suffix)
    if not path.exists():
        raise SystemExit(f'missing {path} - unzip the MySQL archive into {BASEDIR.parent}')
    return path


def run(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout)


def port_open() -> bool:
    import socket
    with socket.socket() as probe:
        probe.settimeout(1.5)
        return probe.connect_ex((BIND, PORT)) == 0


def ping() -> bool:
    if not port_open():
        return False
    result = run([str(bin_path('mysqladmin')), '--protocol=tcp', '-h', BIND, '-P', str(PORT),
                  '--connect-timeout=3', '-u', 'root', 'ping'])
    return result.returncode == 0


def recorded_pid() -> int | None:
    if not PIDFILE.exists():
        return None
    text = PIDFILE.read_text(encoding='utf-8').strip()
    return int(text) if text.isdigit() else None


def process_command_line(pid: int) -> str | None:
    """Returns the command line of `pid`, or None when no such process exists."""
    if os.name != 'nt':
        try:
            return Path(f'/proc/{pid}/cmdline').read_bytes().decode('utf-8', 'replace')
        except OSError:
            return None
    script = (
        f"$p = Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\";"
        "if ($p) { $p.CommandLine }"
    )
    result = run(['powershell.exe', '-NoProfile', '-Command', script], timeout=60)
    text = result.stdout.strip()
    return text or None


def ours(pid: int) -> bool:
    """A PID is ours only when its command line names this datadir."""
    line = process_command_line(pid)
    if line is None:
        return False
    return str(DATADIR) in line


def initialize() -> None:
    if (DATADIR / 'auto.cnf').exists():
        return
    DATADIR.mkdir(parents=True, exist_ok=True)
    RUNDIR.mkdir(parents=True, exist_ok=True)
    print(f'initializing datadir {DATADIR} (no root password, local only)')
    result = run([str(bin_path('mysqld')), '--no-defaults', f'--basedir={BASEDIR}',
                  f'--datadir={DATADIR}', '--initialize-insecure', f'--log-error={LOG}'],
                 timeout=600)
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(f'mysqld --initialize-insecure failed with {result.returncode}, see {LOG}')


def start() -> int:
    if ping():
        print(f'already running on {BIND}:{PORT} (pid {recorded_pid()})')
        return 0
    initialize()
    RUNDIR.mkdir(parents=True, exist_ok=True)
    command = [
        str(bin_path('mysqld')), '--no-defaults',
        f'--basedir={BASEDIR}', f'--datadir={DATADIR}',
        f'--port={PORT}', f'--bind-address={BIND}', '--mysqlx=0',
        # Name resolution stays on: with --skip-name-resolve a TCP connection from
        # 127.0.0.1 is not the account 'root'@'localhost', so the fixture cannot be
        # provisioned at all (measured: ERROR 1130). A local test server is the wrong
        # place to trade provisioning for a lookup that costs microseconds.
        '--skip-networking=0',
        # Small on purpose: this exists to be stopped, not to hold data.
        '--innodb-redo-log-capacity=67108864',
        '--innodb-buffer-pool-size=268435456',
        f'--log-error={LOG}', f'--pid-file={PIDFILE}',
    ]
    creation = 0
    if os.name == 'nt':
        creation = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    with LOG.open('ab') as errors:
        process = subprocess.Popen(command, stdout=errors, stderr=errors,
                                   stdin=subprocess.DEVNULL, creationflags=creation,
                                   close_fds=True)
    started = time.time()
    while time.time() - started < 120:
        if ping():
            break
        if process.poll() is not None:
            tail = LOG.read_text(encoding='utf-8', errors='replace').splitlines()[-15:]
            raise SystemExit('mysqld exited during startup:\n' + '\n'.join(tail))
        time.sleep(1)
    else:
        raise SystemExit(f'mysqld did not answer on {BIND}:{PORT} within 120s, see {LOG}')
    provision()
    receipt = {
        'basedir': str(BASEDIR), 'datadir': str(DATADIR), 'bind': BIND, 'port': PORT,
        'pid': recorded_pid() or process.pid,
        'database': TEST_DATABASE, 'user': TEST_USER,
        'started_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
        'version': version(),
    }
    RECEIPT.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, indent=2))
    print(f'EVIDENCE port_open={port_open()} ping={ping()}')
    return 0


def mysql_client(sql: str) -> subprocess.CompletedProcess:
    return run([str(bin_path('mysql')), '--protocol=tcp', '-h', BIND, '-P', str(PORT),
                '-u', 'root', '--batch', '--skip-column-names', '-e', sql])


def version() -> str:
    result = mysql_client('SELECT VERSION()')
    return result.stdout.strip()


def provision() -> None:
    statements = [
        f"CREATE DATABASE IF NOT EXISTS {TEST_DATABASE} "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin",
        f"CREATE USER IF NOT EXISTS '{TEST_USER}'@'%' IDENTIFIED BY '{TEST_PASSWORD}'",
        f"GRANT ALL PRIVILEGES ON {TEST_DATABASE}.* TO '{TEST_USER}'@'%'",
        "FLUSH PRIVILEGES",
    ]
    for statement in statements:
        result = mysql_client(statement)
        if result.returncode != 0:
            raise SystemExit(f'provisioning failed: {statement}\n{result.stderr}')


def status() -> int:
    up = ping()
    pid = recorded_pid()
    print(f'port {BIND}:{PORT} open={port_open()} ping={up}')
    print(f'recorded pid={pid} ours={ours(pid) if pid else None}')
    if RECEIPT.exists():
        print(RECEIPT.read_text(encoding='utf-8').strip())
    return 0 if up else 1


def stop() -> int:
    pid = recorded_pid()
    if not ping() and pid is None:
        print('EVIDENCE nothing to stop: port closed, no recorded pid')
        return 0
    if pid is not None:
        if process_command_line(pid) is None:
            print(f'recorded pid {pid} no longer exists; treating as stopped')
        elif not ours(pid):
            raise SystemExit(
                f'refusing to stop pid {pid}: its command line does not name {DATADIR}'
            )
        else:
            result = run([str(bin_path('mysqladmin')), '--protocol=tcp', '-h', BIND,
                          '-P', str(PORT), '--connect-timeout=5', '-u', 'root', 'shutdown'],
                         timeout=180)
            if result.returncode != 0:
                # A server that cannot be reached for a graceful shutdown is still ours, and
                # leaving it running would break the "one heavy test server per project" rule.
                # Terminating it is reported as forced rather than passed off as graceful.
                print(f'mysqladmin shutdown returned {result.returncode}: '
                      f'{result.stderr.strip()} - falling back to terminating our own pid')
                run(['powershell.exe', '-NoProfile', '-Command',
                     f'Stop-Process -Id {pid} -Force'], timeout=60)
                time.sleep(3)
    deadline = time.time() + 120
    while time.time() < deadline and port_open():
        time.sleep(1)
    # The process, too, is polled rather than sampled once: on Windows a process object can still
    # answer a query for a moment after the server has exited, and reporting that as "not stopped"
    # would make the gate fail for a reason unrelated to what it measures.
    gone = True
    while time.time() < deadline:
        if pid is None or process_command_line(pid) is None:
            gone = True
            break
        gone = False
        time.sleep(1)
    print(f'EVIDENCE port_open={port_open()} process_gone={gone}')
    if port_open() or not gone:
        raise SystemExit('shutdown did not complete; the fixture is still running')
    if PIDFILE.exists():
        PIDFILE.unlink()
    print('stopped')
    return 0


def reset_database() -> int:
    """Drops and recreates the test schema. Never touches the datadir."""
    if not ping():
        raise SystemExit('fixture is not running')
    mysql_client(f'DROP DATABASE IF EXISTS {TEST_DATABASE}')
    provision()
    print(f'{TEST_DATABASE} recreated on {BIND}:{PORT}')
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('action', choices=['start', 'stop', 'status', 'reset-database'])
    arguments = parser.parse_args()
    return {'start': start, 'stop': stop, 'status': status,
            'reset-database': reset_database}[arguments.action]()


if __name__ == '__main__':
    sys.exit(main())
