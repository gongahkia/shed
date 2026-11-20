# Track B0.1 — Hermetic native-shell fixture isolation

## A. Root cause and result

**PARTIAL delivery; native-shell and whole-IDE native benchmarks remain NO-GO.** Disposable environment construction, path guards, synthetic safety tests and fail-closed benchmark/test admission are delivered. A complete process-confinement backend did **not** pass qualification. This tranche does not meet all B0.1 acceptance criteria. Do not resume native benchmarks, the full native/GUI Maven suite or historical benchmark replay from these results.

B0's concrete vulnerable test is `PtyTerminalConnectorTest.ptySupportsInputOutputAndResize`: `new PtyProcessBuilder(new String[]{"/bin/sh"})`, with no directory or environment override, then `stty size; printf shed-pty-ok; exit\n`. It supplies neither `-l` nor `-i`. [Inference] The attached PTY makes this an interactive shell through automatic terminal detection; it is not explicitly a login shell. It inherits the process environment and native HOME, even if Java `user.home` was redirected. The archived B0 observation says `ENV`, `BASH_ENV`, `ZDOTDIR` and `HISTFILE` were absent. Default startup/history behavior therefore remained available. The current production integration can also explicitly source original Bash/Zsh/Fish/PowerShell configuration; inspect `TerminalShellIntegration` before assuming shell flags suppress those files.

B0's exact focused Maven selector is retained in `WHOLE_IDE_AUDIT.md`, section M. Its native tests had no outer disposable native environment. The test finally closed the connector and shut down its reader executor; it did not establish a complete descendant-process lifecycle. **Whether any child outlived that historical run cannot be verified.** The later benchmark-only `/dev/null` history/startup override did not isolate HOME or constrain arbitrary writes/processes.

A personal history file's mtime overlapped B0. No before-hash exists. **This is not proof that its contents changed or that Shed modified it.** No personal history contents were read, hashed, rewritten or restored in B0.1. The historical uncertainty remains.

## B. Safety contract

A native fixture must have a fresh canonical, current-user-owned mode-0700 root; an ownership marker outside the child-writable subtree; owned home, configuration, state, temporary, log and working directories; a declared environment; bounded output/runtime; and verified process ownership. Symlinks, parent traversal, prefix collisions, multiply linked control files, missing/read-only directories, unexpected environment entries and unsupported configurations reject execution. Cleanup validates the original root device/inode and marker, uses descriptor-relative symlink-resistant removal, and never traverses a synthetic link into another fixture.

Environment isolation alone is **not** a security sandbox. A shell can use absolute filenames or fork/detach children. The candidate therefore requires kernel-enforced read/write and process restrictions plus a positive control. A rejected backend has no fallback to inherited HOME, permissive execution or an ordinary PTY. The Java admission gate is an execution policy for trusted repository tests, not containment of hostile Java code; deliberately disabling JUnit extensions or running historical binaries is outside that contract.

## C. Implementation and file inventory

| File | Change |
| --- | --- |
| `scripts/native_fixture.py` | Experimental disposable POSIX fixture; identity/path/environment validation; bounded builtin-only shell candidate; fail-closed backend verification; owned cleanup. **No native backend qualified** |
| `scripts/run-portable-tests.py` | Explicit existing JDK/Maven paths; reviewed test-source hashes; disposable native/JVM homes/preferences/temp; empty Maven settings; offline dependency-cache copy bounded to 128 MiB/6,000 entries; in-process Surefire |
| `scripts/test_native_fixture.py` | 33 synthetic/admission/dependency-copy checks; no interactive shell execution |
| `scripts/audit-whole-ide.py` | Plan generation preserved; actual execution writes `blocked.json` and exits 2 before toolchain, fixtures, Git or JVM execution |
| `src/benchmark/java/shed/WholeIdeServicesProbe.java` | Immediate unconditional admission rejection, including the server route; prior uncommitted `/dev/null` guard preserved |
| `src/benchmark/java/shed/WholeIdeDesktopProbe.java` | Same rejection before argument use/window creation |
| `src/test/java/shed/NativeFixtureGate.java` | JUnit before-all admission: deny unreviewed classes; check source hash and disposable JVM/native context for reviewed classes |
| `src/test/java/shed/NativeFixtureGateTest.java` | Four deterministic positive/negative policy tests |
| `src/test/java/shed/NativeFixtureRejectedSentinelTest.java` | Synthetic unreviewed class for real JUnit admission rejection; no process access |
| `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` | Register test-only gate |
| `src/test/resources/junit-platform.properties` | Enable automatic extension discovery |
| `src/test/resources/shed/portable-fixture-tests.properties` | Eight explicitly reviewed source hashes; existing test assertions unchanged |
| `docs/NATIVE_SHELL_FIXTURE_ISOLATION.md` | This report |
| `docs/performance/ide-trackb01/` | Evidence, commands, failures, measurements, compliance and checksums |

