# External editor-control and agent-presentation acceptance

See [the command/user guide](../../AGENT_EDITOR_CONTROL.md). This is scoped engineering acceptance for existing-file, reviewed source changes in the tested macOS Java 21 workbench. It is not whole-IDE, multilingual or public-release qualification.

## Results

- Final source-reviewed Maven selection: **366 total, 360 passed, six existing measurement opt-in skips, zero failures/errors** (66 classes). Full native/process-dependent suites remain blocked; the older path-race characterization was deliberately not repeated.
- Final Python tooling: **91 passed**, zero failures/skips. Four new synthetic checks cover the disposable live-client admission environment and unchanged external sentinel.
- Offline packaging: passed. Packaged Mermaid/math/local-image smoke: passed.
- Final actual-window/external-client replay: **PASS**. Exact review/apply/Undo/Redo, asynchronous save completion, zero remaining child processes, private endpoint cleanup and fixture removal passed. The saved generated Java file compiled successfully with `javac -proc:none`.
- Live synthetic key-action → actual workbench completed-paint: first **1.40 ms**, warm **p95 1.32 ms** (20 warm samples, 21 total) on the small demo source. Raw samples are retained. This is not native OS keyboard delivery, display scanout or a large-workload performance result. A packaged media smoke was also running independently during the final live run; no matched-version speedup/regression claim is made.
- Human caret color `-13213771` and agent marker color `-14188196` differed in the live window; human caret/source ownership remained intact.

`gpt-6.1-sol` with requested low reasoning actually used separate CLI processes to open `Demo.java`, read authoritative editor text, show pending activity and submit `proposal-1464bb97d078c62c5219838c695011dc` with `reviewRequired=true`. It did not write the source file directly or apply remotely. The live harness subsequently clicked the actual review Apply button and verified atomic Undo/Redo. Final replay reused the exact generated payload to finish all acceptance checks. `model-client.json` records the executed model, client actions and generated payload; it contains no key material.

## Evidence files

| File | Contents |
| --- | --- |
| `control-compile-1.json.gz` | Initial compile failure in the new presentation listener, corrected before testing |
| `control-focused-1.json.gz` | 63 initial focused passes; overlaps the final selection |
| `control-safe-regression-release.json.gz` | Final 66-class selection, exact command/full log/fixture cleanup |
| `tooling-final.json.gz` | Final 91-tooling-test command, output and cleanup |
| `control-tooling-release.json.gz` | Earlier overlapping 91-test tooling run |
| `control-package-release.json.gz` | Final offline package command, full output and cleanup |
| `control-media-release.json.gz` | Final packaged media smoke, artifact identity and output |
| `agent-workflow-live-*.json.gz` | Every live attempt and final replay, including failures below |
| `model-client.json` | Real Codex low client command results and generated payload provenance |
| `codex-generated-demo.java.txt` | Exact model-generated replacement used in the successful replay |
| `source-hashes.json` | Production source identities accompanying the tested artifact |
| `01-source-before-agent-activity.png` | Actual workbench source before decorations; captures also exhibit the existing source-text artifact |
| `03-review.png`, `05-saved.png` | Actual diff review and saved-result workbench captures |
| `agent-workflow.gif` | Sequence of actual pending/review/applied frames, with pulse/fade changes; no fabricated source typing |

The tested final JAR SHA-256 is `94a2ee7b6c7dffa0c2f518384a992f8580e1689285c8f72456aaf9665b99ba5e`. The JDK is temporary Temurin 21.0.13.1+2 aarch64 on macOS; Maven is 3.9.11. Archive checksums and host details remain in [the preceding environment record](../agent-tracka222c1/environment.json). Production changes are committed separately from these opt-in acceptance fixtures/evidence.

## Reproduction and process safety

Use the existing reviewed runner, supplying existing JDK 21, Maven and an offline cache:

```text
python3 scripts/run-portable-tests.py --jdk <JDK21> --maven <Maven-home> \
  --repository <offline-cache> --tests <all reviewed manifest names except ProjectFileOpenPrerequisiteTest> \
  --output <new-safe-results.json>
```

The exact 66-class list and JVM/Maven argument array are in the raw log. Packaging used the same runner with `--package-only`, without test-selection options. No guard or assertion was weakened.

