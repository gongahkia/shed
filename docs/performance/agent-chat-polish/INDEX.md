# Chat polish and optional inline completion

Starting clean HEAD: `60e23b8aec96f9f81b50eda973524b175594a8b0`. This pass refines the existing right-hand CLI Chat and OpenCode completion foundation. The user selected fast inline suggestions; predictive rewrites and edit-location jumps are excluded. No new provider, renderer framework, dependency, document model, permission bypass or font/theme default is introduced.

## Delivered behavior

Chat has a compact composer with its provider picker and Send/Stop action, quieter header actions and explicit executable settings. Selected-source chips retain exact revision/range payloads with simpler visible labels. Completed replies support native selectable styled paragraphs, emphasis, lists and code. Existing CommonMark parses only bounded text; model HTML remains literal and images are not fetched. Streaming appends deltas instead of replacing the full transcript every tick; only the current reply is formatted when it finishes. Retention is bounded by 98,304 raw message characters and 128 completed messages. No permanent new worker is used. See [chat guide](../../AGENT_CLI_CHAT.md).

Inline suggestions remain optional and off by default. **View → Inline Code Suggestions**, palette enable/disable actions and `:agent complete on|off` share existing validation/persistence. Default idle delay changes from 500 to 250 ms; explicitly saved delays remain unchanged. Tab accepts the rest, Ctrl/Command+Right accepts a word or complete grapheme and keeps the remainder without another provider request, and Escape dismisses. Custom Vim keys and other completion UI retain priority. Completion-only settings changes cancel stale requests without rebuilding the source TextUI. Disabled insertions avoid deferred automatic-request callbacks and redundant repaints. See [completion guide](../../INLINE_CODE_SUGGESTIONS.md).

## Reproducibility and boundaries

Host: macOS 26.5.2 arm64, eight logical CPUs. JDK: Temurin 21.0.13.1+2. Maven: 3.9.11. Artifact, host record and all 1,581 matching packaged main-class identities are in `artifact-final.json`; JAR SHA-256 is `d694826a066599dff232f418b3f4ab611123c970c315671cafa0946df37bfcc7`. No heap/graphics/OS settings were adjusted to improve results. Native samples have no contemporaneous system-load record and no matched prior artifact control, so they establish bounded checks, not comparative performance claims.

Safe Maven invocations use the established source-reviewed admission path, isolated home/preferences/temp and a bounded copy of the offline dependency cache:

```text
python3 scripts/run-portable-tests.py
  --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21
  --maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
  --repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
  --output <new-json-file>
```

Exact argv, classes, logs, elapsed times and fixture removal are retained in compressed JSON. Gzip files contain ordinary JSON, readable with `gzip -dc <file>`. No personal project, real coding CLI/model, language server, interactive shell or B0.1 native-shell harness is launched. This source-reviewed fixture is not an OS sandbox.

