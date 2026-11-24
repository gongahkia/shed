# Use Shed with an external coding agent

Shed uses the same source buffers and Undo history for human and reviewed agent edits. Start Shed normally, open the intended project, and explicitly run `:agent api start control`. The status line shows a private connection descriptor. Stop access with `:agent api stop`. Credentials remain in the owner-only session directory; do not paste key contents into a prompt.

Select that attachment once in your shell:

```sh
export SHED_CONTROL=/path/shown/by/Shed/status-ATTACHMENT.json
shed ctl state
shed ctl open src/App.java
shed ctl read --offset 0 --limit 2048
```

`--connect <descriptor>` is the equivalent explicit option. The matching `.key` filename is derived from the authenticated descriptor; `--credential <file>` remains available. There is no automatic choice among running windows or projects. The installed launcher may be replaced with `java -jar /path/to/shed.jar` in these examples. All replies are JSON-RPC JSON; `--json` is optional. Existing `--workspace-api` commands remain available.

Read replies include `documentId`, `documentRevision`, exact UTF-16 `offset`/`nextOffset`, and `truncated`. Reads are bounded to 2,048 UTF-16 units and respect grapheme boundaries. Continue from `nextOffset`; never interpret it as a byte or code-point index. Use the document ID and revision from the observation that motivated your edit:

```sh
shed ctl propose --document DOCUMENT_ID --revision REVISION \
  --start 100 --end 150 --text-file /tmp/desired-chunk.txt
shed ctl replace --document DOCUMENT_ID --revision REVISION \
  --text-file /tmp/desired-small-file.txt
```

`--text-file -` reads bounded UTF-8 from stdin. A chunk is limited to 8,192 UTF-16 units. `replace` means the entire currently observed small document; use it only when you have read the complete source. `--request-id` supports deliberate retry identification; do not retry an uncertain mutation blindly. Invalid/stale context fails rather than retargeting another buffer. There is no CLI Apply, Save, arbitrary process execution, or automatic acceptance. The human reviews the existing Agents panel, chooses hunks, and presses Apply. Text changes become dirty editor contents; saving remains a separate human action.

You can show a transient distinct agent marker and pulsing source range with:

```sh
shed ctl activity --start 100 --end 150
```

This never types fake intermediate characters into the source or moves the human caret. Complex/large wrapped layouts retain the existing performance restrictions. Missing glyph coverage in the physical default font remains a separate limitation; use exact source replies as the text authority.

## Suggested agent instruction

> Use the explicit Shed attachment selected by SHED_CONTROL. Read the intended source and retain its document ID and revision. Propose bounded changes through `shed ctl`; leave acceptance and saving to the human. If a request is stale, reread and reconsider. Never execute future/unknown endpoints, edit the backing file to bypass review, or treat screenshots as authoritative text. Leave the source and view useful for human review.

Authentication protects the attachment against clients without its key and binds it to the selected instance. It is not an OS sandbox against another process with unrestricted access to the same user’s files. Do not expose the endpoint to other hosts or share private session files.

## Named activity and cancellation

```sh
shed ctl progress --session-id turn-1 --agent Codex --phase thinking --completed 1 --total 3 --start 100 --end 150
shed ctl activity-state
shed ctl propose --session-id turn-1 --document DOCUMENT_ID --revision REVISION --start 100 --end 150 --text-file /tmp/chunk.txt
shed ctl cancel --session-id turn-1
```

Phases are `reading`, `thinking`, `proposing`, `complete`, `cancelled`, and `failed`. A total of zero means indeterminate progress. One display session is active per attachment; another session is rejected until the current one finishes or is cancelled. A finished session ID cannot resume. Names are display labels, not authenticated agent identities. Updates reuse the status line and distinct source marker; progress pulses expire after 30 seconds without another update and stop on source edits, hidden/disposed views, completion, cancellation, or attachment revocation. Reduced-motion preferences remain authoritative.

Pass the same `--session-id` with edit proposals to make cancellation meaningful: finished/cancelled sessions cannot submit new chunks. Cancellation is cooperative; it does not terminate an external agent process or retract an already durable proposal. Reject an outstanding proposal in the review panel. `:agent api stop` revokes the entire attachment. Legacy one-shot proposals without a session remain available.

## Reviewed project changes

`read-file` observes a project-relative file without switching the human's tab. It returns a bounded `file` object containing `content`, opaque `version`, `offset`, `nextOffset`, `length`, `truncated`, and `source`. Keep the version from the source you actually inspected.

```sh
shed ctl read-file src/Other.java
shed ctl propose-files --changes-file /tmp/reviewed-changes.json --request-id task-42
```

The changes file is a JSON array, at most eight files and 8,192 replacement UTF-16 units total:

```json
[
  {"kind":"text","path":"src/Other.java","version":"VERSION_FROM_READ","text":"desired full source"},
  {"kind":"create","path":"src/New.java","text":"class New {}\n"},
  {"kind":"rename","path":"old.txt","version":"VERSION_FROM_READ","destination":"new.txt"},
  {"kind":"delete","path":"obsolete.txt","version":"VERSION_FROM_READ"}
]
```

These operations create one proposal in the existing multi-document review panel. They do not apply remotely. The human inspects each file and accepts through existing checked transactions and recovery. Applying text to an open buffer leaves it dirty; applying changes to closed files and create/rename/delete operations deliberately changes disk through that existing review action. Review Undo restores the transaction, subject to the existing conflict checks. No remote save or arbitrary tool execution is exposed. Paths outside the approved project, nested independently configured roots, protected README files, excluded environment files, stale versions and conflicting targets are rejected. The trusted-local path contract is not a sandbox against hostile same-user filesystem races.

## Feedback and rendered-file observation

```sh
shed ctl diagnostics
shed ctl feedback
shed ctl open notes.md
shed ctl preview
shed ctl preview-state
shed ctl preview-capture --output /tmp/shed-preview.png
```

Diagnostics return at most 32 cached entries for the active source (256 message units each), with zero-based LSP UTF-16 line/column coordinates. They do not start a server or consume its pending message queue. `freshness: unverified` is intentional: the existing cache does not bind every entry to an exact source revision. The response's document revision identifies the observation context, not guaranteed diagnostic freshness. Feedback returns aggregate existing application job counts, without commands, environment, output, unrelated filenames or execution permissions.

Agent-opened previews reuse Shed's existing Markdown preview and disable remote image fetching for the newly opened preview without changing saved preferences. A pre-existing human-opened preview retains its existing policy. Observation is limited to Markdown sources of at most 4,096 UTF-16 units. Mermaid, math and local image support remain those of the existing preview renderer; this does not add inline source rendering.

Capture reads only the current source's existing, showing, revision-current preview viewport. It crops up to 256×160 logical units at the actual display scale, at most 262,144 pixels and 20,000 PNG bytes. A more complex viewport can exceed the byte budget and is rejected. The CLI creates a new requested output file and refuses to overwrite an existing file. No desktop, other application, source tab, picker or arbitrary window can be selected for capture. Captures are visual observations, not authoritative source text. Known physical-font glyph coverage, first-use Unicode layout and stock HTML-preview EDT latency limitations remain open. Readiness/timeout/context errors are reported; do not retry a potentially applied presentation operation blindly.