Tooling ran directly under `native_fixture.Fixture` with controlled environment, isolated home, disabled Python user site/bytecode, a 40-second timeout and cleanup in `finally`:

```text
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

The live path is separately assessed from the unqualified native-shell benchmark harness. `scripts/test-agent-desktop.py` compiles only the fixed `AgentWorkflowDesktopProbe` with annotation processing disabled, then launches that fixed probe with a visible actual `Texteditor`. Home/history/startup/config/cache/state/preferences/cwd are disposable. Executable PATH is an owned empty directory, preventing discovery of personal shell tools or language servers. No interactive terminal, shell fixture, real language server or agent process is launched by Shed. The external model is the explicitly requested Codex delegate using the local CLI, not a model hosted by Shed.

The controlled live-client helper validates fixture identity, owner/private permissions, canonical confined state paths, expected empty startup files, absence of credential/runtime injections and the fixed JAR hash. It launches only the full-path Java executable with the existing headless CLI and bounded request/response/timeout. Credentials remain owner-only files; key contents are not arguments or logs. A trusted JDK executable and trusted benchmark source are assumptions; these fixtures are not an OS sandbox against arbitrary malicious native code.

For an interactive client demonstration:

```text
python3 scripts/test-agent-desktop.py --jdk <JDK21> --jar <fresh-shed.jar> --output <new-capture-dir>
```

It prints a disposable `LIVE_DEMO_SESSION` path. An external client then uses `scripts/agent-demo-client.py --session <that-path> --operation open|read|activity|propose`; proposal payloads belong under that fixture's `run/work`. The helper knows the correct readiness path and waits for it for at most 20 seconds. The demo uses ASCII coordinates; it rejects non-ASCII demo offsets rather than confusing Python indices with UTF-16.

For deterministic replay, add `--replay-payload <codex-generated-demo.java.txt>`. Both client and visible app are real separate processes. The probe has its own finite watchdog; the driver has bounded process waits/termination and deletes only its own fixture. Every recorded live fixture was removed. Normal app startup has no new listener or test worker.

## Failed attempts retained

1. Live attempt 1: test-probe compilation referenced a private field directly. Corrected to fixed-field test reflection; no app process launched.
2. Attempt 2: actual app reached ready, but helper rejected the presence of the fixture's own empty `BASH_ENV` file. Client correctly stopped before execution. Corrected to require that exact owned empty file; injection/external-sentinel tests still reject unsafe startup paths.
3. Attempt 3: the delegate checked `run/ready.json` rather than `run/live/ready.json`. It stopped without using the client. Readiness handling moved into the helper; this was not an app startup failure.
4. Attempt 4: actual Codex open/read/activity/propose and human review/apply/Undo/Redo passed; the test omitted the synthetic key-event timestamp, so no input-to-paint sample existed. Corrected the probe to use production key-marking plus its synthetic typing action. No production performance behavior changed to obtain a sample.
5. Attempt 5: typing passed (warm p95 0.80 ms), but the test read disk immediately after the normal `Saving…` acknowledgement. Corrected to await actual asynchronous disk completion and cleared dirty state; no timeout was enlarged or save assertion removed.
6. Attempt 6: full replay passed (warm p95 0.77 ms), including save and cleanup.
7. Final release replay: full pass against the final JAR, warm p95 1.32 ms, saved source compilation successful. These repeated results are not additional independent test-case counts.

App-owned captures show source-text artifacts even without agent overlays, while proposal text and source bytes are independently checked. They do not establish native-screen/multilingual visual correctness or resolve the existing glyph/layout finding. Unicode first-edit, HTML-preview EDT stalls, native-memory attribution, broader workload and Windows/Linux GUI qualifications remain open. Whole-process retained-memory/startup equivalence is not claimed from this test.

## Repository compliance

README SHA-256 before/after is unchanged:
`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

Only authorized source/control/presentation changes, focused tests/admission entries, opt-in fixtures and documentation/evidence were added. Existing internal-integration work and previous evidence were preserved and committed first. No dependency or theme/font/default-preference change, unrelated redesign, native-shell benchmark bypass, unrestricted desktop capture, source-renderer replacement or remote tool execution endpoint was introduced. Commits are local; no push was performed.