**Test-workflow change:** ordinary `mvn test`/`make test` is intentionally not an accepted full-suite execution path while native isolation is unqualified. Unreviewed classes produce admission errors, not successful skips. Use the explicit reviewed runner below. Do not expand its manifest merely to recover historical pass counts; review setup, transitive process paths and filesystem behavior first. The source-hash allowlist does not certify future production changes transitively.

## D. Environment isolation

No parent environment is copied into the shell candidate. Fixed values place HOME/ZDOTDIR, all four XDG config/data/cache/state paths, XDG runtime, TMPDIR, HISTFILE, ENV, BASH_ENV and GNUPGHOME under the fixture. Startup stubs and Git configuration are synthetic. Git system configuration, prompts and SSH are disabled for fixture purposes. PATH contains only system executable directories; candidate shells have absolute paths. Locale, timezone and terminal identity are explicit. Credentials, SSH-agent sockets, proxy/provider variables, loader injection, user aliases/plugins and Java launcher options are not inherited. Arbitrary additional variables reject validation without logging their values.

The declared candidates are system `/bin/sh`, Bash and Zsh in command/interactive/login forms. Bash/sh request no-profile/no-rc and privileged startup behavior; Zsh requests no user/global rc processing. Zsh's global `/etc/zshenv` cannot be assumed bypassed: the candidate rejects if it exists or is a symlink. On this host it was absent. System profile/rc files were inspected statically; path-helper and terminal-specific startup behavior reinforce using explicit flags and `TERM_PROGRAM=ShedFixture`. **None of these shell/mode combinations received a GO.** Fish, PowerShell, PTYs and user-integrated shells are unsupported here.

A direct JDK settings check established that changing native HOME/TMPDIR alone did not change Java `user.home`/`java.io.tmpdir`. The reviewed runner passes those properties and `java.util.prefs.userRoot` explicitly, invokes Maven's Java entry point without its shell launcher/user rc, supplies empty user/global settings and uses offline mode. Surefire runs in the same JVM (`forkCount=0`), with a warning retained. Its working directory is the checkout to run the existing build; authorized build outputs remain under `target`. This is a reviewed build/test exception, **not** an arbitrary shell fixture cwd.

The final runner copies the supplied dependency repository (98,080,024 bytes observed) into the fixture and lets Maven write only to that copy. Original dependency-cache aggregate content and size/mtime hashes match before/after the final run. Earlier compilation/portable checks used the original offline dependency cache directly; no before-hash covers those earlier cache accesses. No claim of historical cache immutability is made. User Maven settings and credentials were not intentionally read.

## E. Process lifecycle and failed native gate

Stage A/B validated syntax, finite commands, paths, environment and synthetic cleanup before the native shell candidate.

Initial **non-shell** backend controls used `/usr/bin/touch` on disposable files. A `/tmp` rule failed even its allowed write; canonical `/private/tmp` paths allowed the intended write and denied the synthetic external write (`Operation not permitted`). That profile allowed process operations and broad reads, so it demonstrated only a narrow file-write control, **not** credential/process isolation.

