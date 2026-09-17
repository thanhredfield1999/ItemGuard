"""Real synthetic child/port tests for launcher cleanup; not Paper/plugin evidence."""
import io
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import manual

CHILD = """
import socket, sys
s = socket.socket()
s.bind(('127.0.0.1', int(sys.argv[1])))
s.listen()
print('READY', flush=True)
for line in sys.stdin:
    if line.strip() == 'stop':
        print('SAVED', flush=True)
        break
s.close()
"""


class ManualLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'itemguard-lite-manual-0123456789ab'
        self.root.mkdir()
        self.port = manual.free()
        self.patches = [
            patch.object(manual, 'BASE', self.base),
            patch.object(manual, 'JAVA', Path(sys.executable)),
            patch.object(sys, 'stdin', io.StringIO('')),
            patch.object(manual, 'java_command', return_value=[
                sys.executable, '-u', '-c', CHILD, str(self.port)], create=True),
        ]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)
        manual.save(self.root / 'stage.json', {
            'root': str(self.root), 'port': self.port,
            'controller': manual.sha(Path(manual.__file__)),
            'java': manual.sha(Path(sys.executable)), 'files': {},
        })

    def outcome(self):
        value = json.loads((self.root / 'outcome.json').read_text())
        self.assertEqual(0, value['exit'])
        self.assertFalse(value['forced'])
        self.assertTrue(value['port_released'])
        self.assertIn('SAVED', (self.root / 'console.log').read_text())
        self.assertEqual(self.port, manual.free(self.port))
        return value

    def test_eof_closes_child_and_default_is_bounded(self):
        manual.run(self.root)
        self.assertEqual('stdin_closed', self.outcome()['stop_reason'])
        live = json.loads((self.root / 'live.json').read_text())
        attempt = json.loads((self.root / 'attempt.json').read_text())
        self.assertLessEqual(live['deadline_epoch'] - attempt['started'], 1805)

    def test_detached_ignores_eof_but_obeys_deadline(self):
        manual.run(self.root, minutes=0.01, detached=True)
        self.assertEqual('deadline', self.outcome()['stop_reason'])

    def test_stop_command_closes_detached_session(self):
        errors = []
        def launch():
            try:
                manual.run(self.root, minutes=1, detached=True)
            except BaseException as exc:
                errors.append(exc)
        worker = threading.Thread(target=launch)
        worker.start()
        try:
            limit = time.monotonic() + 5
            while not (self.root / 'live.json').exists() and time.monotonic() < limit:
                time.sleep(0.05)
            receipt = manual.stop(self.root, timeout=5)
            self.assertTrue(receipt['port_released'])
        finally:
            (self.root / 'stop.request').write_text('test cleanup')
            worker.join(timeout=10)
        self.assertFalse(worker.is_alive())
        self.assertFalse(errors)
        self.assertEqual('stop_request', self.outcome()['stop_reason'])

    def test_failure_after_spawn_still_stops_child(self):
        original = manual.save
        def fail_live(path, value):
            if path.name == 'live.json':
                raise OSError('synthetic receipt failure')
            return original(path, value)
        with patch.object(manual, 'save', side_effect=fail_live):
            with self.assertRaisesRegex(OSError, 'synthetic receipt failure'):
                manual.run(self.root)
        self.assertEqual('error', self.outcome()['stop_reason'])

    def test_second_process_cannot_acquire_project_lock(self):
        code = ("import sys; from pathlib import Path; import manual; "
                "\nwith manual.SessionLock(Path(sys.argv[1])): pass")
        lock_path = self.base / 'manual.lock'
        with manual.SessionLock(lock_path):
            result = subprocess.run([sys.executable, '-c', code, str(lock_path)],
                                    cwd=Path(manual.__file__).parent,
                                    capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already running', result.stderr)
        # A stale lock file must not prevent the next session after its owner exits.
        with manual.SessionLock(lock_path):
            pass

    def test_duplicate_run_rejected_before_attempt(self):
        with manual.SessionLock(self.base / '.itemguard-lite-manual.lock'):
            with self.assertRaisesRegex(RuntimeError, 'already running'):
                manual.run(self.root)
        self.assertFalse((self.root / 'attempt.json').exists())

    def test_existing_legacy_session_blocks_new_launch(self):
        other = self.base / 'itemguard-lite-manual-abcdef012345'
        other.mkdir()
        manual.save(other / 'live.json', {'pid': __import__('os').getpid(),
                    'root': str(other), 'port': manual.free()})
        with self.assertRaisesRegex(RuntimeError, 'unfinished session'):
            manual.run(self.root)
        self.assertFalse((self.root / 'attempt.json').exists())

    def test_stop_rejects_outside_namespace(self):
        with self.assertRaisesRegex(RuntimeError, 'namespace'):
            manual.stop(self.base, timeout=0.1)
        self.assertFalse((self.base / 'stop.request').exists())

    def test_cli_detach_requires_explicit_bounded_minutes(self):
        for flags in [['--detach'], ['--detach', '--minutes', '0'],
                      ['--detach', '--minutes', '121']]:
            result = subprocess.run([sys.executable, manual.__file__, 'run', str(self.root), *flags],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(2, result.returncode)
        self.assertFalse((self.root / 'attempt.json').exists())


if __name__ == '__main__':
    unittest.main()
