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