| Check | Actual result | Evidence |
| --- | --- | --- |
| Broad admitted Maven selection, every manifest name except retired `ProjectFileOpenPrerequisiteTest` | **577 passed, seven existing skips, zero failures/errors; 584 total** | `regression-release.json.gz` |
| Final focused: `--tests AgentChatTranscriptTest,AgentCliChatPanelTest,InlineCompletionSessionTest,InlineCompletionKeymapTest,InlineCompletionDisplayTest` | **28 passed**, zero skips/failures/errors after the final appearance tweak | `final-focused.json.gz` |
| `--package-only` | Passed; tests run separately | `package-final.json.gz` |
| `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, disposable fixture environment | **116 passed**, zero failures | `tooling-final.json.gz` |
| `JAVA=/Users/gongahkia/.local/share/shed-tools/jdk-21/bin/java make demo-chat` | **Passed** against final packaged app; completion intentionally `NOT_REQUESTED` | `make-chat-final-*` and PNGs |
| Existing packaged media driver: `python3 scripts/test-packaged-markdown.py --jdk <above-jdk> --jar <absolute-target/shed-2.0.0.jar> --output <new-json-file>` in disposable environment | **Passed** Mermaid/math/local-image smoke; run after paint measurement | `media-final.json.gz`, `media-driver-final.json.gz` |

Counts are separate, overlapping selections, not additive totals. The broad run preceded the last welcome-title/status color adjustment; the final focused run, package, live demo and media use the final production source. Seven skips are the existing opt-in measurement/data-loss fixtures. No full unrestricted Maven/native/GUI suite is claimed. No admission assertion or existing test was weakened.

Component regressions include Markdown/code attributes, HTML literal handling, selection preservation during stream appends, final-response replacement, transcript bounds, default-off and saved-delay behavior, word/remainder acceptance with no extra request, combining/Indic/ZWJ boundaries, exact Undo/Redo, disabled/no-provider behavior and listener retirement. Existing preview pixel/geometry comparisons include wrap/resize/tab cases. Existing stale/cancel/size/backoff tests remain.

## Live UI and performance evidence

The final default Chat demo exercises real app controllers and authenticated bridge with a bounded external **fixed Java CLI double**, disposable files/configuration and owned-window captures. It verifies right docking, narrow composer bounds, focus-mode restoration, normal bottom tools and detached Search, selection context, two native-ID turns, source reads/edits, idle bridge rejection, Stop, source ownership and draft preservation, New Chat and zero remaining task IO workers/children. Synthetic external sentinel content hash and modification time were unchanged; fixture cleanup passed. Captures show the completed formatted replies and compact composer. No desktop-wide capture is used.

Final post-chat edit-to-`paintImmediately` check: 40 samples, **first 11.555 ms, p95 9.079 ms**, below the existing 16 ms p95 component target. This includes the first sample and excludes OS key delivery, EDT admission and compositor display. It is a small supported fixture, not whole-IDE input-to-screen qualification. RSS, native font memory and retained-heap equivalence are unmeasured. Existing OpenCode connection/timeout/cancellation resources are reused; default-off does not initialize a new model process.

A precursor controlled fixed-response inline run (`live-04-*`) passed automatic preview, word acceptance, remaining Tab acceptance, exact native buffer Undo/Redo and menu disabling. Idle was 250 ms; first synthetic insertion to completed preview was **261.601 ms**, including that idle. Preview layout was **2.237 ms**, fixed-response readiness **3.290 ms**. Post-chat 40-sample paint p95 was **1.081 ms**. These are single controlled runs, not real-model latency measurements or final unattended native input qualification. `precursor-fixed-completion.png` shows that ghost presentation. No speed/quality parity with proprietary prediction models is claimed.

## Preserved failures and native focus limitation

- `compile.json.gz`: initial factory-brace compilation failure; corrected before tests. `focused.json.gz`: a new toggle button did not match the existing quiet-button API; changed to an ordinary settings button. Assertions remain.
- `live-01-*`: Chat checks passed, but the probe did not descend `JMenu` popup children, so its checkbox lookup was null. The fixture lookup was corrected.
- `live-02-*`: native source-focus timeout. No bypass was used.
- `live-03-*`: preview source mismatch. `live-05-*`: partial-acceptance mismatch. Neither has sufficient mutation tracing to conclusively attribute the individual failure; both remain explicit failed runs.
- `live-06-*` reproduced a preview source mismatch with a bounded document mutation trace. The additional insertions came through Swing's `DefaultEditorKit$DefaultKeyTypedAction`, while the experiment had explicitly brought the fixture to the foreground. This attributes this reproduction to native typed-key input, rather than the completion preview insertion path. `live-06-source-mutations-redacted.json` retains hashes, UTF-16 lengths and mutation call stacks; incidental native typed text is intentionally omitted. Original diagnostic evidence remains in the task temporary directory, unchanged.

The final probe removes forced foreground activation. **`make demo-completion` is explicitly interactive**: click its own editor when prompted and do not type during its source checks. Default `make demo-chat` does not run the focus-sensitive completion stage. Timeouts and concurrent changes fail rather than relaxing focus/revision/source assertions. Final unattended native-completion acceptance remains **OPEN**; the earlier positive does not erase failed runs. The new Python admission tests reject `--inline` without `--chat` and retain preexisting-output protections before any process launch.

## Remaining limitations and repository compliance

Real provider speed/quality, Windows/Linux desktops, full keyboard-to-screen latency, startup/RSS/native-memory comparison, predictive rewrites and cross-file jumps are unqualified/outside this pass. Existing Unicode glyph/layout and HTML-preview EDT limitations remain open. No B0.1 remediation was attempted.

Changed production files are limited to the Chat transcript/panel and completion settings, display/session/controller, command/menu/palette/settings wiring. Tests/admission lists, the existing owned-window probe/driver, Make demo entry and these guides/evidence accompany them. User source/project files and saved preferences are not used as fixtures. README before/after SHA-256 remains `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing work/evidence is preserved. Atomic local commits are authorized; no push is performed. `git diff --check` passes.

Production commits: `da0788617` (Chat presentation and tests) and `7207677e4` (optional completion controls, partial acceptance and regressions). Demo/tooling and this evidence are kept in the following documentation/test commit. No pushes were created.
