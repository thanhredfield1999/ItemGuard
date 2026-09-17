"""Contract tests for the LITE runtime adjudicator's plugin-output filter. No server, no network."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify


class PluginOutputFilterTest(unittest.TestCase):
    """Vanilla broadcasts are not ItemGuard output and must never count as a disclosure."""

    def test_vanilla_join_broadcast_is_not_plugin_output(self):
        self.assertEqual([], verify.plugin_lines(['LiteStaff joined the game']))

    def test_vanilla_leave_and_chat_are_not_plugin_output(self):
        noise = ['LiteStaff left the game', '<LiteStaff> hello', 'LiteStaff fell from a high place']
        self.assertEqual([], verify.plugin_lines(noise))

    def test_prefixed_plugin_message_is_plugin_output(self):
        line = '[ItemGuard LITE] No recorded history found; this is not proof the item does not exist.'
        self.assertEqual([line], verify.plugin_lines([line]))

    def test_numbered_timeline_event_is_plugin_output(self):
        line = '1. First tracked | LiteStaff | world (8, -60, 9) | 2026-09-12 11:51:22 (0s ago)'
        self.assertEqual([line], verify.plugin_lines([line]))

    def test_disclosure_is_detected_only_in_plugin_output(self):
        leaked = ['1. Picked up | LiteStaff | world (1337, 12, -4242) | 2026-09-12 11:51:22 (0s ago)']
        self.assertTrue(verify.discloses_actor(leaked, 'LiteStaff'))

    def test_join_broadcast_alone_is_not_a_disclosure(self):
        self.assertFalse(verify.discloses_actor(['LiteStaff joined the game'], 'LiteStaff'))

    def test_redacted_timeline_is_not_a_disclosure(self):
        redacted = ['1. Picked up | another player (staff only) | location hidden | 2026-09-12 11:51:22 (0s ago)']
        self.assertFalse(verify.discloses_actor(redacted, 'LiteStaff'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
