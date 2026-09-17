"""Compare two JARs entry-by-entry. Whole-JAR hashes differ on ZIP metadata alone, so prove
whether the application bytes are identical. Read-only."""
import hashlib
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print('Usage: compare_jar_entries.py A.jar B.jar')
        return 2
    a_path, b_path = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(a_path) as a, zipfile.ZipFile(b_path) as b:
        a_names, b_names = set(a.namelist()), set(b.namelist())
        missing = sorted(a_names - b_names)
        extra = sorted(b_names - a_names)
        differing = []
        for name in sorted(a_names & b_names):
            if a.read(name) != b.read(name):
                differing.append(name)
        shared = len(a_names & b_names)
    print(f'a={a_path}')
    print(f'  sha256={hashlib.sha256(open(a_path, "rb").read()).hexdigest()}')
    print(f'b={b_path}')
    print(f'  sha256={hashlib.sha256(open(b_path, "rb").read()).hexdigest()}')
    print(f'shared_entries={shared} missing_in_b={len(missing)} extra_in_b={len(extra)} differing={len(differing)}')
    for name in (missing + extra + differing)[:20]:
        print(f'  ! {name}')
    if missing or extra or differing:
        print('VERDICT ENTRIES_DIFFER')
        return 1
    print('VERDICT ENTRY_BYTES_IDENTICAL (whole-JAR hash differs only by ZIP metadata)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