The stricter candidate profile requested system/runtime reads and fixture-only writes, no networking/Mach access or child creation, and constrained executable paths; its launcher also requested CPU/file-size/fd/core limits. These restrictions did not receive full runtime qualification. Its **first positive control** attempted only `printf allowed > "$HOME/allowed"`. The `sandbox-exec` launch targeting `/bin/bash` terminated by signal 6, with no output, after **9.50 ms**. Whether the launcher or shell runtime aborted was not established. The allowed-write success predicate failed. Root process was awaited and its fixture removed. Exact responsible launcher/runtime restriction is **unattributed**; this is not proof that all macOS sandbox approaches are impossible.

The gate stopped there. Denied-read/write/fork/exec candidate probes and subsequent shell scenarios were **not executed or qualified**. No relaxation was applied. A shell that can detach children requires additional ownership/supervision proof; process-group cleanup alone is insufficient for general PTYs. Timeout, cancellation, background grandchildren, inherited pipes and runner-crash cleanup have not been demonstrated with a viable native backend. No broad process-killing command, forced GC, permanent worker or production change was introduced.

The candidate's direct-PID reap/kill and resource limits are experimental, not accepted evidence for crash-safe native process cleanup. If the runner crashes, its directories can remain; no unattended cleanup service is installed. Do not describe B0.1 as complete hermetic native execution.

## F. External-file protection

Thirty-three synthetic/admission tests pass, including external-history rejection, startup/credential injection, symlink and hardlink escape, relative/parent paths, prefix collisions, read-only/missing directories, permission denial, changed owner marker, idempotent cleanup, bounded dependency copying and native-command rejection before launch.

`sentinel-proof.json` records equal synthetic external sentinel SHA-256 and nanosecond mtime before/after cleanup, existence after the other fixture was removed, then cleanup by its own owner. Dependency-copy tests modify a copied artifact and assert its source content/mtime stay unchanged. `dependency-cache-before.json` and `dependency-cache-after.json` record the final real dependency-cache integrity check without listing artifact contents.

These prove the covered operations, not absence of all possible writes by arbitrary native programs or OS diagnostic services. No personal history/configuration file was used as a writable probe. Native protected-sentinel checks did not complete after the failed positive control; they must not be counted as passing.

## G. Benchmark meaning

Fixture construction and validation timings measure test infrastructure only. A minimal isolated shell would exclude user plugin/startup costs and could not represent universal personal-shell startup latency. There is **no qualified terminal-startup, command-throughput or terminal-UI result** from this tranche. Historical B0 PTY values remain historical observations under the documented unsafe environment, not new hermetic baselines.

Existing B0 source/desktop controls are preserved but blocked. Other historical benchmark tools/archived binaries are not globally intercepted or approved for native replay by this change. No historical shell command was replayed to attribute the personal-history concern.

## H. Performance and resources

Same-process synthetic measurements, 50 fixture create/validate/dispose cycles; nearest-rank observed p95, not a general latency guarantee:

| Operation | n | p50 ms | p95 ms | max ms |
| --- | ---: | ---: | ---: | ---: |
| Fixture creation | 50 | 0.551 | 0.628 | 0.758 |
| Guard validation, including close validation | 100 | 0.368 | 0.460 | 0.527 |
| Cleanup | 50 | 0.594 | 0.703 | 0.747 |

All 50 roots were removed; no native processes launched in this workload. Open descriptors were **4 before / 4 after**. Python process peak RSS was **24,870,912 bytes**, not a JVM/native-shell retained-heap or settled-RSS comparison. Successful shell launch/shutdown latency, native graphics/resources and long-lived descendant retention remain unmeasured. The rejected positive control's 9.50 ms must not be advertised as shell startup performance.

No production startup/editing path imports these utilities or registers this test extension. Ordinary typing/startup were not rerun; no new production overhead is established by source/artifact identity, but this is not a fresh typing benchmark. Existing Unicode, HTML preview and native-memory qualifications remain open.

## I. Verification

See the evidence index for exact commands and raw output.

