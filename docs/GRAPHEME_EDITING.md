# Grapheme-Aware Editing Contract

## Status

This is the contract for the grapheme-aware cursor, deletion, and regression work in #72 through #74. The current editor still has UTF-16 code-unit motions in several paths and does not yet claim this behavior. Large-file mode remains read-only and is outside this contract.

## Terms and coordinate systems

Shed stores Java text and Swing document positions as zero-based UTF-16 code-unit offsets. They are valid from `0` through `text.length()` inclusive. This preserves interoperability with `JTextArea`, `Document`, sessions, and existing Java APIs; it does not make one UTF-16 code unit a user-visible character.

A user-visible character is one [Unicode extended grapheme cluster](https://www.unicode.org/reports/tr29/). Implementations must pin the Unicode boundary data used for a release and verify it against the corresponding grapheme-break test data. They must not silently substitute code-point, `char`, or locale-word boundaries for extended grapheme boundaries.

No operation normalizes text. NFC/NFD-equivalent input retains its original UTF-16 content, and undo/redo restores those exact code units.

## Boundary invariants

- A grapheme boundary is `0`, `text.length()`, or an extended-grapheme break between them.
- User-visible caret positions and collapsed selections are always grapheme boundaries.
- Character motions move to the preceding or following boundary. Word, line, document, and visual-selection motions may apply their own motion rule, but their result must be a grapheme boundary.
- A non-collapsed selection covers complete clusters. Its start rounds toward the preceding boundary and its end rounds toward the following boundary.
- A replacement or deletion range uses those same outward-rounded endpoints. It never leaves part of a cluster behind.

Direction resolves an offset inside a cluster: a left/backward operation uses the preceding boundary and a right/forward operation uses the following boundary. APIs that accept an unqualified external offset must reject an interior offset rather than guess a direction. Session restoration and internal state repair may use the preceding boundary only after recording that normalization in the applicable diagnostic path.

## Editing behavior

Backspace deletes exactly the cluster immediately before a collapsed caret. Delete removes exactly the cluster immediately after it. At document bounds they are no-ops. For a non-collapsed selection, either command deletes the normalized complete-cluster range.

Selection expansion and shrink commands move only between boundaries. Replacing a selection first applies the range rule above, then inserts the supplied UTF-16 text without normalization. One user deletion or replacement is one undoable edit; undo restores the exact former code units and boundary-valid caret/selection state.

## Required examples

The following inputs each form one cluster for character movement, selection, backspace, and delete:

| Input | Meaning |
| :--- | :--- |
| `e\u0301` | Latin base plus combining acute accent |
| `👩🏽` | Emoji base plus skin-tone modifier |
| `👩‍💻` | Emoji ZWJ sequence |
| `🇸🇬` | One regional-indicator flag pair |
| `\r\n` | One line-break cluster |

Adjacent regional indicators follow the UAX #29 pairing rule: `🇸🇬🇺🇸` has two flag clusters. A request at any UTF-16 offset between a surrogate pair, ZWJ sequence component, combining mark, modifier, or regional-indicator pair is an interior offset and follows the boundary invariant.

## Invalid input

Java `String` values can contain unpaired surrogate code units even though they are not well-formed Unicode scalar text. Grapheme helpers must preserve them exactly and treat each unpaired surrogate as one opaque one-code-unit cluster. They must not emit U+FFFD, normalize, merge it with a neighbor, or access outside `[0, text.length()]`.

Malformed file bytes are handled by the file decoding policy before text reaches this contract. A grapheme operation must fail without mutating the document when its supplied range is outside the document or violates the unqualified-offset rule.

## Non-goals

This contract does not promise cursor behavior by glyph, display column, font shaping, bidirectional visual order, locale tailoring, or every script's orthographic syllable. It specifies default extended grapheme clusters only. Features requiring a different unit must name and test that unit explicitly.

## Verification

Focused coverage for #72–#74 must exercise the examples above through navigation, selection, deletion, undo, save, and reload. It must also test leading/trailing boundaries, empty text, adjacent clusters, malformed surrogate values, and range rejection. Tests must verify exact UTF-16 output rather than only rendered glyphs.
