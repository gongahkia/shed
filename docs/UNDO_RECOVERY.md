# Undo Recovery Policy

Undo and redo history does not survive a restart. Shed persists only complete dirty-buffer content through the bounded, integrity-checked recovery journal; it never serializes Swing `UndoableEdit` objects or replays edits from disk.

After recovery, the restored buffer is dirty and starts with an empty undo/redo history. Saving is explicit. This prevents a stale or malformed history record from changing restored content, while the recovery journal still protects unsaved text.

Runtime history remains configurable with `undo.history.max.entries` and `undo.history.max.bytes`. Those limits apply only to the current process and do not affect recovery-journal retention.