- Full existing source/test compilation: **PASS**, 281 test sources compiled initially; final compile includes the additional synthetic admission target. Initial direct Maven launcher attempt failed because `classworlds.conf` was missing; corrected explicit launcher configuration passed. Both results retained.
- Final reviewed Maven execution: **27 passed, zero failures/errors/skips** across eight classes: gate, terminal split-placement parser, bounded Undo, snapshots, grapheme boundaries/ranges, density configuration and basic theme roles. No terminal or project subprocess test was admitted.
- Final Python tooling: **84 passed**, including 33 new B0.1 checks and 51 existing checks.
- Independently compiled B0 probes: **PASS**; two direct Java invocations reject before arguments, protocol server or workbench creation. Driver admission rejects before toolchain access and writes explicit failure evidence.
- Native candidate: **FAILED qualification**, one positive-control abort; subsequent native scenarios not run.
- Actual JUnit admission control: **PASS**. The synthetic unreviewed class produced the expected Maven admission error before its setup sentinel was written; it is reported separately from the 27 passing tests.
- Full Maven native/GUI suite, packaged media smoke, PTY integration, build/task cancellation, shell timeout/cancellation/crash/descendant workflows: **NOT RUN** after the failed native gate. Last B0 results (1,616 passes/five skips; packaged-media PASS) are historical, not this tranche's results. Test assertions were not weakened or deleted.
- `git diff --check`: PASS at final check; it does not include already externally committed changes. Entry-to-final file hashes separately establish scope/preservation.

## J. UI/UX compliance

No `src/main`, dependency manifest, application settings, terminal UI, source model, themes, fonts, keymaps or layout implementation changed. Shed Light, bundled JetBrains Mono and saved-preference semantics remain intact. Production compiled-class/JAR identity is checked separately; a passing reviewed test subset is not blanket IDE qualification. No background test service or normal application startup hook was added.

## K. README and repository compliance

README SHA-256 before **and** after:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

Entry HEAD: `ffb866cc44af441a27a0ab36240449b3604692cc`. Entry work included a modified `WholeIdeServicesProbe.java`, untracked `WHOLE_IDE_AUDIT.md` and B0 evidence. All are preserved; the existing probe guard was extended, not removed. An external process created commit `baf132f19639fdf050e2365098508c455531f87f` while this work ran, capturing some new files. **This agent created no commits or pushes**, and performed no reset/clean/revert/discard. Final HEAD/status and every changed/new file relative to the entry snapshot are in `repository-verification.json`. Previous evidence remains byte-identical.

## L. Limitations

No native shell/platform combination is currently approved by this work. Windows/Linux native containment and GUI behavior: **No access / unqualified**. The reviewed runner is verified on the current macOS/POSIX host only. It is not a hostile-code container, network sandbox, universal process reaper or a replacement for normal terminal behavior. Unknown startup files, alternate shells, personal plugins, detached child patterns and new test classes require separate review. Full Maven regression coverage is intentionally unavailable until a safe native execution strategy is qualified.

## M. Readiness

**NO-GO to resume native-shell/whole-IDE native benchmarks. PARTIAL for B0.1 infrastructure delivery.** Safe admission and synthetic cleanup are verified. Reliable native process confinement, complete descendant ownership and crash cleanup are not. The next isolation prerequisite is a separately bounded test-runner backend investigation with an allowed-write/normal-exit positive control and real child/grandchild/crash tests before permitting PTYs. Do not bypass the guard or treat the broader touch-only profile as a solution. No production refactoring is authorized by this report.

## N. Subsequent engineering recommendation

After the isolation prerequisite, the smallest justified product correction is **LSP input-blocking remediation**, using B0's controlled 200 ms peer/210–216 ms queued-input delay; move only the established synchronous UI request boundary, preserving result revisions and cancellation. Then qualify missing glyphs (international readability, independent of layout latency), investigate the attributed active-view gutter paint failure (source/Undo checks had passed), and conduct Track A0's read-only control-plane feasibility audit after safety coverage is restored. Existing source-integrity failures would outrank this order, but none was newly established here.

No LSP/gutter/glyph fix, Markdown remediation, agent-control implementation or subsequent tranche was started.
