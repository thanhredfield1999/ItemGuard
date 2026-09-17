"""Freeze the exact LITE review inputs as SHA-256. Read-only."""
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXED = [
    'src/main/resources/lite/plugin.yml',
    'src/main/resources/lite/config.yml',
    'scripts/package_lite.py',
    'scripts/verify_lite_artifact.py',
    'scripts/test_verify_lite_artifact.py',
    'docs/LITE_TESTING.md',
    'target/ItemGuard-LITE-1.0.0-test.jar',
    'target/ItemGuard-1.0.0.jar',
    'target/lite-build-receipt.json',
]


def main() -> None:
    paths = sorted(
        p.relative_to(ROOT).as_posix()
        for p in (ROOT / 'src/main/java/com/itemguard/lite').glob('*.java')
    ) + FIXED
    inputs = {}
    for rel in paths:
        path = ROOT / rel
        inputs[rel] = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
    payload = {
        'scope': 'ItemGuard LITE L0 frozen review inputs',
        'date': '2026-09-12',
        'runtime_verified': False,
        'inputs': inputs,
    }
    out = ROOT / 'docs/reviews/2026-09-12-lite-input-manifest.json'
    out.write_text(json.dumps(payload, indent=2), encoding='utf-8')
    print(json.dumps(payload, indent=2))


if __name__ == '__main__':
    main()
