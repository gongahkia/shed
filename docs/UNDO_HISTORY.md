# Undo History Policy

Each buffer keeps a bounded undo/redo history. `undo.history.max.entries` limits retained edits and `undo.history.max.bytes` limits estimated edit payload bytes. The defaults are 500 edits and 8 MiB per buffer.

Shed estimates a document edit as its UTF-16 payload length plus fixed bookkeeping. When either limit is exceeded, it evicts the oldest retained edit until both limits are met. An edit larger than the byte limit is not retained. Undo and redo remain valid for every retained edit; evicted edits cannot be recovered through undo.

Changing either setting applies immediately to open buffers and may trim their retained history. `:undolist` reports retained edits, estimated bytes, and whether undo or redo is currently available.
