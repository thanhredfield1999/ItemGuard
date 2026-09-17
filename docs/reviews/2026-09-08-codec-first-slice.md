# Pure codec first slice — offline only

Opus 5 bounded re-review returned `PASS_FOR_CODEC_TDD` after normative codec constraints were clarified. Whole guardian remains BLOCKED. Review output: `%LOCALAPPDATA%/Temp/itemguard-codec-review-r2-20260908.json`.

Design now specifies strict UTF-8/scalar key order/escapes/UInt64/resource bounds and history-completeness gating. No runtime authority or Paper authorization created.

## Executed first vertical slice

Source `tools/execution-authority/guardian/CanonicalString.cs`; behavioral executable test `tools/execution-authority/tests/CodecStringTests.cs`.

- Test written and compiled first.
- Initial compiler invocations with slash-separated source paths failed CS1504; source existed. Switching compiler cwd to source parent and passing basename compiled successfully. These are tooling failures, not behavioral RED.
- RED after test compilation: missing implementation assembly FileNotFoundException. This establishes absent implementation, NOT a behavioral mutant regression.
- Added minimal strict UTF-8 string encoder.
- GREEN actual executable: `PASS 6 encoding vectors + 3 invalid-surrogate cases`, exit 0.
- git diff --check PASS.

Only string encoding implemented: empty/ASCII escaping/control/non-ASCII/astral/interior FEFF and invalid surrogate rejection. No complete object codec, decoder, independent recovery assembly, journal/hash/sequence verifier, Win32 guardian or durable receipt implementation yet. Test cap/large-input behavior belongs to future document-level codec. Binaries only in user Temp.

Next safe work: independent decoder + frozen byte corpus and string behavioral mutation test, then object key ordering/UInt64/bounds, record hashing. Do not call this full codec or full TDD completion; missing-assembly RED alone is weaker than an observed behavioral regression.
