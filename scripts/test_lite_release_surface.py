"""Source guard for the public read-only LITE command boundary."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class LiteReleaseSurfaceTest(unittest.TestCase):
    def test_no_restore_or_jump_entrypoints(self):
        source = (ROOT / 'src/main/java/com/itemguard/lite/LiteCommand.java').read_text(encoding='utf-8')
        for token in ('handleRestore(', 'jumpToContainer(', 'player.teleport(', '"restore"', 'itemguard.teleport'):
            with self.subTest(token=token):
                self.assertNotIn(token, source)

    def test_no_privileged_mutation_permissions(self):
        descriptor = (ROOT / 'src/main/resources/lite/plugin.yml').read_text(encoding='utf-8')
        for token in ('itemguard.restore', 'itemguard.teleport'):
            self.assertNotIn(token, descriptor)
