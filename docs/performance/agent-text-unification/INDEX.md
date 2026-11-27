# Unified agent text presentation

The approved treatment keeps the original source pulse/replay, shares its pulse paint with admitted text fields, and uses the existing branded presence. Large inserted source blocks show the provider/activity badge without a tracking pointer or thin replay caret. Small edits retain their existing typing/cursor behavior.

## Implementation and boundaries

- `AgentTextPulse` supplies common theme/caret-aware colors, alpha and directional sweeps. Source decorations, semantic UI fields and the legacy Find API reuse it. Writing sweeps left-to-right; reading top-to-bottom; deletion right-to-left. There is no new timer, thread pool, dependency, source buffer or setting.
- `AgentChangePlayback` retains its existing small-edit boundary: at most 160 UTF-16 units and two newlines. Larger additions keep chunk reveal but report zero tracking cursors. Deletion behavior and small/batch edit visuals are preserved.
- `AgentEditPresentation` anchors the badge near the start of a large block, clamped to the visible viewport. It retains badge-only mode after replay finishes, so no cursor flashes or travels to EOF. The pulse and original viewport tour still explain where the edit occurs.
- `AgentUiPresentation` fades the pointer glyph while leaving the badge opaque. It retains location and follow-token ownership. The next admitted interaction restores the pointer from that retained position; no permanent animation runs while parked. Reduced motion applies the state directly.
- Ordinary fields retain fast progressive small ASCII input. Complex text and larger values install as one shaped value and use the shared pulse. Field reading/deletion use the same directional paint. Only each request's own highlight is removed on cleanup.
- Source edits remain one checked `FileBuffer.TextTransaction`. Glyph replay is paint-only, so source revision, dirty/save tracking and one-step Undo do not become per-character edits. The existing 64 KiB source replay bound remains.

This is one visual policy with adapters for the existing source and field owners. It does not replace JTextArea, PlainDocument, the terminal renderer, or any document ownership boundary. Existing Unicode shaping/glyph, first-use layout and preview limitations remain open. The API/permissions/CLI command schemas are unchanged.

## Verification

The added deterministic tests cover badge pixels without pointer pixels, smooth pointer restoration, a pinned large-source badge, no EOF cursor flash, no thin chunk caret, directional/clipped sweep paint, exact source and Undo preservation, document replacement, highlight cleanup, long-field shaped insertion and reduced-motion stability. Existing source, grapheme, animation, terminal-decoration, legacy Find and workbench tests remain admitted through the reviewed runner.

Commands used (existing installations, no downloads):

```sh
JDK=/Users/gongahkia/.local/share/shed-tools/jdk-21
MAVEN=/private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
CACHE=/private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository

python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" \
 --repository "$CACHE" --measure-lsp --output "$PWD/target/<new-safe-report>.json"
python3 scripts/run-portable-tests.py --jdk "$JDK" --maven "$MAVEN" \
 --repository "$CACHE" --package-only --output "$PWD/target/<new-package-report>.json"
python3 scripts/test-packaged-markdown.py --jdk "$JDK" \
 --jar target/shed-2.0.0.jar --output "$PWD/target/<new-media-report>.json"
make demo
python3 scripts/test-scm-performance.py --jdk "$JDK" \
 --jar target/shed-2.0.0.jar --baseline-jar target/agent-pulse-baseline.jar \
 --pairs 3 --output "$PWD/target/<new-performance-directory>"
```

The first reviewed Maven selection passed **687 tests, six skips, zero failures/errors** (693 total). The action-color extension subsequently passed **689 Maven tests, six skips, zero failures/errors** (695 total); its focused selection passed 83 tests. Skips are the five opt-in glyph/gutter/workspace measurements and the existing DOS-attributes assumption unavailable on macOS. LSP measurement was enabled separately. The two focused runs passed 77 and 100 tests; these overlap the full reviewed selection and are not additive. Python tooling passed **134 tests**, no failures/errors/skips, using the same disposable-fixture invocation documented in the preceding [workbench report](../agent-workbench/INDEX.md). The complete unreviewed/native Maven selection was not run.

A bounded direct-Java owned-window capture probe passed for small source edits, large inserted blocks and Find typing. Source Undo restored the original in one step; Find typing preserved exact source; no child processes remained. Its synthetic external sentinel content hash and modification time were unchanged, and its fixture was removed. The probe and runner are preserved with the captures for reproducibility; they use the existing authenticated external Java client, never a real coding model or shell.

Representative actual component captures: [badge-only block](captures/block.png), [small edit](captures/small.png), [shared field pulse](captures/field.png). These are sampled owned-component paints, not unrestricted desktop screenshots or a compositor/frame-rate measurement.

## Action colors

The pointer, provider/activity badge and highlighted text use the same semantic base color. Writing/success is green; deletion/failure/conflict red; reading/checking yellow or amber; search blue; navigation purple. Highlight translucency is retained for readability. Search uses a dedicated blue within agent decoration; the rest of Shed's theme is unchanged. If a configured human caret is close to an action color, the agent uses a different shade of that same hue rather than switching to purple. Badge text contrast is selected against its action background.

Source and terminal adapters pass the exact tint already used by their text decoration/glow. Source intents also pass their actual activity label (for example, “searching”), rather than looking like “writing” for every intent. Provider logos and the existing persistent location/follow-token behavior remain shared; large inserted blocks stay badge-only.

