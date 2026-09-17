"""Build the Spigot release bundle from the exact packaged candidate.

The bundle is not a second source of truth: every byte in it comes from the JAR named in
target/lite-build-receipt.json and from the README that ships next to it. The receipt's
sha256 is re-derived from the file on disk here, so a stale receipt cannot bind a bundle to
a candidate that no longer exists.

Writes release/ItemGuard-LITE-1.0.0-Spigot.zip and release/SHA256SUMS.txt, then reads the
written archive back: CRC test, entry set, and the hash of the JAR AS STORED IN THE ZIP.
That read-back is the only reason to trust the bundle - an archive that was written is not
an archive that was verified.
"""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RECEIPT = ROOT / 'target/lite-build-receipt.json'
README = ROOT / 'docs/release/ItemGuard-LITE-1.0.0-README.txt'
LICENCE = ROOT / 'docs/release/LICENSE-LITE.txt'
BUNDLE = ROOT / 'release/ItemGuard-LITE-1.0.0-Spigot.zip'
SUMS = ROOT / 'release/SHA256SUMS.txt'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    receipt = json.loads(RECEIPT.read_text(encoding='utf-8'))
    jar = Path(receipt['artifact'])
    if not jar.is_file():
        raise SystemExit('Packaged candidate missing: ' + str(jar))
    candidate = sha(jar)
    # The receipt is a record, not an authority. If it disagrees with the file, the file wins
    # and nothing is written: the mismatch means the JAR was rebuilt after the receipt.
    if candidate != receipt['sha256']:
        raise SystemExit('Receipt/artifact mismatch: receipt says ' + receipt['sha256']
                         + ', file is ' + candidate)
    if receipt['tests']['failures'] or receipt['tests']['errors']:
        raise SystemExit('Refusing to bundle a candidate with failing tests')
    if not README.is_file():
        raise SystemExit('README missing: ' + str(README))
    if not LICENCE.is_file():
        raise SystemExit('Licence missing: ' + str(LICENCE))

    BUNDLE.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(BUNDLE, 'w', zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(jar, jar.name)
        bundle.write(README, 'README.txt')
        bundle.write(LICENCE, 'LICENSE.txt')

    # Read-back over the archive that now exists on disk, not over what was just in memory.
    with zipfile.ZipFile(BUNDLE) as bundle:
        if bundle.testzip() is not None:
            raise SystemExit('Bundle CRC failure')
        entries = sorted(bundle.namelist())
        if entries != sorted([jar.name, 'README.txt', 'LICENSE.txt']):
            raise SystemExit('Unexpected bundle entries: ' + repr(entries))
        stored = hashlib.sha256(bundle.read(jar.name)).hexdigest()
    if stored != candidate:
        raise SystemExit('Stored JAR does not match the candidate')

    SUMS.write_text(candidate + '  ' + jar.name + '\n'
                    + sha(BUNDLE) + '  ' + BUNDLE.name + '\n', encoding='utf-8')

    print(json.dumps({
        'bundle': str(BUNDLE),
        'bundle_sha256': sha(BUNDLE),
        'candidate_sha256': candidate,
        'entries': entries,
        'tests': receipt['tests'],
        'verdict': 'BUNDLE_MATCHES_CANDIDATE_OFFLINE_ONLY',
        'scope': 'Archive integrity and candidate binding only. Not runtime, gameplay, '
                 'listing or publication evidence.',
    }, indent=2))


if __name__ == '__main__':
    sys.exit(main())
