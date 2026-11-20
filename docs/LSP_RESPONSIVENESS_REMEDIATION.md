# Track B1A — LSP input blocking and event-loop safety

## A. Reproduction

**The confirmed Go to Definition response wait has been moved off the EDT. Overall qualification is PARTIAL:** the targeted production correction passes the covered in-process workflows, but this is not certification of every LSP operation, native provider or desktop configuration.

B0 measured 210–216 ms queued-input delay for a valid 200 ms definition response. B1A reproduced the same transport boundary without a native process: an in-memory `Process` double supplies JSON-RPC frames to the real `LspClient` reader, serializer, capability model, response queue and parsers. Three pre-correction samples delayed the EDT barrier **207.21 / 205.48 / 205.55 ms**. Captured EDT stacks contain:

```
LinkedBlockingQueue.poll
LspClient.waitForResponse
LspClient.sendRequest
LspClient.sendTextDocumentPositionRequest
LspClient.textDocumentLocations
LspClient.textDocumentLocation
LspClient.definition
```

The B1A probe calls this existing client operation on the EDT, matching the blocking boundary established by B0's actual workbench command. It does not construct a full `Texteditor` window or claim to rerun the entire native workbench path. B0's current production source confirms `CommandHandler`/`:lsp definition` → `LspController.lspGoToDefinition` → that synchronous client call → `openLspLocation`.

Native-shell benchmarks, real language servers, interactive terminals and the rejected B0.1 backend were **not run**. Trusted Java/Maven/Python build and verification processes used disposable environments; the LSP peers themselves are Java objects with no OS process or socket.

## B. Root-cause attribution

The EDT held the synchronous request call until `waitForResponse` returned. The peer's ordinary 200 ms compute delay therefore prevented unrelated input dispatch. It was not necessary to blame provider algorithms, glyph layout, document parsing or disk I/O to establish this defect.

Two related cancellation conditions were independently reproduced before their corrections:

1. `sendRequest` used a Java monitor for the shared response consumer. A canceled definition worker waiting behind a held hover request could not leave that monitor wait on interruption. The regression failed its one-second cancellation bound before the change and passes afterward, without releasing the unrelated response first.
2. `Future.cancel` released queued task callables but left canceled `FutureTask` nodes in the existing `ThreadPoolExecutor` queue. With the worker deliberately occupied, **32 canceled nodes remained queued** despite a bounded job-history map. Removing the exact canceled, not-started future from the standard worker queue produces **zero nodes** under the same test. This was queue-object retention until workers advanced, not demonstrated source corruption or a document/view graph leak.

Concurrent notification polling was another necessary ownership boundary: the active request must remain the sole consumer of its response. EDT polling now tries the request lock without waiting; the worker consumes notifications while awaiting the response. No duplicated response consumer or UI wait was added.

## C. Before/after measurements

Final fresh-process, same-host component control: three synchronous and three asynchronous samples with identical 200 ms peer scheduling and 4 KiB ASCII source. Samples are chronological; these small groups are ranges/individual observations, not population p95 claims.

| Boundary | Synchronous control | Async correction |
| --- | ---: | ---: |
| First command EDT occupancy | 213.71 ms | **12.87 ms** |
| Subsequent command EDT occupancy | 207.19 / 208.97 ms | **0.108 / 0.292 ms** |
| Queued input dispatch after peer receives request | 207.08–213.71 ms | **0.034–0.111 ms** |
| Queued input through completed component paint | 208.26–215.74 ms | **0.674–1.498 ms** |
| Response publication through callback/component paint | 2.03–8.14 ms | 1.69–4.49 ms |
| Command EDT allocations, first sample | 289,104 bytes | 268,152 bytes |
| Command EDT allocations, subsequent samples | 11,984 / 12,600 bytes | 904 / 904 bytes |

The queued barrier is posted after the peer observes the request. It does **not** imply that initial command dispatch itself costs 0.1 ms; the first async dispatch occupied 12.87 ms. It includes first-use submission/class/thread work. Other retained runs observed first async dispatch around 3–4 ms; neither result is discarded.

The provider still takes approximately 200 ms. This correction improves unrelated input availability; it does not make language analysis instantaneous. Response/component-paint timing is **not** target-file loading or full-window navigation-to-visible-paint. Production uses its existing asynchronous file-open path after acceptance; real target-disk/render timing remains unqualified here.

Typing measurements enqueue the default typed action, mutate the actual `PlainDocument`, paint the `SourcePlainView`-adapted component, then Undo outside the timing interval. They use Shed Light and bundled JetBrains Mono at the existing default 16 points, 800×500 offscreen pixels, short ASCII lines, no preview. Each phase retains its first sample separately and 50 subsequent samples; p95 is the nearest-rank observation.

