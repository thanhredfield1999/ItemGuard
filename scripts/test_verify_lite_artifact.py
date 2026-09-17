"""Contract tests for the read-only LITE artifact verifier. No build, server or network."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_lite_artifact as verifier


class StripCommentsTest(unittest.TestCase):
    def test_full_line_comment_is_removed(self):
        text = '# LITE never registers reclaim/withdraw/lost-item commands.\nenabled: true\n'
        self.assertEqual(verifier.strip_comments(text).strip(), 'enabled: true')

    def test_trailing_comment_is_removed_but_value_kept(self):
        text = 'action: NOTIFY # Warnings only; never remove or confiscate items.\n'
        self.assertEqual(verifier.strip_comments(text).strip(), 'action: NOTIFY')

    def test_hash_inside_quoted_value_is_kept(self):
        text = "prefix: '&6[ItemGuard #1] &r'\n"
        self.assertIn('#1', verifier.strip_comments(text))

    def test_active_setting_is_still_visible_to_token_scan(self):
        text = 'reclaim:\n  enabled: true\n'
        self.assertIn('reclaim', verifier.strip_comments(text))


class DescriptorAssertionTest(unittest.TestCase):
    """The verifier must assert the LITE surface, not merely print it."""

    def test_extra_registered_command_is_a_finding(self):
        findings = verifier.check_surface(['itemguard', 'finditem'], verifier.EXPECTED_PERMISSIONS)
        self.assertTrue(any('command' in f for f in findings), findings)

    def test_exact_expected_command_set_passes(self):
        self.assertEqual([], verifier.check_surface(['itemguard'], verifier.EXPECTED_PERMISSIONS))

    def test_history_others_defaulting_to_everyone_is_a_finding(self):
        loosened = dict(verifier.EXPECTED_PERMISSIONS)
        loosened['itemguard.history.others'] = 'true'
        findings = verifier.check_surface(['itemguard'], loosened)
        self.assertTrue(any('itemguard.history.others' in f for f in findings), findings)

    def test_missing_permission_declaration_is_a_finding(self):
        reduced = {k: v for k, v in verifier.EXPECTED_PERMISSIONS.items() if k != 'itemguard.search'}
        findings = verifier.check_surface(['itemguard'], reduced)
        self.assertTrue(any('itemguard.search' in f for f in findings), findings)


class UnexpectedEntryTest(unittest.TestCase):
    """H5: the English-only rule is an allowlist, and it has to actually fire.

    The rule replaced a denylist of two filenames, under which any third language file would have
    shipped in both jars unnoticed. A rule nobody has watched reject anything is not evidence, so
    these cases pin both directions: a new resource is a finding, and real jar content is not.
    """

    def test_a_new_root_resource_is_a_finding(self):
        names = ['plugin.yml', 'config.yml', 'messages_en.yml', 'messages_vi.yml']
        self.assertEqual(['messages_vi.yml'], verifier.unexpected_entries(names))

    def test_a_new_language_directory_is_a_finding(self):
        names = ['plugin.yml', 'config.yml', 'messages_en.yml', 'lang/vi.yml', 'help_zh.txt']
        self.assertEqual(['help_zh.txt', 'lang/vi.yml'], verifier.unexpected_entries(names))

    def test_real_lite_content_is_not_a_finding(self):
        names = [
            'plugin.yml', 'config.yml', 'messages_en.yml', 'sqlite-jdbc.properties',
            'META-INF/MANIFEST.MF', 'META-INF/services/java.sql.Driver',
            'META-INF/maven/org.xerial/sqlite-jdbc/pom.xml',
            'org/sqlite/JDBC.class', 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll',
            'com/itemguard/libs/bstats/bukkit/Metrics.class', 'com/itemguard/lite/ItemGuardLite.class',
            'com/itemguard/', 'org/sqlite/native/',
        ]
        self.assertEqual([], verifier.unexpected_entries(names))

    def test_the_shipped_message_file_is_allowed_but_the_vietnamese_one_is_not(self):
        self.assertEqual([], verifier.unexpected_entries(['messages_en.yml']))
        self.assertEqual(['messages.yml'], verifier.unexpected_entries(['messages.yml']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
