"""Runs the isolated crafting-close lifecycle tests. JDK only: no Paper, no server, no fixture.

The crafting-close cases can only be adjudicated end to end by a real 15-minute Paper run, so the
decisions that run gets wrong when it gets them wrong — which close belongs to which armed case,
whether a terminal path gave its listener back, what a throwing server call means, which slots a
recovery may clear — are isolated in smoke.CraftCloseLifecycle and proven here instead.

Nothing in this gate reads a source file for a string: the class is compiled and its behaviour is
executed, so a probe that merely mentions the right words cannot satisfy it.
"""
import subprocess, unittest
from pathlib import Path

HOME = Path(__file__).resolve().parent
JAVAC = 'C:/Program Files/Java/jdk-21/bin/javac.exe'
JAVA = 'C:/Program Files/Java/jdk-21/bin/java.exe'
# Kept out of build/smoke, which stage() fills with the probe classes it packages into the helper
# jar. These classes are never staged and never shipped.
BUILD = HOME / 'build' / 'lifecycle'

class CraftCloseLifecycleTests(unittest.TestCase):
    def test_crafting_close_lifecycle_behaviour(self):
        BUILD.mkdir(parents=True, exist_ok=True)
        compiled = subprocess.run(
            [JAVAC, '--release', '21', '-d', str(BUILD),
             str(HOME/'CraftCloseLifecycle.java'), str(HOME/'ProbeInitiatedClose.java'),
             str(HOME/'CraftCloseLifecycleTest.java')],
            capture_output=True, text=True)
        self.assertEqual(0, compiled.returncode,
                         'the lifecycle seam does not compile:\n'+compiled.stderr)
        ran = subprocess.run([JAVA, '-cp', str(BUILD), 'smoke.CraftCloseLifecycleTest'],
                             capture_output=True, text=True)
        self.assertEqual(0, ran.returncode,
                         'crafting-close lifecycle behaviour:\n'+ran.stdout+ran.stderr)

if __name__ == '__main__':
    unittest.main()
