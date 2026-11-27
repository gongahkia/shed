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

The final reviewed Maven selection passed **687 tests, six skips, zero failures/errors** (693 total). Skips are the five opt-in glyph/gutter/workspace measurements and the existing DOS-attributes assumption unavailable on macOS. LSP measurement was enabled separately. The two focused runs passed 77 and 100 tests; these overlap the full reviewed selection and are not additive. Python tooling passed **134 tests**, no failures/errors/skips, using the same disposable-fixture invocation documented in the preceding [workbench report](../agent-workbench/INDEX.md). The complete unreviewed/native Maven selection was not run.

A bounded direct-Java owned-window capture probe passed for small source edits, large inserted blocks and Find typing. Source Undo restored the original in one step; Find typing preserved exact source; no child processes remained. Its synthetic external sentinel content hash and modification time were unchanged, and its fixture was removed. The probe and runner are preserved with the captures for reproducibility; they use the existing authenticated external Java client, never a real coding model or shell.

Representative actual component captures: [badge-only block](captures/block.png), [small edit](captures/small.png), [shared field pulse](captures/field.png). These are sampled owned-component paints, not unrestricted desktop screenshots or a compositor/frame-rate measurement.

## Artifact, environment and safety

Same macOS 26.5.2 arm64 host, eight logical CPUs, Temurin 21.0.13.1+2. Final JAR SHA-256: `21771c926543aea1054f17ae83018b58034fa2ed014aa17e8679b63718925179`; all 1,650 compiled main classes match packaged bytes. Preserved baseline JAR SHA-256: `4f195d7def1cdb1a88bee8ab5d9c63ce3763c58a1ecf0ccdcbcd0dd4ed3caeff` (`target/agent-pulse-baseline.jar`, ignored build output).

Benchmark JVM arguments, fixture identity, load, raw samples and cleanup are retained in the reports. Fresh JVMs with warm OS caches are not cold-cache startup. No heap/graphics/OS settings were tuned for the comparison. The native-shell benchmark path remains NO-GO and was not used. Direct Java probes are separately bounded with disposable HOME/preferences/projects; the default source tour uses only fixed system Git commands with isolated configuration/hooks in its disposable repository. No real language server, interactive shell, native terminal demo or real VCS mutation backend was launched. Windows/Linux desktops and alternate display configurations remain unqualified.

Entry HEAD: `e7c86feed7d8db41f7ef93ee1d8738b0e97cf9a3`, clean working tree. Existing implementation, external commits and previous evidence were preserved. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No preference migration, default theme/font/keymap change, reset/clean/revert/discard or push. Atomic implementation and demo commits are separate from this evidence.
