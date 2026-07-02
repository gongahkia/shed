# Grapheme QA Checklist

## Setup

1. Launch Itsy with piece-tree storage enabled.
2. Open a blank document.
3. Use a visible monospace font size where emoji width changes are obvious.

## Paste Family Emoji

Text:

```text
start 👨‍👩‍👧‍👦 end
```

Steps:

1. Paste the text.
2. Move the caret left and right across the family emoji.
3. Press Backspace once immediately after the emoji.

Observable:

- Arrow movement treats `👨‍👩‍👧‍👦` as one stop.
- One Backspace removes the full emoji and leaves `start  end`.

Failure mode:

- Caret lands inside the ZWJ sequence, or Backspace leaves partial people glyphs / replacement characters.

## Arrow Through Flag

Text:

```text
flags 🇸🇬🇯🇵 done
```

Steps:

1. Paste the text.
2. Move the caret from before `🇸🇬` to after `🇯🇵` with Right Arrow.
3. Move back with Left Arrow.

Observable:

- Each flag is one caret stop.
- The caret never lands between regional indicators.

Failure mode:

- A flag splits into letters, halves, or replacement characters while moving.

## Backspace Through ZWJ

Text:

```text
job 👩‍🚒 done
```

Steps:

1. Paste the text.
2. Put the caret immediately after `👩‍🚒`.
3. Press Backspace once.

Observable:

- `👩‍🚒` is removed as one grapheme cluster.
- Surrounding spaces and text remain unchanged.

Failure mode:

- Backspace removes only the helmet/fire-truck part, leaves a standalone woman glyph, or produces `�`.

## Multi-Cursor

Text:

```text
👋🏽 क्क 1️⃣ 🇸🇬 👩‍🚒
```

Steps:

1. Put cursors after each grapheme cluster.
2. Type `#`.
3. Press Backspace once.

Observable:

- Five `#` characters appear, one per cursor.
- One Backspace removes only those five `#` characters and restores the original text.

Failure mode:

- Cursor offsets drift, a complex grapheme is split, or fewer than five deletions happen.
