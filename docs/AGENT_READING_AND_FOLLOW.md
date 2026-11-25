# Direct source following and reading presentation

The visualization watcher observes ordinary writes by any agent. Reading presentation requires an explicit signal; filesystem writes are not evidence of reads. Both presentations use Shed's real source pane and authoritative buffers, without simulated source edits or desktop input events.

## Confirmed failures

The reported attachment was watching the correct demo project and was ready, but its `following` field was false. A fresh project-window probe reproduced an idle click in the blank source pane permanently disabling Follow before the first observed edit. The earlier EOF probe started with an already-open file and missed this interaction. A previously suggested project mismatch is insufficient to explain the subsequent correct-project screenshot.

`LiveChangesPanel.pauseForHuman` now interrupts an active or pending tour, rather than permanently disabling Follow for idle source/Explorer interactions. Explicit Pause, interaction during a tour, dirty buffers and recent typing remain protected. Automatic opening selects the existing source split when the tree is active; it does not replace the Explorer or an unrelated preview/alternate surface.

The first reading desktop attempt also exposed an existing control-entry limitation: metadata/navigation context required an already-open file. Asking a blank/tree window to open its first file failed with `NO_ACTIVE_EDITOR`.

## Agent-visible reading

```sh
# use the live attachment returned by visualize, with the rebuilt client and application
export SHED_CONTROL=/private/session/status-ATTACHMENT.json
shed ctl read src/App.java --offset 0 --limit 2048 --agent "My agent"
```

The path form explicitly opens an authorized file, returns a bounded chunk of the actual source buffer and presents that chunk in the editor. Continue from the returned `nextOffset`. The pulse uses the existing theme warning color, a slower alpha cycle and top-to-bottom sweep, an underline marker and a reading label, distinguishing it from the existing success-colored edit cursor. The status line identifies the file being read. Rendering never changes source, revision, Undo history or the human caret. Opening another file retains the normal tab/navigation behavior.

The animated indicator expires after approximately six seconds. Editing, replacing or hiding its source clears it. Reduced motion retains a static indication until replacement/cleanup with no running animation timer. The existing explicit `progress` reading phase uses the same treatment. Reading does not raise the application's OS window or expose the agent-management dock.

Plain `ctl read` remains an observation without presentation; `read-file` remains the existing project read. Ordinary `cat`, a model's own file tool and filesystem access do not automatically emit reading activity. A vendor-neutral agent instruction is: “Use `shed ctl read <relative-file>` for reads, continue from `nextOffset`, and keep making your normal filesystem edits while Shed watches.” No Codex-specific watcher or log parser is added.

## Checked first-file opening

The existing `editor/file/open` accepts an additional explicit project-targeted request shape:

```json
{"schemaVersion":1,"instanceId":"...","windowId":"...","projectId":"...","path":"src/App.java"}
```

The fully context-checked request shape remains supported. The client uses the project-targeted form for `ctl open` and for the opening phase of `ctl read <path>`.

Authentication, instance/window/project identity, root generation and path policy are checked before preparing an opening context. An internal `captureForOpen` snapshot can represent an untitled buffer using its real identity, revision, caret and a nullable path. It is only an installation precondition; ordinary metadata/source observation still rejects untitled buffers. It is never returned as a source observation grant. A tree target selects the existing source pane once, while later checks reject pane/project/source changes instead of retargeting.

The existing pinned-root `ProjectFileOpen`, `ProjectDocumentInstall` and `PaneBufferController` prepare and install the requested file. Their commit checks compare the actual pane, document/revision, interaction context and project again. A human edit before installation rejects the operation and preserves the draft. An unsaved untitled buffer remains in the document store. After installation, the reply additionally verifies the requested path and pane; a human switch cannot turn the request into a read of another document.

The CLI then submits `editor/source/read-visible` with the returned context plus `offset`, `limit` and `agent`. The server uses the existing bounded/grapheme-checked read path and rechecks the context before applying a reading decoration on the EDT. The client validates the existing strict read-reply schema. Opening and reading are separate requests: failure can leave the intended file open without delivering a read; it does not authorize blind retries or source writes.

Opening initially requested keyboard focus before capturing its installation precondition. Native tests caught that focus event invalidating the same request. The final path selects the content pane without requesting keyboard focus; an explicit file open can retain focus in the existing Explorer, while caret/selection navigation retains its original stricter focus policy. The CLI obtains one fresh navigation context after opening for settled viewport geometry and verifies the pane, document, source revision, caret/selection and dirty state still match the opening result. There is no automatic retry loop or acceptance of another document.

No authorization scope, automatic approval, document model, source-edit endpoint, native dependency, permanent worker or configuration preference is added. Transport and file preparation remain off the EDT. Existing machine-navigation mode, modal/picker, alternate-surface and complex-layout restrictions still apply. The nullable internal opening state does not broaden observation access to untitled drafts.

## Verification and limitations

The [evidence index](performance/agent-reading-follow/INDEX.md) records commands, final counts, desktop results and intermediate failures. The desktop fixture starts with a fresh project and blank source, sends an idle source click, observes a new file opening, and verifies existing edit tours. A separate bounded Java CLI process reads two files through the authenticated transport; the first read starts with the tree active and an untitled content pane. It checks exact returned source, real file switching, highlighted offsets, viewport reveal, preserved source/caret, hidden management panels and expiry. Only the owned Swing root is captured.

The unrestricted native-shell benchmark remains blocked. Windows/Linux desktop, physical input/scanout, multilingual glyph completeness, Unicode first-edit stalls, stock HTML-preview latency and whole-process native-memory attribution retain their previous qualifications. The separate disk-reload behavior that collapses a nonempty selection to its caret is unchanged. This feature does not claim automatic observation of arbitrary agent reads.

## Try it with an external CLI agent

Use the rebuilt launcher, an existing Java 21 runtime and a fresh visualization window. Old running windows retain their old implementation. In the same terminal that will run the agent:

```sh
export SHED_BIN=/absolute/path/to/shed/scripts/shed
export SHED_CONTROL="$("$SHED_BIN" visualize /absolute/path/to/project | python3 -c 'import json,sys; print(json.load(sys.stdin)["descriptor"])')"
"$SHED_BIN" ctl read Demo.java --offset 0 --limit 2048 --agent "My agent"
```

The descriptor selects the correct window; its private credential is resolved by the CLI and should not be copied into prompts. The environment must be inherited by the agent process. An example instruction is: “Use the selected Shed attachment to visualize this project. Before reading a chunk, run `"$SHED_BIN" ctl read <project-relative-file> --offset <UTF-16-offset> --limit 2048 --agent <label>` and use its JSON source. Continue from `nextOffset` as needed. Keep editing the files normally; Shed will visualize those filesystem changes. Do not turn them into review proposals solely for visualization.”

Attachments retain their existing expiry. If one expires, use a newly authorized attachment/window rather than bypassing authentication. Do not infer source correctness from rendered glyphs or claim a read indicator proves model comprehension.