| Source | First control / pending-response input | Warm p95 control / pending-response input |
| --- | ---: | ---: |
| 4 KiB | 2.613 / 0.341 ms | 0.575 / 0.384 ms |
| 100 KiB | 0.463 / 0.300 ms | 0.329 / 0.296 ms |
| 1 MiB | 0.476 / 0.256 ms | 0.262 / 0.256 ms |

All measured component phases satisfy **≤16 ms**. These are enqueued synthetic typing-to-completed **headless component** paints, not physical input or a new full-workbench typing qualification. Earlier exploratory component samples excluded queue time and are retained separately; they are not relabeled or pooled with this final boundary.

First graphics/configuration/view setup and paint in the fresh performance JVM took **3,041.84 ms**; the later fixture setup took 4.84 ms. That first-use cost remains visible, includes font/view preparation, and was not further attributed or removed by B1A. A separate full safe-suite measurement is retained, with its warmed class/font state explicitly distinguished. No cold-cache, startup-regression or universal first-paint claim follows.

## D. Files changed and rationale

| Production file | Narrow change |
| --- | --- |
| `src/main/java/shed/LspController.java` | Capture/flush the origin on EDT, use existing async server startup if unavailable, delegate definition work, validate current binding before navigation, retire requests on edits/server management/shutdown; marshal server edit-review UI to EDT |
| `src/main/java/shed/LspDefinitionRequests.java` | New per-window EDT-owned coordinator: one job, one latest replacement, generation/cancellation checks and explicit callback/reference retirement |
| `src/main/java/shed/LspClient.java` | Package-private injected-`Process` transport seam; unchanged real launcher validation; interruptible shared-consumer lock and nonblocking notification drain |
| `src/main/java/shed/AsyncJobService.java` | Remove the exact canceled queued future from the production `ThreadPoolExecutor`; retain existing status/token/completion/history behavior |
| `src/main/java/shed/Texteditor.java` | Narrow cancellation bridge used by existing pane/navigation owners |
| `src/main/java/shed/EditorUiController.java` | Cancel pending definition when rebinding the active pane |
| `src/main/java/shed/PaneBufferController.java` | Cancel pending definition at synchronous/asynchronous file-open intent, before delayed file installation |
| `src/main/java/shed/SessionConfigController.java` | Cancel before discard confirmation, since a modal dialog can dispatch queued navigation completions |

New test sources:

- `InProcessLspFixture.java`: bounded-workload in-memory JSON-RPC peer and reader lifecycle.
- `LspDefinitionResponsivenessTest.java`: original synchronous wait reproduction and EDT stack capture.
- `LspDefinitionRequestsTest.java`: context, source, navigation callback, cancellation, split/Undo and disposal cases.
- `LspTransportConcurrencyTest.java`: interruptible contention, notification progress and completion/definition/reference response ownership.
- `LspQueuedCancellationTest.java`: saturated-worker queue-node regression.
- `LspDefinitionPerformanceTest.java`: explicit opt-in paired component and disposal measurements.

Test/benchmark infrastructure:

- `src/test/resources/shed/portable-fixture-tests.properties`: extend the source-hashed admission manifest from eight to **34** reviewed non-native classes; native/whole-window classes remain excluded.
- `scripts/run-portable-tests.py`: explicit `--measure-lsp` opt-in and mutually exclusive `--package-only` using the existing offline Java/Maven path. Packaging skips tests and reports that phase honestly; it does not admit native fixtures.
- `scripts/test_native_fixture.py`: exact reviewed-class set, additional native-LSP rejection checks, package-mode exclusion checks and smoke command-construction coverage. Existing safety assertions retained.
- `scripts/test-packaged-markdown.py`: explicitly redirect compiler/runtime Java home, temporary directory and preferences into its already disposable scratch directory; parent HOME alone does not redirect those JDK properties.
- `docs/LSP_RESPONSIVENESS_REMEDIATION.md` and `docs/performance/ide-trackb1a/`: this report, commands, raw successful/failed evidence, source versions, identities and compliance.

No dependency, document/Undo model, renderer, setting, keybinding or appearance feature was added or redesigned.

## E. Request, cancellation and revision safety

The EDT captures canonical buffer and pane references, client identity, source path/URI, monotonic document revision, synchronized LSP version, directed selection and UTF-16 line/character. Existing `syncLspOpen` and pending-change flushing remain before dispatch, including direct `:definition` callers. Initial open/full-sync source transmission remains legitimate synchronization; it was not removed to improve a number.

Only client/URI/position values enter the worker task. The worker does not inspect Swing components or read the authoritative document. `AsyncJobService` returns completion to the EDT; it is not wrapped in a second completion hop. Acceptance requires the original live active pane/buffer, attached document, revision, path, URI, LSP version, selection and still-current live client. Editing and Undo back to identical text still invalidate the old monotonic revision.

