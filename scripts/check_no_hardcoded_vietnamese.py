"""Fail when a Vietnamese literal can reach a player in a jar advertised as English only.

`MessageManager.DEFAULT_MESSAGES` was fixed in September (H5) so the *fallback map* is English. That
fix could not see literals compiled straight into a class, and two of them shipped while the listing
said "English": the startup banner and the craft refusal (C2, review 2026-09-17). The packaging gate
`package_lite.py` allowlists resource *file names* at the root of the jar; it never reads a `.class`.
This is the check that reads the code.

The rule is not "no Vietnamese anywhere". FULL supports Vietnamese, and the sanctioned way to write
bilingual text here is the language flag: `text(english, vietnamese)` / `vietnamese ? A : B`, which
LITE resolves to English always (`MessageLanguagePolicy`). So a file may contain Vietnamese literals
only if it also contains that flag. A literal with no flag next to it is a string that LITE will
print verbatim.

    python scripts/check_no_hardcoded_vietnamese.py
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "src" / "main" / "java"

# FULL-only surfaces: not reachable from `ItemGuardLite`. They still carry Vietnamese literals and
# that is recorded debt (M3/M6, review 2026-09-17) to clear in the FULL pass — the file names are
# listed here rather than the problem being hidden.
EXEMPT_DIRS = (
    "com/itemguard/catalog",
    "com/itemguard/commands",
    "com/itemguard/gui",
)

# A directory is not proof of unreachability, and this list is the correction. LITE imports
# `commands/ItemCodeInput` directly (`LiteCommand.normalize`), so a Vietnamese string added there
# would have been exempted by the directory rule and shipped. Anything inside an exempt directory that
# some file *outside* it references has to be declared here as one of:
#
#   * SCANNED - LITE can reach it, so its literals are held to the LITE rule
#   * SKIPPED - referenced, but provably not reachable by a LITE player, with the reason
#
# and an undeclared reference is a finding. That check is what makes the exemption honest instead of
# a directory-sized hole.
SCANNED_INSIDE_EXEMPT = {
    "com/itemguard/commands/ItemCodeInput.java":
        "LiteCommand calls normalize(); its exception text is caught there, so this is scanned "
        "rather than exempted in case it ever stops being caught",
    "com/itemguard/catalog/CatalogText.java":
        "runs on BOTH editions: SqliteConnectionOwner.registerIndexFunction is not edition-guarded, "
        "so 'it holds no player text today' has to be checked rather than assumed",
}

# `import com.itemguard.commands.*` in ItemGuard.java makes every file in that package 'referenced',
# and the reason each one cannot reach a LITE player is the same: `ItemGuard.registerCommands()` is
# the only thing that instantiates them, and `ItemGuardLite.registerCommands()` overrides it to
# install `LiteCommand` alone. Listing them one by one is the point — a new command class has to be
# declared deliberately rather than inheriting the package's exemption.
_SKIPPED_COMMANDS = "registered only by ItemGuard.registerCommands(), which ItemGuardLite overrides"
_SKIPPED_COMMAND_HELPER = "helper of those command classes; nothing outside commands/ references it"
SKIPPED_INSIDE_EXEMPT = {
    "com/itemguard/catalog/CatalogRepository.java":
        "constructed by DatabaseManager for both editions, but getCatalog() has exactly one caller, "
        "CatalogUi, which is built only when !isLiteEdition; its query text cannot reach LITE",
    "com/itemguard/catalog/CatalogUi.java":
        "constructed only when !isLiteEdition (ItemGuard.registerListeners)",
    "com/itemguard/gui/FilterChatListener.java":
        "registered only when !isLiteEdition",
    "com/itemguard/gui/GUIListener.java":
        "registered only when !isLiteEdition",
    "com/itemguard/commands/CheckCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/HistoryCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/MainCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/MatDoCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/SearchCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/StatsCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/FindItemCommand.java": _SKIPPED_COMMANDS,
    "com/itemguard/commands/FindItemCommandAction.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/FindItemCommandParser.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/FindItemCommandTask.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/MatDoCommandAction.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/MatDoCommandParser.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/HistoryAccessPolicy.java": _SKIPPED_COMMAND_HELPER,
    "com/itemguard/commands/SubcommandArguments.java": _SKIPPED_COMMAND_HELPER,
}

# How a statement shows that its Vietnamese literal has an English counterpart reachable at
# runtime. Four shapes exist in this codebase, and all four are accepted:
#
#   return isVietnamese() ? "VI" : "EN";            the flag itself
#   case "X" -> vietnamese                          the flag on the next line (regex allows the break)
#       ? "VI" : "EN";
#   if (vietnamese) { ... "VI" ... } else { ... }   a block whose *header* carries the flag
#   new Element("BOOK", "EN", List.of("VI"))        an English literal beside the Vietnamese one
#   message(sender, "EN", "VI")                     the project's own bilingual helpers
#
# The last two are covered by "an English literal in the same statement", which is why the helper
# names are deliberately NOT in this pattern: `message("VI only")` with one argument is a bare
# Vietnamese string, and matching the call name would have cleared it.
#
# Still not provable: that the English branch is actually reachable, or that a helper really compares
# the two arguments. This narrows the hole from "the whole file" (the first version) to "the
# statement", and says so rather than claiming the check is complete.
LANGUAGE_FLAG = re.compile(r"isVietnamese\(\)|vietnamese\s*\?|\bvietnamese\b")

# Comments are blanked before the flag is searched (M4, review #3), and a companion literal has to
# look like a sentence rather than a key, a colour code or a punctuation mark.
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)
LETTERS = re.compile(r"[^\W\d_]", re.UNICODE)
COLOUR = re.compile(r"^[&§][0-9a-fk-orA-FK-OR]")
# `messages.foo`, `tracking.cancel-untracked-craft-output`, `anti-dupe`: keys, not prose.
KEY_LIKE = re.compile(r"^[a-z][a-z0-9]*([._-][a-z0-9]+)+$")

# Classes allowed to carry Vietnamese *inside the jar*, because they carry the English counterpart
# beside it and select via the flag above (`isVietnamese()` / a `vietnamese` parameter), which LITE
# always resolves to English. Declared rather than inferred: a jar cannot show which branch runs, so
# the honest form is "these five are known bilingual, and a sixth is a finding until someone puts its
# name here on purpose". The source scan is what proves each one still has its flag.
BILINGUAL_CLASSES = (
    "com/itemguard/custody/CustodyPresentation.class",
    "com/itemguard/lite/LiteCommand.class",
    "com/itemguard/lite/LiteHistoryView.class",
    "com/itemguard/lite/LiteMenuChrome.class",
    "com/itemguard/restore/LossReason.class",
)

# Vietnamese with diacritics, as codepoint ranges: Latin-1 letters used by Vietnamese plus the
# Latin Extended-A/B and combining marks the language needs.
DIACRITICS = re.compile(r"[\u00c0-\u01b0\u1ea0-\u1ef9\u0300-\u0323]")

# Vietnamese written without diacritics, which the diacritic scan cannot see. Whole words that are
# not English: kept deliberately short and specific so an English string cannot trip it.
WORDS = (
    "khong", "duoc", "chua", "nguoi", "nhung", "cua", "truoc", "lenh",
    "vat pham", "kiem tra", "xac minh", "hoan tat", "tam tu", "the gioi",
    "nhieu", "ban sao", "vi tri", "hanh dong", "phat hien", "bao staff",
    "cam vat pham", "da kich hoat", "da tat", "giu fail-closed", "de reconcile",
    "xin chao", "thong bao", "canh bao", "tra loi", "khong the", "chua the",
    "nguoi choi",
)
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
WORD = re.compile(r"\b(" + "|".join(re.escape(w) for w in WORDS) + r")\b", re.IGNORECASE)


def statements(text: str):
    """(start offset, chunk) pieces split on `;`, `{` and `}`.

    Statement-sized rather than line-sized: the bilingual form this project uses is often a ternary
    that wraps across lines, and a line-sized rule would flag the Vietnamese branch of every one of
    them. The first version of this gate exempted the whole *file* instead, which is how
    `LiteCommand.java` — the entire LITE command surface — came to be exempt from the check that LITE
    is English only.

    M4 (review #3): a separator inside a string literal is not a separator. The old version split on
    every `;{}`, so a literal containing one was cut in half - and half a literal no longer matches
    STRING_LITERAL, which removed it from every check downstream without a word.
    """
    pieces = []
    start = 0
    in_string = False
    escaped = False
    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            continue
        if char in ";{}":
            pieces.append((start, text[start:index], char))
            start = index + 1
    pieces.append((start, text[start:], ""))
    return pieces


def without_comments(chunk: str) -> str:
    """The chunk with `//` and `/* */` comments blanked out.

    M4 (review #3): the language flag was searched in the raw chunk, so a comment saying
    "keep the vietnamese branch" cleared the Vietnamese literal on the next line.
    """
    return COMMENT.sub(" ", chunk)


def looks_like_english_sentence(literal: str) -> bool:
    """Whether a literal beside a Vietnamese one is plausibly its English counterpart.

    M4 (review #3): any second literal used to count, so a config key
    (`getString("messages.foo", "Khong tim thay")`), a colour code (`"§c" + "Khong the ..."`) or a
    punctuation mark cleared the finding. Those three are what is excluded here; everything else with
    at least two letters counts, because a single-word counterpart ("Enabled" beside "Đã bật") is a
    real translation and an earlier version of this rule flagged it.

    Still not provable: that the companion is the *translation* of the Vietnamese literal rather than
    any other prose string in the same statement, and a single-word config key as the second argument
    of `getString(key, fallback)` would still clear it.
    """
    stripped = literal.strip()
    if len(LETTERS.findall(stripped)) < 2:
        return False
    return not (COLOUR.match(stripped) or KEY_LIKE.match(stripped))


def is_vietnamese(literal: str) -> str | None:
    """Why this literal looks Vietnamese, or None."""
    if DIACRITICS.search(literal):
        return "diacritics"
    match = WORD.search(literal)
    return "word " + match.group(0) if match else None


def scan_file(path: Path) -> list[tuple[int, str, str]]:
    """(line number, the literal, which signal saw it) for every Vietnamese string literal.

    A literal is only cleared if the statement it lives in also carries the language flag, which is
    what makes the English counterpart reachable at runtime.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    findings = []
    # A literal inside `if (vietnamese) { ... }` is selected by its block header, not by its own
    # statement, so the flag has to be inherited down the block. The stack tracks whether an
    # enclosing block was opened by a flag-carrying header; `}` pops it.
    inside_flagged_block = [False]
    for start, chunk, separator in statements(text):
        flagged = []
        english_beside_it = False
        code = without_comments(chunk)
        for literal in STRING_LITERAL.findall(chunk):
            why = is_vietnamese(literal)
            if why:
                flagged.append((literal, why))
            elif looks_like_english_sentence(literal):
                english_beside_it = True
        if flagged:
            cleared = (bool(LANGUAGE_FLAG.search(code))
                       or inside_flagged_block[-1]
                       or english_beside_it)
            if not cleared:
                line = text.count("\n", 0, start) + 1
                findings.extend((line, literal, why) for literal, why in flagged)
        if separator == "{":
            inside_flagged_block.append(
                bool(LANGUAGE_FLAG.search(without_comments(chunk))) or inside_flagged_block[-1])
        elif separator == "}" and len(inside_flagged_block) > 1:
            inside_flagged_block.pop()
    return findings


def referenced_from_outside(source: Path) -> set[str]:
    """Files inside an exempt directory that a file outside it names.

    The heuristic behind the declaration lists. It over-reports on purpose: a name mentioned anywhere
    outside the directory counts, whether or not the edition guard makes it unreachable. Over-
    reporting costs a line in `SKIPPED_INSIDE_EXEMPT`; under-reporting costs a Vietnamese string in
    the jar, which is the whole point of this file.
    """
    outside, inside = [], []
    for path in sorted(source.rglob("*.java")):
        relative = path.relative_to(source).as_posix()
        (inside if any(relative.startswith(e) for e in EXEMPT_DIRS) else outside).append((relative, path))
    references = set()
    wildcard_packages = set()
    outside_text = []
    for _, path in outside:
        text = path.read_text(encoding="utf-8", errors="replace")
        outside_text.append(text)
        references |= set(re.findall(r"com\.itemguard\.[\w.$]+", text))
        references |= set(re.findall(r"^import\s+(?:static\s+)?([\w.]+);", text, re.M))
        # `import com.itemguard.commands.*;` — the [\w.]+ pattern above stops at the `*`, so the whole
        # package used to go unexamined. That is how the largest exempt directory (seven command
        # classes full of hard-coded Vietnamese) was never asked the question this function exists to
        # ask, while the check below reported success.
        wildcard_packages |= set(re.findall(r"^import\s+(?:static\s+)?([\w.]+)\.\*;", text, re.M))
    hit = set()
    for relative, _ in inside:
        name = relative[:-5].replace("/", ".")
        package = name.rsplit(".", 1)[0]
        simple = name.rsplit(".", 1)[-1]
        if package in wildcard_packages:
            hit.add(relative)
            continue
        if name in references or any(r.startswith(name + ".") for r in references) \
                or any(r.endswith("." + simple) for r in references):
            hit.add(relative)
            continue
        # Bare names: `new CheckCommand(this)` with a wildcard import, or the same package. Matching
        # the class name as a word anywhere outside is deliberately loose - it over-reports, and an
        # over-reported file costs one declaration line.
        if re.search(rf"\b{re.escape(simple)}\b", "\n".join(outside_text)):
            hit.add(relative)
    return hit


def scan(source: Path) -> tuple[list[tuple[Path, int, str, str]], int]:
    """Findings plus how many files were read, so a silent zero can be told from a broken scan."""
    findings = []
    checked = 0
    for path in sorted(source.rglob("*.java")):
        relative = path.relative_to(source).as_posix()
        exempt = any(relative.startswith(e) for e in EXEMPT_DIRS)
        if exempt and relative not in SCANNED_INSIDE_EXEMPT:
            continue
        checked += 1
        flagged = scan_file(path)
        findings.extend((path, number, literal, why) for number, literal, why in flagged)
    return findings, checked


TAG_UTF8 = 1
TAG_INTEGER = 3
TAG_FLOAT = 4
TAG_LONG = 5
TAG_DOUBLE = 6
TAG_METHOD_HANDLE = 15
TWO_BYTE = {7, 8, 16, 19, 20}
FOUR_BYTE = {9, 10, 11, 12, 17, 18}
EIGHT_BYTE = {TAG_LONG, TAG_DOUBLE}


class UnparsedClass(Exception):
    """Raised when the constant pool cannot be walked to the end.

    Not a detail: the first version of `class_strings` returned whatever it had collected when it met
    a tag it did not know, and it did not know `CONSTANT_MethodHandle` (15) — the tag javac emits for
    every lambda, method reference and non-constant string concatenation. On `ItemGuard.class` that
    meant 82 Utf8 constants read out of 338 (`javap -v`), while `scan_jar` counted the class as
    checked and reported zero findings. A gate that cannot see two thirds of a class is the defect it
    was written to catch.
    """


def class_strings(data: bytes) -> list[str]:
    """Every Utf8 constant in a class file — the strings the class can actually print.

    Parsing the constant pool rather than scanning the raw bytes is not fussiness: the first version
    of this function decoded the whole entry leniently and matched Latin-1 characters that random
    byte pairs happen to produce, so it reported 28 classes in this jar as Vietnamese, 20 of them
    inside the shaded sqlite driver. A gate that fires on `org/sqlite/core/DB.class` is a gate nobody
    reads.
    """
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return []
    count = int.from_bytes(data[8:10], "big")
    strings = []
    offset = 10
    index = 1
    while index < count and offset < len(data):
        tag = data[offset]
        offset += 1
        if tag == TAG_UTF8:
            length = int.from_bytes(data[offset:offset + 2], "big")
            offset += 2
            strings.append(data[offset:offset + length].decode("utf-8", errors="replace"))
            offset += length
        elif tag in EIGHT_BYTE:
            offset += 8
            index += 1  # takes two constant-pool slots
        elif tag == TAG_METHOD_HANDLE:
            offset += 3
        elif tag in FOUR_BYTE:
            offset += 4
        elif tag in TWO_BYTE:
            offset += 2
        elif tag in (TAG_INTEGER, TAG_FLOAT):
            offset += 4
        else:
            raise UnparsedClass(f"unknown constant-pool tag {tag} at offset {offset - 1}")
        index += 1
    if index < count:
        raise UnparsedClass(f"pool ended early: read {index - 1} of {count - 1} entries")
    return strings


def declared_lite_reachable(class_name: str) -> bool:
    """Whether the source behind this class is declared reachable from LITE.

    A class inside an exempt directory was skipped here whatever the source gate said, so
    `commands/ItemCodeInput.class` - declared SCANNED there precisely because `LiteCommand` calls it -
    was the one file the artifact pass could not see (H1, review #3). Inner classes are matched
    through their outer name, because `Foo$Bar.class` is emitted from `Foo.java`.
    """
    source = class_name[: -len(".class")].split("$")[0] + ".java"
    return source in SCANNED_INSIDE_EXEMPT


def scan_jar(jar: Path) -> tuple[list[tuple[str, str]], int, dict[str, int]]:
    """The same rule applied to the built jar, because a source scan is not the artifact.

    The checks that missed C2 read the tree, while the buyer receives a jar: `package_lite.py`
    allowlists resource *file names* and nothing inspected `.class` content (IG-R020). This reads the
    string constants of every class the LITE edition can reach.
    """
    findings = []
    checked = 0
    skipped = {"exempt": 0, "bilingual": 0}
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        for name in sorted(names):
            if not name.endswith(".class"):
                continue
            if name in BILINGUAL_CLASSES:
                skipped["bilingual"] += 1
                # Not adjudicated here: whether a bilingual class prints its Vietnamese branch is
                # decided by a flag at runtime, and bytecode cannot show which branch runs. The
                # source pass and the per-class flag assertions cover that. What *is* checked is that
                # the declaration cannot rot - the class has to exist and still carry Vietnamese, so
                # a renamed or de-vietnamised class cannot keep the exemption for free.
                try:
                    strings = class_strings(archive.read(name))
                except UnparsedClass as failure:
                    findings.append((name, f"unparsed class: {failure}"))
                    continue
                if not any(DIACRITICS.search(literal) or WORD.search(literal) for literal in strings):
                    findings.append((name, "declared bilingual but no longer carries a Vietnamese "
                                           "literal, so the declaration is stale"))
                continue
            if any(name.startswith(exempt + "/") for exempt in EXEMPT_DIRS) \
                    and not declared_lite_reachable(name):
                skipped["exempt"] += 1
                continue
            checked += 1
            try:
                strings = class_strings(archive.read(name))
            except UnparsedClass as failure:
                # A class this cannot read is a class it cannot clear, so it is a finding rather
                # than a skip. The silent version of this line is what hid two thirds of every
                # class in the jar.
                findings.append((name, f"unparsed class: {failure}"))
                continue
            for literal in strings:
                if DIACRITICS.search(literal):
                    findings.append((name, "diacritics: " + literal[:60]))
                    break
                match = WORD.search(literal)
                if match:
                    findings.append((name, "word " + match.group(0) + ": " + literal[:60]))
                    break
        for declared in BILINGUAL_CLASSES:
            if declared not in names:
                findings.append((declared, "declared bilingual but absent from the jar"))
    return findings, checked, skipped


def main() -> int:
    if "--jar" in sys.argv:
        jar = Path(sys.argv[sys.argv.index("--jar") + 1])
        if not jar.is_file():
            print(f"no jar to scan: {jar}", file=sys.stderr)
            return 2
        findings, checked, skipped = scan_jar(jar)
        print(f"jar {jar.name}: scanned {checked} class entries; skipped {skipped['exempt']} in "
              f"FULL-only directories, {skipped['bilingual']} known-bilingual classes")
        print(f"class files carrying Vietnamese to a player: {len(findings)}")
        for name, why in findings:
            print(f"  {name}  [{why}]")
        if findings:
            print("\nA class in this jar prints Vietnamese. LITE resolves the language to English "
                  "always, so this reaches the player. Move the string behind the language flag or "
                  "into messages_en.yml.")
            return 1
        return 0

    if not SOURCE.is_dir():
        print(f"no source to scan: {SOURCE}", file=sys.stderr)
        return 2
    undeclared = referenced_from_outside(SOURCE) - set(SCANNED_INSIDE_EXEMPT) - set(SKIPPED_INSIDE_EXEMPT)
    findings, checked = scan(SOURCE)
    if undeclared:
        print("files inside an exempt directory that something outside it references, and that no "
              "declaration covers:")
        for name in sorted(undeclared):
            print(f"  {name}")
        print("\nDeclare each one in SCANNED_INSIDE_EXEMPT (LITE can reach it) or in "
              "SKIPPED_INSIDE_EXEMPT (with the reason it cannot). 'Not in the exempt directory' is "
              "not the same as 'unreachable': commands/ItemCodeInput is imported by LiteCommand.")
        return 1
    print(f"scanned {checked} files ({len(SCANNED_INSIDE_EXEMPT)} of them inside an exempt "
          f"directory because LITE reaches them), {len(EXEMPT_DIRS)} FULL-only directories exempt")
    print(f"Vietnamese literals with no language flag in front of them: {len(findings)}")
    for path, number, literal, why in findings:
        print(f"  {path.relative_to(REPO)}:{number}  [{why}]  {literal[:90]}")
    if findings:
        print(
            "\nEither take the English/Vietnamese pair through the language flag "
            "(`isVietnamese()` / `vietnamese ?`), or move the string into messages_en.yml. LITE "
            "resolves the language to English always, so a stray literal is printed in Vietnamese "
            "inside an English-only jar."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
