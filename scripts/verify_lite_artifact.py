"""Independently verify an already-built LITE candidate. Read-only: never builds, deploys or starts a server."""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_SUBSTITUTIONS = {'plugin.yml', 'config.yml', 'META-INF/MANIFEST.MF'}
DESTRUCTIVE_TOKENS = ('reclaim', 'withdraw', 'lostitem', 'lost-item', 'teleport', 'confiscate', 'seize')
# Allowlist, not a denylist: anything outside these sets is a finding.
EXPECTED_COMMANDS = ['itemguard']
EXPECTED_PERMISSIONS = {
    'itemguard.check': 'true',
    'itemguard.history': 'true',
    'itemguard.history.others': 'op',
    'itemguard.search': 'op',
    'itemguard.stats': 'op',
    'itemguard.notify': 'op',
    'itemguard.admin': 'op',
}


def unexpected_entries(names) -> list:
    """Non-class, non-directory entries that belong to neither the plugin nor a shaded library.

    The plugin ships exactly three resources at the root of the jar; everything else that is not
    a class comes from a shaded dependency (`META-INF/`, `org/sqlite/`, the relocated bstats, and
    sqlite-jdbc's own properties file). Judging by name rather than by a denylist of two
    filenames is what makes a future `lang/vi.yml` or `help_zh.txt` a finding instead of a silent
    passenger in a jar advertised as English-only.

    Deliberately *not* a content scan. The leak this replaced was Vietnamese written without
    diacritics (`"Ban khong co quyen su dung lenh nay!"`), which no codepoint range can see;
    the guard for that is the Java-side parity test pinning the built-in fallback text to
    `messages_en.yml`. A byte scan here would look like coverage and miss the real case.
    """
    root_resources = {'plugin.yml', 'config.yml', 'messages_en.yml'}
    library_prefixes = ('META-INF/', 'org/sqlite/', 'com/itemguard/libs/')
    library_root_files = {'sqlite-jdbc.properties'}
    return sorted(
        n for n in names
        if not n.endswith('.class') and not n.endswith('/')
        and n not in root_resources and n not in library_root_files
        and not n.startswith(library_prefixes)
    )


def check_surface(commands, permissions) -> list:
    """Assert the exact LITE command set and permission defaults."""
    findings = []
    if sorted(commands) != sorted(EXPECTED_COMMANDS):
        findings.append(f'registered command set is not exactly {EXPECTED_COMMANDS}: {sorted(commands)}')
    for name, expected in EXPECTED_PERMISSIONS.items():
        actual = permissions.get(name)
        if actual is None:
            findings.append(f'permission {name} is not declared in the LITE descriptor')
        elif actual != expected:
            findings.append(f'permission {name} default is {actual!r}, expected {expected!r}')
    for name in permissions:
        if name not in EXPECTED_PERMISSIONS:
            findings.append(f'undeclared extra permission in LITE descriptor: {name}')
    return findings


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strip_comments(text: str) -> str:
    """Drop YAML comments so token scans only see active settings, not prohibition notes."""
    cleaned = []
    for raw in text.splitlines():
        quote = None
        cut = len(raw)
        for index, char in enumerate(raw):
            if quote:
                if char == quote:
                    quote = None
            elif char in '"\'':
                quote = char
            elif char == '#':
                cut = index
                break
        cleaned.append(raw[:cut].rstrip())
    return '\n'.join(cleaned)


def top_level_keys(descriptor: str) -> dict:
    """Parse the flat top-level blocks of plugin.yml without a YAML dependency."""
    blocks: dict[str, list[str]] = {}
    current = None
    for raw in descriptor.splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        if not raw.startswith((' ', '\t')):
            key, _, inline = raw.partition(':')
            current = key.strip()
            blocks[current] = [inline.strip()] if inline.strip() else []
        elif current is not None:
            blocks[current].append(raw.rstrip())
    return blocks