Repeated requests replace only the latest pending origin. The previous job is canceled, and replacement work starts after its completion retires. Old successful or canceled completions cannot use a new request's context. The saturated-queue correction handles canceled futures that had not started; the standard production worker pool no longer accumulates those nodes in the covered test. This does not redesign arbitrary custom executor implementations or the long-running virtual-thread executor.

Edits to the origin, pane rebinding, file-open intent, explicit server stop/restart/workspace stop, discard confirmation and window shutdown invalidate pending navigation. Unrelated document edits do not cancel the origin request. Close releases callbacks, current/pending origins and the job-service reference even if completion is already queued. Shared documents/history are not disposed by request cancellation.

If no live client exists, Definition reuses the existing automatic background launcher and returns an unavailable/starting message. **The user must retry once ready**; no automatic jump is queued against an unknown future cursor. This deliberately avoids the previous synchronous startup path. Real native server launch/restart/disconnection remains unqualified by these tests.

Server-originated workspace edits continue to be declined pending user review. Their existing review UI now runs on the EDT when the request arrives during a background definition wait, without making the worker wait for a modal UI acknowledgement.

## F. Navigation and editor correctness

Covered in-process scenarios include immediate/held/empty/error/throwing responses, multiple locations, cancellation before execution and during a response, interruption behind another feature, late canceled results, 50 rapid replacements, two independent clients, source edits before debounce, edit→Undo back to original text, path/URI/version/client changes, buffer/pane/selection changes, pane disposal, server disconnection and shutdown, and completed results queued before close.

The existing first-location policy is preserved. On valid acceptance, production still calls `openLspLocation` and its existing jump-history and asynchronous file-open machinery. No second navigation owner or replacement document is introduced. Source/history tests assert exact contents, unchanged revisions/dirty state/Undo for read-only requests, unchanged caret, and history only for accepted callbacks. Pending-request tests edit through two shared surfaces, close active/inactive panes, and verify exact Undo/Redo through the survivor. Server edit-review tests verify the declined wire response and EDT execution without source changes.

Existing admitted tests additionally cover LSP completion application and disk workspace edits, encoding/BOM/mixed newlines/EOF, atomic saves/recovery, document identities, navigation history, Plain/Emacs and Vim-overlay routing, source-view geometry and bounded reads, grapheme behavior, Markdown atomic edits/link navigation/highlighting, theme roles, density and centered-width calculations.

**Boundary:** the new coordinator uses real buffers/panes/client transport with a headless navigation callback. Full `Texteditor` target-window installation, physical keyboard routing, actual modal windows and full 8G.1 native/GUI scenarios were not executed. Existing document-open admission remains responsible after an accepted location begins loading; target files changing during that load were not newly redesigned or certified.

## G. Memory, concurrency and lifecycle

No new pool or permanent worker was created. Requests use the existing bounded short-work pool. Coordinator state is constant-sized; it holds at most one live request and one latest replacement. Job metadata remains bounded by the existing history policy. There is no document-sized result cache, repeated source copy in the new capture, or per-keystroke allocation in its cancellation check.

The opt-in workload creates/disposes 24 independent contexts. All owned `shed-worker`/`shed-lsp-reader` threads retire; the final bounded retirement observation was **1.92 ms**, with none remaining. No real LSP child processes exist in that workload. Private lifecycle assertions verify cleared coordinator callbacks, origin references and job-service references; queued cancellation and completion delivery retain the existing exactly-once contract.

Fresh **Maven/test JVM**, not the IDE: used/committed heap was about **30.36 / 68 MiB** before the component workload and **96.47 / 106 MiB** afterward without forced GC. Three explicitly labeled post-measurement diagnostic GC requests ended near **21.09 / 80 MiB**. These are observations, not a claim of reduced retained IDE heap. Global JVM threads went 6→11 as Swing/graphics initialized; owned request workers retired. Open descriptors went **175→176**, not cumulatively sampled/attributed to a native resource category. **RSS and native-memory categories were not measured.** No whole-process leak or memory-equivalence conclusion follows.

Legacy LSP deferred/stale-response bookkeeping and pathological peers that never return canceled responses were not comprehensively stress-qualified. Real pipe backpressure and uninterruptible native I/O remain outside this in-memory qualification.

## H. Tests, commands and safety exclusions

Final admitted suite, with the performance opt-in explicitly enabled: **162 passed, zero failures/errors/skips**. The preceding ordinary safe run reported **161 passed and one opt-in performance skip**, zero failures/errors. These are overlapping runs, not 323 distinct tests. Final compile/package succeeded. **86 Python tooling tests passed**, zero failures/skips. The real JUnit admission probe produced its expected pre-setup error for a synthetic unreviewed class; the wrapper confirmed no sentinel was written.

