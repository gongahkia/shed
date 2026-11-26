# Unified Chat UI verification

This refines the [ongoing CLI chat](../../AGENT_CLI_CHAT.md), starting from clean HEAD `790a784084baa483611565fc0928ea14acc8549f`. README's before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No real provider, language server, native-shell benchmark, unrestricted desktop capture or personal project fixture was used.

## Delivered behavior

- One **View → Agent Chat…** entry and one normal Chat surface on the right. The duplicate AI Assistant menu entry and permanent Chat/Review/Live/Advanced strip are removed from normal use. Changes/review/legacy tools remain explicit actions with **Back to Chat**.
- The existing tool host/split is reused. Chat uses left/right orientation; Search and other normal tools retain top/bottom orientation. Per-orientation dimensions and focus-mode restoration are retained. Detached tools remain independently refreshable and return to their normal dock when closed.
- Each open-editor tab renders its own close icon/hit target instead of a separate far-right close button. The same document-close/discard decision is reused. Closing an inactive tab restores the previous active document when available. Keyboard Delete still closes the selected tab.
- Claude marks are tinted orange centrally in `ProviderIcons`, covering chat/model selectors, source activity and terminal decoration. Converting the indexed PNG to ARGB before tinting preserves its alpha silhouette and allows the new color. No downloaded/replaced assets or new icon framework is involved.
- **Add selection to chat** captures bounded, exact UTF-16 source text, observed revision and range into removable chips. It neither sends a message nor mutates source/history/selection. Attachments are consumed by one submitted turn, survive cancelled approval with the draft, and cannot silently cross project boundaries. Prompt UTF-8 size is checked before consent/launch.

## Commands and results

The established `scripts/run-portable-tests.py` admission path used:

```text
--jdk /Users/gongahkia/.local/share/shed-tools/jdk-21
--maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
--repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
--output <new-json-file>
```

- Focused `--tests AgentCliChatPanelTest,ProviderIconsTest,DocumentWorkbenchTest,AgentDelegationTest,ArchitectureBoundaryTest`: **22 passed**, zero skips/errors/failures (`focused-02.json.gz`).
- Broad safe selection: all source-admitted names except the previously retired `ProjectFileOpenPrerequisiteTest`: **557 passed, seven existing skips, zero failures/errors**, 564 total (`regression-release.json.gz`). Exact classes/argv are retained in the report. No complete native/GUI suite is claimed. The six opt-in measurement skips and existing data-loss fixture skip remain.
- Final overlapping `--tests AgentCliChatPanelTest,AgentDelegationTest,ArchitectureBoundaryTest`: **17 passes**, zero failures/errors/skips after extending the input exemption to the Chat header (`ownership-final.json.gz`). These are not added to the broad count.
- Package `--package-only`: passed. Packaging skips tests only after the separate safe regression run.
- `python3 -m unittest discover -s scripts -p 'test_*.py' -v`: **114 passed** in a disposable fixture environment (`tooling.json.gz`).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 make demo-chat`: paced real Swing application + bounded fixed Java CLI double. The resolver warns about the known nonexistent JAVA_HOME and uses the app-owned JDK. The enhanced probe covers all new UI paths while retaining the real authentication, read/write/follow-up, cancellation and IO-retirement checks.
- Packaged Mermaid/math/local-image smoke **passed** for the final artifact (`media-qualified*`), using the existing driver in a disposable fixture, after live paint measurement. It does not use the B0.1 harness.
- `git diff --check` and README identity pass. Local commits are authorized; no push is performed.

## Live evidence

The source-reviewed `AgentCliChatDesktopProbe` compiles against only the packaged JAR, uses a disposable project/home/preferences/temp and fixed direct Java commands, and verifies the external synthetic sentinel's content hash and modification time. It captures only its own Swing root. The final `make-qualified-*` evidence and PNGs are stored alongside this index. All checks passed, the sentinel hash/mtime were unchanged, the fixture was removed and remaining task IO workers were zero.

Covered checks include right-side orientation, no duplicate menu/default tab strip, normal bottom-tool restoration, focus-mode restoration, narrow-window composer bounds, orange Claude picker, explicit repeated review/back navigation, source-selection attachment through the actual context action, exact context in the next external client prompt, unchanged source/revision/Undo/selection while attaching, inactive-tab close targeting, chat hide/reopen, two native conversation turns, idle authorization rejection, Stop, preserved draft and zero remaining task IO workers. The final probe additionally exercises detached Search refresh and reattachment.

Completed-paint samples append 40 characters after the chat stops and call `paintImmediately` on the small disposable source. Samples include the first operation and exclude EDT admission, OS input delivery and compositor presentation. They are bounded component checks against the existing 16 ms target, not a full input-to-screen or matched whole-IDE benchmark. The final 40-character run measured **first 2.544 ms, p95 0.815 ms**; raw samples are in `make-qualified-result.json.gz`. Whole-process RSS/native-memory equivalence is not established.

## Preserved failures and limitations

`focused.json.gz` preserves the first orange-icon test failure: applying an orange fill directly to the indexed-color image retained a monochrome palette. The production renderer was corrected, without weakening the pixel assertion. `live-01-*` and `live-02-*` preserve an incorrect test lookup: the workbench Tools toolbar button shares the name `workbench.tools` with the dock's tab strip. The selector was scoped to the actual tool host; the single-surface assertion remains. `live-03-*` passes with the original live refinement checks; final Make evidence includes the later narrow-layout and detached-tool assertions.

Real Codex/Claude/OpenCode behavior is not newly qualified by the fixed Java double. Windows/Linux desktops, native-shell confinement, complete native-memory attribution, Unicode glyph/layout limitations and preview stalls remain open. No source/document architecture, default font/theme, stored preference schema, provider authentication, agent permissions or new dependency is introduced.

The qualified code commit is `11267be5ee76c530dd47d1fade233ad8758b65eb`. The final JAR SHA-256 is `e351ee43946225b9311874522fea879853bc180116c31c43e298ba0df94a5b7b`; all 1574 compiled main classes match its packaged bytes (`artifact-check.json`). Packaged media is recorded separately in `media-qualified*` for that same artifact. Earlier package/media runs remain preserved with their own identities.
