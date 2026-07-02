# Grapheme Design

Status: Phase 24 fixture baseline.

## Ground Truth

Cursor motion, deletion, and selection validation use Unicode extended grapheme cluster boundaries from UAX #29. The conformance fixture is vendored at:

```text
Tests/Fixtures/UCD/GraphemeBreakTest.txt
```

Regenerate it with:

```sh
scripts/download_ucd.sh
```

The script fetches `auxiliary/GraphemeBreakTest.txt` from `https://www.unicode.org/Public/UCD/latest/ucd` by default. Set `UCD_BASE_URL` to pin or mirror a Unicode data directory.

## Test Parser

The conformance test parser should read each non-comment test row, parse `÷` as an allowed boundary and `×` as no boundary, then compare the expected byte offsets to `UAX29GraphemeIterator`.

Failure output must include the fixture row number and the codepoint sequence, so Unicode version changes are diagnosable from CI logs.