## Build-tool availability

The temporary Maven installation and offline cache disappeared after the successful Maven qualification. A subsequent ordinary package attempt failed before compilation because its configured tool directory no longer existed; no tools were installed and no user configuration was changed.

The installed JDK recompiled all six changed action-color source units against the existing packaged dependencies. All 13 generated classes matched the previous Maven output byte-for-byte. The unsigned runtime JAR was refreshed with the 1,650 verified compiled main classes, retaining existing dependencies/resources/manifest. This is a direct-JDK artifact refresh, not a new Maven packaging claim.

A fresh reviewed JUnit run against that artifact passed 689 tests; five disabled measurement tests and one unavailable DOS-attribute assumption account for the six skips/aborts. Its initial attempt had nine fixture failures because these process tests require the existing URL-classloader/build-root contract. The second runner used that supported layout, with unchanged classpath admission, manifest source hashes, fixture ownership, native-test gate and assertions. The initial failure report is preserved. No production change was made to accommodate the runner.

The normal Maven rebuild path remains unavailable until Maven/cache access is restored. The ready-to-run JAR contains the action-color change. The existing JDK and cached public JUnit jars were sufficient for the documented source/runtime checks.

## Artifact, environment and safety

Same macOS 26.5.2 arm64 host, eight logical CPUs, Temurin 21.0.13.1+2. First pulse-only JAR SHA-256: `21771c926543aea1054f17ae83018b58034fa2ed014aa17e8679b63718925179`. Final action-color JAR SHA-256: `cd25792da2cce0a24ee7df580bfebeab9ca05b98078b4900fba836de59696c5d`; all 1,650 compiled main classes match packaged bytes. Preserved baseline JAR SHA-256: `4f195d7def1cdb1a88bee8ab5d9c63ce3763c58a1ecf0ccdcbcd0dd4ed3caeff` (`target/agent-pulse-baseline.jar`, ignored build output).

Benchmark JVM arguments, fixture identity, load, raw samples and cleanup are retained in the reports. Fresh JVMs with warm OS caches are not cold-cache startup. No heap/graphics/OS settings were tuned for the comparison. The native-shell benchmark path remains NO-GO and was not used. Direct Java probes are separately bounded with disposable HOME/preferences/projects; the default source tour uses only fixed system Git commands with isolated configuration/hooks in its disposable repository. No real language server, interactive shell, native terminal demo or real VCS mutation backend was launched. Windows/Linux desktops and alternate display configurations remain unqualified.

Entry HEAD: `e7c86feed7d8db41f7ef93ee1d8738b0e97cf9a3`, clean working tree. Existing implementation, external commits and previous evidence were preserved. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No preference migration, default theme/font/keymap change, reset/clean/revert/discard or push. Atomic implementation and demo commits are separate from this evidence.

External user commit `a038a3eda` captured the action-color extension and pending demo/docs during verification; it was preserved. The earlier pulse implementation is atomic commit `0f6c66f34`. Later verification/evidence changes are separate.


## Final live and performance results

The final `make demo` passed on the action-color JAR: the source animation tour followed by 53 workbench stages / 131 authenticated external Java CLI calls. The added workbench block stage asserts badge-only state with zero tracking cursors, captures it, then verifies that one Undo restores the draft. Source, caret/selection and history integrity checks remain in the original tour. Color assertions compare the actual shared pointer/badge tint to the active source highlight during reading, replay and every intent. Captures for [reading](captures/reading.png), [deletion](captures/deleting.png), [search](captures/searching.png) and [badge-only blocks](captures/block.png) come from this final artifact. No child process remained after the workbench fixture; its synthetic external sentinel hash/mtime and cleanup checks passed.

One earlier pulse-only full source tour timed out waiting for a watcher-driven deletion presentation after a read. It was preserved and no assertion was weakened. The final action-color full tour passed the same stage; the intermittent watch/tour failure's cause is still unresolved. A passing rerun is not evidence that this timing concern was fixed. A subsequent `make demo-deletion` attempt before the JAR refresh stopped at the missing Maven prerequisite and launched no fixture.

All 28 existing matched performance gates passed in a clean run of three alternating pairs / six fresh JVMs. The unchanged probe uses five warmups and 100 timed typing samples per workload, completed Swing component painting, the existing 16 ms typing target, and bounded source-control interference/disposal fixtures. Exact startup, typing, first-use, heap/thread/descriptor, load and raw samples are in `raw/matched-performance-final.json.gz`; no universal or statistical-equivalence claim follows from three pairs. Whole-process native-memory attribution and cross-platform native desktop qualification remain open.

The first performance attempt overlapped the packaged-media smoke at startup. It was excluded from acceptance and stopped by its exact owned runner PID; its fixture cleanup and partial evidence are retained. The accepted comparison ran after media smoke had finished, with one benchmark child at a time. Packaged Mermaid/math/local-image smoke passed separately on the final JAR. Native-shell NO-GO restrictions were not bypassed.

The fallback JDK/JUnit probes in `probes/` are the exact one-off recorded scripts; their hardcoded output directories intentionally reject overwrites. Use a fresh copied output directory for reproduction. They are evidence tooling, not a replacement production build system.

The final same-artifact candidate typing-workload p95 values were all at or below 2.910 ms, below the existing 16 ms target. The source-tour tint verification is commit `6f0286640`; documentation/evidence is separate. `git diff --check` passes.
