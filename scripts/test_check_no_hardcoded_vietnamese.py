"""Tests for the Vietnamese-literal gate, including the bypass it exists to catch.

The gate is a scan, so it can pass for the wrong reason: no files read, a word list that matches
nothing, or an exemption that swallowed the whole tree. These tests plant a literal and require the
gate to fail, and they check the real repository so the gate is actually applied to the code that
ships rather than only to fixtures.

    python -m unittest scripts.test_check_no_hardcoded_vietnamese -v
"""
from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import check_no_hardcoded_vietnamese as api  # noqa: E402
from scripts.check_no_hardcoded_vietnamese import (  # noqa: E402
    BILINGUAL_CLASSES,
    UnparsedClass,
    class_strings,
    scan,
    scan_jar,
)


def api_class(strings: list[tuple[str, str]]) -> bytes:
    """A minimal class file whose constant pool holds the given Utf8 constants.

    Written by hand rather than compiled: the point is to control exactly which strings the scan is
    offered, including the Vietnamese one a compile would put there anyway.
    """
    pool = []
    for literal, _ in strings:
        raw = literal.encode("utf-8")
        pool.append(bytes([1]) + len(raw).to_bytes(2, "big") + raw)
    body = b"".join(pool)
    header = b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + (65).to_bytes(2, "big")
    return header + (len(strings) + 1).to_bytes(2, "big") + body

REPO = Path(__file__).resolve().parents[1]


class VietnameseLiteralGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_a_literal_with_diacritics_and_no_flag_is_a_finding(self):
        self.write("com/itemguard/Thing.java", 'message("Nguồn plugin: chưa xác định");\n')
        findings, checked = scan(self.root)
        self.assertEqual(1, checked)
        self.assertEqual(1, len(findings))
        self.assertEqual("diacritics", findings[0][3])

    def test_a_literal_without_diacritics_and_no_flag_is_a_finding(self):
        """The banner that shipped: ASCII only, invisible to a non-ASCII scan."""
        self.write("com/itemguard/Thing.java", 'log("ItemGuard da kich hoat!");\n')
        findings, _ = scan(self.root)
        self.assertEqual(1, len(findings))
        self.assertTrue(findings[0][3].startswith("word"))

    def test_the_same_literal_behind_the_language_flag_is_allowed(self):
        self.write(
            "com/itemguard/Thing.java",
            'private String text(String english, String vietnamese) {\n'
            '    return isVietnamese() ? vietnamese : english;\n'
            '}\n'
            'String greeting = text("Enabled", "Đã bật");\n',
        )
        findings, _ = scan(self.root)
        self.assertEqual([], findings)

    def test_full_only_directories_are_exempt_and_the_scan_still_reads_others(self):
        self.write("com/itemguard/catalog/Thing.java", 'log("Chưa có dữ liệu");\n')
        self.write("com/itemguard/services/Thing.java", 'log("Xin chao");\n')
        findings, checked = scan(self.root)
        self.assertEqual(1, checked, "the exempt file must not be counted as scanned")
        self.assertEqual(["com/itemguard/services/Thing.java"],
                         [f[0].relative_to(self.root).as_posix() for f in findings])

    def test_an_english_jar_cannot_pass_by_reading_nothing(self):
        """No file read means the gate proved nothing, so the count is reported and asserted."""
        findings, checked = scan(self.root)
        self.assertEqual(([], 0), (findings, checked))

    def test_the_shipping_source_tree_has_no_unflagged_vietnamese_literal(self):
        findings, checked = scan(REPO / "src" / "main" / "java")
        self.assertGreater(checked, 100, "the scan must actually read the source tree")
        self.assertEqual(
            [], [(f[0].name, f[1], f[2]) for f in findings],
            "a Vietnamese literal with no language flag in front of it would be printed verbatim "
            "on an English-only LITE server",
        )

    def test_the_jar_scan_reports_vietnamese_string_constants(self):
        """The artifact check: a class that prints Vietnamese has to carry the string."""
        import zipfile

        class_bytes = api_class([("Xin chào từ class", "Ljava/lang/String;")])
        jar = self.root / "fake.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("com/itemguard/Thing.class", class_bytes)
        findings, checked, _ = scan_jar(jar)
        self.assertEqual(1, checked)
        self.assertEqual(["com/itemguard/Thing.class"],
                         [f[0] for f in findings if "absent from the jar" not in f[1]])

    def test_the_jar_scan_does_not_fire_on_random_bytes(self):
        """The first version of this scan matched Latin-1 characters that random byte pairs happen
        to produce and reported 28 classes, 20 of them inside the shaded sqlite driver."""
        import os
        import zipfile

        jar = self.root / "noise.jar"
        # A real class header followed by bytes that are not a constant pool.
        noise = b"\xca\xfe\xba\xbe\x00\x00\x00\x41\x00\x03" + os.urandom(4096)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("com/itemguard/Noise.class", noise)
        findings, _, _ = scan_jar(jar)
        # The class cannot be parsed, so it is reported as unparsed - which is the point: the failure
        # has to be visible rather than a silent skip. What it must never claim is a Vietnamese
        # string it did not find.
        self.assertEqual(
            [why for _, why in findings if why.startswith("diacritics") or why.startswith("word")],
            [])

    def test_a_known_bilingual_class_is_skipped_and_an_undeclared_one_is_not(self):
        import zipfile

        jar = self.root / "bilingual.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for name in BILINGUAL_CLASSES:
                archive.writestr(name, api_class([("Nhặt lên", "Ljava/lang/String;")]))
            archive.writestr("com/itemguard/New.class", api_class([("Nhặt lên", "Ljava/lang/String;")]))
        findings, checked, skipped = scan_jar(jar)
        self.assertEqual(1, checked)
        self.assertEqual(len(BILINGUAL_CLASSES), skipped["bilingual"])
        self.assertEqual(["com/itemguard/New.class"],
                         [f[0] for f in findings if "absent from the jar" not in f[1]],
                         "a sixth bilingual class must be declared on purpose, not slip in")

    def test_relocated_library_classes_are_skipped_and_itemguard_classes_are_not(self):
        """The Premium shaded jar carries Connector/J's protobuf descriptors under the relocation
        prefix; their proto byte strings read as diacritics, and no vendored class can be moved behind
        the language flag. The skip is a declared prefix with its own bucket, not a directory guess."""
        import zipfile

        jar = self.root / "relocated.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(
                "com/itemguard/libs/mysql/cj/x/protobuf/MysqlxSession.class",
                api_class([("proto bytes", "Ljava/lang/String;")]))
            archive.writestr("com/itemguard/Thing.class",
                             api_class([("Nhặt lên", "Ljava/lang/String;")]))
        findings, checked, skipped = scan_jar(jar)
        self.assertEqual(1, skipped["relocated"], "the vendored class must land in the named bucket")
        self.assertEqual(1, checked, "only the vendored class may be skipped")
        self.assertEqual(["com/itemguard/Thing.class"],
                         [f[0] for f in findings if "absent from the jar" not in f[1]])

    def test_the_relocated_prefix_matches_the_shade_configuration(self):
        """A new or wider relocation must not silently enlarge the skip: every `<shadedPattern>` in
        pom.xml has to fall under a declared prefix, and no prefix may sit outside the namespace."""
        import xml.etree.ElementTree as ET

        root = ET.parse(REPO / "pom.xml").getroot()
        # The POM is namespaced, so the tags come back as `{http://maven.apache.org/POM/4.0.0}...`.
        targets = [element.text.strip() for element in root.iter()
                   if element.tag.endswith("shadedPattern")
                   and element.text and element.text.strip()]
        self.assertGreater(len(targets), 0, "pom.xml has no relocation to check against")
        for target in targets:
            with self.subTest(target=target):
                normalized = target.replace(".", "/") + "/"
                self.assertTrue(
                    any(normalized.startswith(prefix) for prefix in api.RELOCATED_LIBRARY_PREFIXES),
                    f"{target} is not covered by a declared relocated-library prefix")
        for prefix in api.RELOCATED_LIBRARY_PREFIXES:
            with self.subTest(prefix=prefix):
                self.assertTrue(prefix.startswith("com/itemguard/libs/"),
                                "the skip may not be widened outside the relocation namespace")

    def test_the_artifact_scan_namespace_covers_every_product_source_file(self):
        """The jar pass adjudicates `com/itemguard/**` (minus relocated libraries). Every file that
        ships has to live there, or a new package would be treated as vendored without anyone
        deciding that it is."""
        source = REPO / "src" / "main" / "java"
        strays = sorted(
            path.relative_to(source).as_posix()
            for path in source.rglob("*.java")
            if not path.relative_to(source).as_posix().startswith("com/itemguard/")
        )
        self.assertEqual(
            [], strays,
            "product code outside com/itemguard/ would be skipped by the artifact scan")

    def test_the_shipping_jar_carries_no_undeclared_vietnamese_string(self):
        """The real artifact, not the tree: this is the check that would have caught C2."""
        findings, checked, skipped = scan_jar(
            REPO / "release" / "spigot-upload" / "ItemGuard-LITE-1.0.0.jar")
        self.assertGreater(checked, 200, "the jar scan must actually read the class entries")
        self.assertEqual([], [(f[0], f[1]) for f in findings])
        # H1 (review #3): every class entry is either scanned or in a named bucket. Counting them
        # this way is what makes a silent skip visible instead of a smaller `checked`.
        jar = REPO / "release" / "spigot-upload" / "ItemGuard-LITE-1.0.0.jar"
        with zipfile.ZipFile(jar) as archive:
            entries = [n for n in archive.namelist() if n.endswith(".class")]
        self.assertEqual(len(entries),
                         checked + skipped["exempt"] + skipped["bilingual"]
                         + skipped["relocated"] + skipped["thirdparty"],
                         "a class entry that is neither scanned nor accounted for")

    def test_the_jar_scan_reads_a_declared_file_inside_an_exempt_directory(self):
        """H1 (review #3): `commands/ItemCodeInput` is declared SCANNED in the source pass because
        `LiteCommand` calls it, while the jar pass skipped the whole directory and reported 0."""
        import zipfile

        declared = "com/itemguard/commands/ItemCodeInput.class"
        self.assertIn("com/itemguard/commands/ItemCodeInput.java", api.SCANNED_INSIDE_EXEMPT)
        jar = self.root / "declared.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(declared, api_class([("Khong the xu ly ma", "Ljava/lang/String;")]))
            archive.writestr("com/itemguard/commands/Other.class",
                             api_class([("Khong the xu ly ma", "Ljava/lang/String;")]))
        findings, checked, skipped = scan_jar(jar)
        self.assertEqual([declared],
                         [f[0] for f in findings if "absent from the jar" not in f[1]],
                         "the declared file is scanned; its exempt neighbour still is not")
        self.assertEqual(1, checked)
        self.assertEqual(1, skipped["exempt"])

    def test_a_declared_bilingual_class_has_to_exist_and_still_carry_vietnamese(self):
        """The declaration cannot rot: a renamed class used to keep its exemption for free."""
        import zipfile

        missing = self.root / "missing.jar"
        with zipfile.ZipFile(missing, "w") as archive:
            archive.writestr("com/itemguard/Unrelated.class", api_class([("plain", "x")]))
        findings, _, _ = scan_jar(missing)
        self.assertEqual(
            len(BILINGUAL_CLASSES),
            len([f for f in findings if f[1].endswith("absent from the jar")]),
            "every declared bilingual class has to be in the artifact")

        stale = self.root / "stale.jar"
        with zipfile.ZipFile(stale, "w") as archive:
            for name in BILINGUAL_CLASSES:
                archive.writestr(name, api_class([("all english here", "x")]))
        findings, _, _ = scan_jar(stale)
        self.assertEqual(
            len(BILINGUAL_CLASSES),
            len([f for f in findings if "declaration is stale" in f[1]]),
            "a bilingual class with no Vietnamese left is a stale declaration, not a pass")

    def test_a_referenced_file_inside_an_exempt_directory_has_to_be_declared(self):
        """The hole this check closes: `commands/ItemCodeInput` is exempt by directory, imported by
        `LiteCommand`, and was the one file in the tree carrying an undeclared Vietnamese literal."""
        self.write("com/itemguard/commands/Lonely.java", 'throw new IllegalStateException("Bắt buộc");\n')
        self.write("com/itemguard/lite/User.java", "import com.itemguard.commands.Lonely;\n")
        with mock.patch.object(api, "EXEMPT_DIRS", ("com/itemguard/commands",)), \
                mock.patch.object(api, "SCANNED_INSIDE_EXEMPT", {}), \
                mock.patch.object(api, "SKIPPED_INSIDE_EXEMPT", {}):
            referenced = api.referenced_from_outside(self.root)
            self.assertEqual({"com/itemguard/commands/Lonely.java"}, referenced)
            # Undeclared: scan() skips the directory, which is exactly why the CLI's declaration
            # check has to exist - a scan alone would have reported zero findings here.
            findings, checked = api.scan(self.root)
            self.assertEqual([], [f for f in findings])
            self.assertEqual(1, checked, "only the file outside the exempt directory is scanned")

        with mock.patch.object(api, "EXEMPT_DIRS", ("com/itemguard/commands",)), \
                mock.patch.object(api, "SCANNED_INSIDE_EXEMPT",
                                  {"com/itemguard/commands/Lonely.java": "reached by LiteCommand"}), \
                mock.patch.object(api, "SKIPPED_INSIDE_EXEMPT", {}):
            findings, checked = api.scan(self.root)
            self.assertEqual(2, checked, "the declared file is scanned too")
            self.assertEqual(["com/itemguard/commands/Lonely.java"],
                             [f[0].relative_to(self.root).as_posix() for f in findings])

    def test_every_referenced_file_in_the_real_tree_is_declared(self):
        """The gate's own guard, applied to the shipping tree."""
        referenced = api.referenced_from_outside(REPO / "src" / "main" / "java")
        declared = set(api.SCANNED_INSIDE_EXEMPT) | set(api.SKIPPED_INSIDE_EXEMPT)
        self.assertEqual(set(), referenced - declared,
                         "a file inside an exempt directory is referenced from outside and no "
                         "declaration says whether LITE can reach it")

    def test_the_declared_files_exist(self):
        for name in list(api.SCANNED_INSIDE_EXEMPT) + list(api.SKIPPED_INSIDE_EXEMPT):
            self.assertTrue((REPO / "src" / "main" / "java" / name).is_file(),
                            f"declared file is gone: {name} - a rename must break this test rather "
                            f"than silently narrow the gate")

    def test_a_method_handle_entry_does_not_hide_the_rest_of_the_pool(self):
        """H-1 of the second review. `CONSTANT_MethodHandle` (15) was missing from the parser, and it
        returned early instead of complaining: on the real `ItemGuard.class` that meant 82 Utf8
        constants read out of 338 — the gate was checking a quarter of every class and reporting
        success."""
        method_handle = bytes([15, 6, 0, 7])  # tag, reference kind, two-byte reference index
        pool = (bytes([1]) + len(b"first").to_bytes(2, "big") + b"first"
                + method_handle
                + bytes([1]) + len("Nhặt lên".encode()).to_bytes(2, "big") + "Nhặt lên".encode())
        header = b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + (65).to_bytes(2, "big")
        data = header + (4).to_bytes(2, "big") + pool
        self.assertIn("Nhặt lên", class_strings(data))

    def test_an_unparseable_class_is_a_finding_not_a_silent_skip(self):
        import zipfile

        header = b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + (65).to_bytes(2, "big")
        broken = header + (2).to_bytes(2, "big") + bytes([99]) + b"\x00\x00\x00\x00"
        jar = self.root / "broken.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("com/itemguard/Broken.class", broken)
        findings, _, _ = scan_jar(jar)
        unparsed = [f for f in findings if f[1].startswith("unparsed")]
        self.assertEqual(["com/itemguard/Broken.class"], [f[0] for f in unparsed])
        self.assertIn("unparsed class", unparsed[0][1])

    def test_the_parser_reads_a_real_class_to_the_end(self):
        """The strength check the first version would have failed: a real class must yield its whole
        constant pool, not the fragment in front of the first lambda."""
        import zipfile

        with zipfile.ZipFile(REPO / "release" / "spigot-upload" / "ItemGuard-LITE-1.0.0.jar") as archive:
            strings = class_strings(archive.read("com/itemguard/ItemGuard.class"))
        self.assertGreater(len(strings), 300,
                           "ItemGuard.class holds 338 Utf8 constants per javap -v")

    def test_a_literal_selected_by_the_flag_in_its_own_statement_is_allowed(self):
        self.write("com/itemguard/Thing.java",
                   'String s = isVietnamese() ? "Đã bật" : "Enabled";\n')
        findings, _ = scan(self.root)
        self.assertEqual([], findings)

    def test_a_literal_inside_a_flag_carrying_block_is_allowed(self):
        self.write("com/itemguard/Thing.java",
                   'if (vietnamese) {\n'
                   '    lines.add("Đã ghi nhận " + transfers + " lần đổi tay.");\n'
                   '    lines.add("Tự vứt ra rồi nhặt lại không được tính.");\n'
                   '} else {\n'
                   '    lines.add("Recorded handovers.");\n'
                   '}\n')
        findings, _ = scan(self.root)
        self.assertEqual([], findings)

    def test_a_bare_vietnamese_message_with_no_english_side_is_a_finding(self):
        """The hole the file-level rule left: this statement sits in `LiteCommand`, which has the
        flag somewhere else, so the whole file used to be exempt."""
        self.write("com/itemguard/Thing.java",
                   'private boolean isVietnamese() { return false; }\n'
                   'void go() { sender.sendMessage("Khong tim thay vat pham"); }\n')
        findings, _ = scan(self.root)
        self.assertEqual(["com/itemguard/Thing.java"],
                         [f[0].relative_to(self.root).as_posix() for f in findings])

    def test_the_chinese_message_file_is_still_shipped_so_the_zh_path_is_deliberate(self):
        """M3 (review 2026-09-17) records that `language: zh` resolves to Vietnamese; the file
        existing is what makes that a silent wrong answer rather than a missing file."""
        self.assertTrue((REPO / "src/main/resources/messages_zh.yml").is_file())


if __name__ == "__main__":
    unittest.main()
