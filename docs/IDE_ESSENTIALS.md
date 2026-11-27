# Saved history, Outline and keyboard shortcuts

## Local file history

**File → Local History…** or `:localhistory` opens saved versions for the current file. Select a version to inspect its exact source, compare it with the current buffer, or explicitly restore it. Restore requires confirmation and the same active file and document revision; it is one Undo step. It does not write disk. Save separately through the normal external-change and encoding checks. Comparing uses the central read-only diff surface and does not create another source document.

History is recorded after successful ordinary synchronous/asynchronous text saves, including Save As under its new path. The first save retains the loaded saved baseline when available. It does not capture arbitrary external agent writes, every keystroke, notebooks' separate external execution output or streaming large-file saves. Recovery and Git history continue independently. Failed saves do not create versions. History failure does not turn a successful disk save into a failed save; the browser shows the last capture error.

Versions live under Shed's configured directory in `local-history`, using owner-private files and hashed absolute path identities. Exact UTF-16 code units are stored with SHA-256 integrity checks, including supplementary and unpaired surrogate code units. Restore retains the current document's encoding/newline policy; versions are source snapshots, not original byte-format backups. Renaming or Save As starts a separate path history. No network or VCS is required.

Bounds: 512 Ki UTF-16 units per snapshot, 20 versions per path, 256 total versions, 32 MiB retained payload after pruning. Consecutive identical source is deduplicated. Storage scans are bounded and reject unexpected entries, permissions, links or oversized files. Hidden basenames, filenames containing `secret` or `credential`, `.pem` and `.key` files are excluded; this is a conservative filename filter, **not content-based secret detection**. Source history may contain sensitive code. Disable future capture with `history.local.enabled=false` in Settings; existing versions remain until explicitly deleted with **Delete saved versions…**. No pre-existing personal configuration is modified to satisfy private-state permissions. Unsupported permission models fail capture with a visible history error.

Normal UI saves use Shed's existing save workers. History adds bounded disk work to a save, not to typing; no new worker pool, timer or startup scan is added. Synchronous callers retain their existing synchronous save boundary. These limits are not a backup or crash-durability guarantee. Simultaneous Shed instances share owner-private state but do not provide a cross-process retention transaction; limits are applied by each successful capture.

## Persistent Outline

**View → Toggle Outline**, its command-palette action or `:outline-sidebar` opens Outline below File Explorer. It remains independent of document tabs and editor splits. Its divider is draggable; Explorer and Outline can be shown independently, and focus mode temporarily hides them. The Outline visibility preference persists as `ui.outline.visible` (off by default).

Filter by symbol name. Double-click or Enter reveals a symbol in the active source and records navigation history. Outdated document snapshots reject navigation. File switching and edits replace old cached results; hidden Outline does not start another parser or language server. At most 2,000 rows are displayed.

Outline reuses the existing local `SymbolService` cache: JDK structural Java declarations where supported and lexical declarations/headings for the service's other languages. It does not invent language coverage, request additional LSP work or promise semantic symbols for unsupported files. Large-file mode reports unavailability. Existing LSP document/workspace symbol commands remain available separately. No new background index or document listener is installed.

## Keyboard shortcuts

**File → Keyboard Shortcuts…** retains Shed's existing searchable effective bindings, profile/mode scopes, user overlays and reset behavior. Double-click a binding or choose **Edit Selected** to edit it. Choose an action or enter the existing `action:…` / `command:…` mapping syntax.

**Record shortcut** focuses the Key field. Press a modified shortcut or F1–F12; Escape cancels recording. Command on macOS / Control elsewhere is recorded as portable `Mod`. Modifier-only events and ordinary text are not captured; Ctrl+Alt/AltGraph remain reserved for international input. Recorded input does not enter source buffers.

The editor shows existing mappings with the same physical key/modifiers in the chosen scope or global fallback, including Mod versus physical modifier aliases. Saving an overlapping mapping asks for confirmation. The existing key parser, precedence rules and validation remain authoritative. Overrides apply to editor shortcuts; menu accelerators, OS-reserved shortcuts, other text fields and legacy Vim sequences retain their established boundaries. OS interception and non-US native keyboard layouts are not fully qualified by synthetic key events.

## Other VCS systems

Mercurial and Subversion now have graphical working-copy pages alongside Git in **View → Source Control**. Select a provider or use automatic working-copy detection; inspect changes, central diffs and history, then perform explicitly confirmed provider operations. Existing `:scm` text commands remain available. Installed `hg` / `svn` clients are required. See [Source Control usage, native semantics and qualification limits](SOURCE_CONTROL.md). The earlier IDE-essentials evidence remains a record of its original command-only scope.

Run `make run .` to try the features. The existing `make demo-chat` now exercises Outline, a normal save/history capture, confirmed undoable restore, comparison, shortcut recording/persistence, and the existing Chat/dock flow using a disposable fixed Java client. It does not run a real coding model or the unqualified native-shell harness. See [qualification commands, exact counts and limitations](performance/ide-essentials/INDEX.md).