def nested_names(lines: list[str]) -> list[str]:
    names = []
    if not lines:
        return names
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    if not indents:
        return names
    base = min(indents)
    for line in lines:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) == base:
            names.append(line.strip().rstrip(':').split(':')[0])
    return names


def permission_defaults(lines: list[str]) -> dict:
    """Map each declared permission to its literal `default:` value."""
    defaults: dict[str, str] = {}
    if not lines:
        return defaults
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    if not indents:
        return defaults
    base = min(indents)
    current = None
    for line in lines:
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if indent == base:
            current = line.strip().rstrip(':').split(':')[0]
            defaults.setdefault(current, None)
        elif current is not None and line.strip().startswith('default:'):
            defaults[current] = line.split(':', 1)[1].strip().strip("'\"")
    return defaults


def main() -> int:
    version = '1.0.0'
    lite_path = ROOT / 'target' / f'ItemGuard-LITE-{version}.jar'
    full_path = ROOT / 'target' / f'ItemGuard-{version}.jar'
    receipt_path = ROOT / 'target/lite-build-receipt.json'
    findings: list[str] = []
    facts: dict[str, object] = {}

    for path in (lite_path, full_path, receipt_path):
        if not path.exists():
            print(f'REJECT missing input: {path}')
            return 1

    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    lite_sha = sha256(lite_path)
    full_sha = sha256(full_path)
    facts['lite_sha256'] = lite_sha
    facts['full_sha256'] = full_sha
    facts['receipt_tests'] = receipt.get('tests')

    if receipt.get('sha256') != lite_sha:
        findings.append('receipt sha256 does not match the LITE artifact on disk')
    if receipt.get('source_jar_sha256') != full_sha:
        findings.append('receipt source_jar_sha256 does not match the Full artifact on disk')
    totals = receipt.get('tests') or {}
    if not totals.get('tests') or any(totals.get(k) for k in ('failures', 'errors', 'skipped')):
        findings.append(f'receipt test gate is not clean: {totals}')
    if receipt.get('runtime_verified') is not False:
        findings.append('receipt must not claim runtime verification')

    with zipfile.ZipFile(lite_path) as lite, zipfile.ZipFile(full_path) as full:
        if lite.testzip() is not None:
            findings.append('LITE JAR failed CRC validation')
        lite_names = lite.namelist()
        full_names = full.namelist()
        if len(lite_names) != len(set(lite_names)):
            findings.append('LITE JAR contains duplicate entries')
        # LITE is English-only (lite/config.yml pins language: en), so package_lite.py drops the
        # Vietnamese and Chinese message files. They are not merely unused: an admin who opens
        # the plugin folder, finds messages.yml in Vietnamese and edits it gets no effect at
        # all, because LITE never loads that file. Dropping them is deliberate, so the expected
        # entry set must account for it -- but their presence in LITE is still a hard failure.
        DROPPED_FROM_LITE = {'messages.yml', 'messages_zh.yml'}
        expected = {n for n in full_names if not n.startswith('lite/')} - DROPPED_FROM_LITE
        if set(lite_names) != expected:
            missing = sorted(expected - set(lite_names))[:5]
            extra = sorted(set(lite_names) - expected)[:5]
            findings.append(f'entry set mismatch; missing={missing} extra={extra}')
        leaked = sorted(DROPPED_FROM_LITE & set(lite_names))
        if leaked:
            findings.append(f'LITE JAR ships non-English message files: {leaked}')
        leftover = [n for n in lite_names if n.startswith('lite/')]
        if leftover:
            findings.append(f'LITE JAR still ships template entries: {leftover[:5]}')
        # The same rule package_lite.py enforces, asserted independently here.
        unexpected = unexpected_entries(lite_names)
        if unexpected:
            findings.append(f'LITE JAR ships unexpected non-class entries: {unexpected[:5]}')

        changed = sorted(n for n in expected & set(lite_names) if lite.read(n) != full.read(n))
        facts['changed_entries'] = changed
        if set(changed) - ALLOWED_SUBSTITUTIONS:
            findings.append(f'unexpected byte changes outside substitutions: {sorted(set(changed) - ALLOWED_SUBSTITUTIONS)}')
        facts['entry_count'] = len(lite_names)
        facts['class_entries'] = sum(1 for n in lite_names if n.endswith('.class'))
        if 'org/sqlite/JDBC.class' not in lite_names:
            findings.append('LITE JAR is not shaded with the SQLite driver')

        descriptor = lite.read('plugin.yml').decode('utf-8')
        config = lite.read('config.yml').decode('utf-8')
        manifest = lite.read('META-INF/MANIFEST.MF').decode('utf-8')

    if '${' in descriptor:
        findings.append('descriptor contains unresolved Maven placeholders')
    descriptor_active = strip_comments(descriptor)
    config_active = strip_comments(config)
    blocks = top_level_keys(descriptor)
    main_class = blocks.get('main', [''])[0]
    api_version = blocks.get('api-version', [''])[0].strip("'\"")
    commands = nested_names(blocks.get('commands', []))
    permissions = permission_defaults(blocks.get('permissions', []))
    facts['main'] = main_class
    facts['api_version'] = api_version
    facts['commands'] = commands
    facts['permissions'] = permissions
    facts['manifest_main_class'] = next(
        (line.split(':', 1)[1].strip() for line in manifest.splitlines() if line.startswith('Main-Class:')), None)

    if main_class != 'com.itemguard.lite.ItemGuardLite':
        findings.append(f'descriptor main is not the LITE entry point: {main_class!r}')
    if facts['manifest_main_class'] != 'com.itemguard.lite.ItemGuardLite':
        findings.append(f'manifest Main-Class is not the LITE entry point: {facts["manifest_main_class"]!r}')
    # This is the declared FLOOR, not the Paper build the JAR was tested against. LITE compiles
    # against paper-api 1.21.4 and was verified on eleven versions from 1.21.4 to 26.2, so the
    # floor and the compile target are deliberately the same value and both are 1.21.4.
    # Anything else means someone moved one without moving the other.
    if api_version != '1.21.4':
        findings.append(f'api-version is not the declared support floor: {api_version!r}')
    findings.extend(check_surface(commands, permissions))
    aliases = [line.strip() for line in blocks.get('commands', []) if 'aliases:' in line]
    facts['command_aliases'] = aliases
    for alias_line in aliases:
        for banned in ('finditem', 'matdo', 'igbench'):
            if banned in alias_line:
                findings.append(f'LITE command alias exposes a Full surface: {alias_line}')
    for token in DESTRUCTIVE_TOKENS:
        if token in descriptor_active.lower():
            findings.append(f'descriptor declares a destructive surface: {token}')

    duplicate_action = re.search(r'^\s*action:\s*(\S+)', config_active, re.MULTILINE | re.IGNORECASE)
    facts['config_duplicate_action'] = duplicate_action.group(1) if duplicate_action else None
    worldguard = re.search(r'worldguard-support:\s*(\S+)', config_active)
    facts['config_worldguard_enabled'] = worldguard.group(1) if worldguard else None
    if facts['config_duplicate_action'] != 'NOTIFY':
        findings.append(f'default duplicate action is not NOTIFY: {facts["config_duplicate_action"]!r}')
    if facts['config_worldguard_enabled'] != 'false':
        findings.append(f'default worldguard-support is not disabled: {facts["config_worldguard_enabled"]!r}')
    for token in DESTRUCTIVE_TOKENS:
        if token in config_active.lower():
            findings.append(f'default config declares a destructive surface: {token}')

    print(json.dumps(facts, indent=2, ensure_ascii=False))
    if findings:
        print('VERDICT REJECT')
        for item in findings:
            print(f'- {item}')
        return 1
    print('VERDICT ARTIFACT_CONSISTENT_OFFLINE_ONLY')
    print('Not runtime, client, gameplay, release or production evidence.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