The freshly packaged JAR passed **Mermaid/math/local-image smoke**, including service-provider discovery, invalid Mermaid recovery, cache reuse and a second rendering session. All 1,429 production classes match the packaged artifact; no test gate or LSP fixture classes leaked into it. New JAR SHA-256:

`2a1767672c3a08dcc3062be06fef462db338adccd649d8df83f6a1fe7e4a29d9`

Pre-build JARs were preserved under `/private/tmp/shed-trackb1a-run/starting-jars`; their hashes are archived. Raw failures are retained: the expected cancellation-monitor regression, expected 32-node queue regression, and a measurement-harness compile error from using nonexistent color getters, corrected to the existing theme roles. No test assertions were relaxed to obtain success.

Use the existing toolchain, from repository root, with new output filenames:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --output /private/tmp/shed-trackb1a-run/final-all-safe.json
```

The same arguments with `--tests LspDefinitionPerformanceTest --measure-lsp` produced the fresh performance control; `--verify-gate` produced the synthetic admission check; `--package-only` produced the JAR with tests skipped solely for packaging. Exact expanded JVM/Maven argv, environment policy, fixture/source/class hashes and logs are indexed in `performance/ide-trackb1a/INDEX.md`. Python tooling uses `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under a fresh disposable environment. The packaged smoke driver supplies the same fresh environment and explicit JDK/JAR, with compiler/runtime home/temp/preferences redirected.

**Not run:** the full historical native/GUI Maven suite (B0: 1,616 passed and five skipped), `LspServiceTest`'s real child peers, `TexteditorSwingIntegrationTest`, PTYs, actual native language servers, physical/window input/paint, Windows/Linux GUI qualification. B0.1's gate and its signal-6 NO-GO remain intact. No full-suite pass count is inferred from compilation or historical results.

## I. UI/UX, defaults and preferences

No design, color-role, font, theme, layout, pane style, density, keymap or preference format was changed. Shed Light and bundled JetBrains Mono remain defaults; saved preferences retain their existing ownership. Loading/result messages reuse existing command/status mechanisms. The only UI glue changes retire obsolete pending navigation and marshal the existing review UI correctly. No new controls, settings, dialogs, renderers, workers, polling, dependencies or native hooks were introduced.

## J. README and repository protection

README before and after SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

Entry HEAD: `baf132f19639fdf050e2365098508c455531f87f`, with pre-existing B0.1 script/doc/evidence changes and the untracked admission sentinel. Those are preserved. External capture commits advanced HEAD during the task; their identities and final status are recorded in the repository-verification evidence. **This agent made no commits or pushes**, and used no reset/clean/revert/discard operation. The admission manifest was extended only for specifically reviewed safe classes; native launch safeguards were not disabled. Entry-to-final hashes separately verify preserved previous evidence and the intended source scope.

## K. Remaining limitations

- Other explicit LSP routes still call synchronous APIs. This tranche does not claim all completion/hover/reference/format/hierarchy/server-management UI paths are nonblocking.
- Existing didOpen/full-change transmission, URI/workspace resolution and target existence checks may still do synchronous preparation/I/O. Native pipe backpressure and target-file installation/visible paint remain unqualified.
- First graphics/font/view preparation in the headless fixture still took seconds; it was measured separately and not attributed to or fixed by the response-wait correction.
- First async submission has measurable initialization/EDT allocation costs; all first samples remain in the evidence.
- A starting/unavailable server requires a retried Definition command. Actual provider initialization and restart are not certified by an in-memory peer.
- Full window/IME/Plain/Vim/Emacs journeys and cross-platform GUI/performance remain unqualified. Unit/model checks do not substitute for those journeys.
- Native-shell isolation remains NO-GO. Existing Unicode first-edit stalls, 51 conformance mismatches, missing-glyph qualification, HTML-preview EDT stalls and native-memory attribution remain independent open items.

## L. Verdict

**PARTIAL overall; the narrowly scoped production correction is delivered and verified in the covered in-process paths.** The confirmed definition response wait no longer runs on the EDT, canceled lock waiters can retire, and canceled queued futures are removed from the standard worker queue. Source/Undo/context invariants and component responsiveness pass. This is not a GO for universal LSP responsiveness or general public release; native/provider/full-window qualification remains open.

## M. Next independently scoped task

Recommend **gutter-paint correctness** next, using the same in-process Swing approach: B0 already attributed an active-view invalid model offset, so a bounded guard/geometry investigation is possible without reviving native fixtures. Preserve source/Undo and compare ordinary and unlaid-out view geometry before any correction.

B0.1 process-confinement remediation remains a prerequisite to resuming native/full-window acceptance; missing-glyph rendering requires its own platform/rendering qualification. Track A0 should not be used to bypass these safety and observation gaps. No gutter fix, confinement investigation, glyph change or agent-control work was started here.
