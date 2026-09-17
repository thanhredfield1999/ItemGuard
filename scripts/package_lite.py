"""Build and verify a LITE candidate; never deploy or start a server."""
from pathlib import Path
import copy
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    log = ROOT / 'docs/reviews/lite-package-build.log'
    command = ['cmd.exe', '/d', '/c', str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(ROOT / 'mvnw')]
    with log.open('w', encoding='utf-8') as output:
        # -o (offline) is required, not optional: this machine has no reliable route to Maven
        # Central during a run, and without it the build dies resolving plugins that are
        # already in ~/.m2. Every dependency needed is cached.
        subprocess.run(command + ['-o', 'clean', 'verify', '--no-transfer-progress'], cwd=ROOT,
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    reports = list((ROOT / 'target/surefire-reports').glob('TEST-*.xml'))
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in reports:
        report = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(report.get(key, '0'))
    if not totals['tests'] or any(totals[k] for k in ('failures', 'errors', 'skipped')):
        raise RuntimeError(f'Full test gate rejected: {totals}')
    version = ET.parse(ROOT / 'pom.xml').getroot().find('{*}version').text
    source = ROOT / 'target' / f'ItemGuard-{version}.jar'
    destination = ROOT / 'target' / f'ItemGuard-LITE-{version}.jar'
    replacements = {}
    with zipfile.ZipFile(source) as original:
        if 'org/sqlite/JDBC.class' not in original.namelist():
            raise RuntimeError('Not a shaded artifact')
        for name in ('plugin.yml', 'config.yml'):
            replacements[name] = original.read('lite/' + name)
        descriptor = replacements['plugin.yml'].decode('utf-8')
        if ('main: com.itemguard.lite.ItemGuardLite' not in descriptor
                or '${' in descriptor or 'finditem:' in descriptor or 'matdo:' in descriptor):
            raise RuntimeError('Invalid LITE descriptor')
        replacements['META-INF/MANIFEST.MF'] = original.read('META-INF/MANIFEST.MF').replace(
            b'Main-Class: com.itemguard.ItemGuard', b'Main-Class: com.itemguard.lite.ItemGuardLite')
        # LITE ships English only (lite/config.yml pins language: en), so the Vietnamese and
        # Chinese message files are dead weight that actively misleads: an admin opening the
        # plugin folder finds messages.yml full of Vietnamese, edits it expecting their chat
        # to change, and nothing happens because LITE never loads it. Drop them from the jar.
        # A denylist of two names depends on nobody ever adding a third language file. The rule
        # that actually holds is the other way round: the plugin ships exactly three resources at
        # the root of the jar, and everything else that is not a class belongs to a shaded
        # library. Anything outside that is a finding, so a future `lang/vi.yml` or `help_zh.txt`
        # cannot ride along in the English-only jar unnoticed.
        dropped = {'messages.yml', 'messages_zh.yml'}
        root_resources = {'plugin.yml', 'config.yml', 'messages_en.yml'}
        library_prefixes = ('META-INF/', 'org/sqlite/', 'com/itemguard/libs/')
        library_root_files = {'sqlite-jdbc.properties'}
        with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as lite:
            for info in original.infolist():
                if info.filename.startswith('lite/') or info.filename in dropped:
                    continue
                lite.writestr(copy.copy(info), replacements.get(info.filename, original.read(info.filename)))
        with zipfile.ZipFile(destination) as lite:
            if lite.testzip() is not None:
                raise RuntimeError('JAR CRC validation failed')
            expected = {n for n in original.namelist()
                        if not n.startswith('lite/') and n not in dropped}
            if any(n in lite.namelist() for n in dropped):
                raise RuntimeError('Non-English message file leaked into LITE')
            if len(lite.namelist()) != len(set(lite.namelist())) or set(lite.namelist()) != expected:
                raise RuntimeError('JAR entry mismatch')
            for name in lite.namelist():
                if name.endswith('.class') or name.endswith('/'):
                    continue
                if name in root_resources or name in library_root_files:
                    continue
                if name.startswith(library_prefixes):
                    continue
                raise RuntimeError(
                    f'Unexpected LITE entry {name!r}: not one of the three shipped root resources '
                    f'{sorted(root_resources)}, not a class, not shaded library content under '
                    f'{list(library_prefixes)}. LITE ships English only - if this file belongs in '
                    f'the jar, check what language it contains and add it deliberately.'
                )
            for name in expected:
                if lite.read(name) != replacements.get(name, original.read(name)):
                    raise RuntimeError(f'Unexpected byte change: {name}')
    receipt = {'artifact': str(destination), 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
               'source_jar_sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'tests': totals,
               'paper_target': '1.21.11', 'java': 21, 'runtime_verified': False,
               'boundary': 'Same tested classes; separate LITE entrypoint/descriptor/default config. Full internal classes remain packaged but commands are not registered.'}
    (ROOT / 'target/lite-build-receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
