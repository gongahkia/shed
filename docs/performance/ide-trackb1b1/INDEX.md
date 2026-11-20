# Track B1B.1 evidence

Read [GUTTER_PAINT_REMEDIATION.md](../../GUTTER_PAINT_REMEDIATION.md) for the verdict, scope and limitations. No native-shell benchmark, actual language-server child or full main window was launched. All document content is synthetic.

## Results and identities

- `before.json.gz`: unchanged production gutter with three new safe component tests; **two regression failures**, one passing geometry control. Actual unlaid-out −1 throws/logs; injected 431/430 boundary loses foreground pixels. Failures are intentionally retained.
- `original-exceptions.json`: exact existing B0/8H evidence paths/hashes and their first full stacks. The original evidence remains unmodified. B0 logs four active-view 431/430 failures; 8H logs the matching line-106 exception.
- `focused.json.gz`: initial **16 passing** gutter/diff/component tests after the correction. Later EOF/read-bound coverage is in the final run.
- `final-bounded-regressions.json.gz`: final 38 admitted classes, **182 passed**, zero failures/errors/skips, including both explicitly enabled performance tests. Includes 12 GutterRenderingTest cases, four existing line-number cases, three diff-gutter cases and one opt-in gutter measurement case. Other covered safe LSP/document/Undo/appearance/Unicode/source-view classes are listed in the exact argv/log.
- `all-safe.json.gz`, `final-all-safe.json.gz`: earlier **180-pass** runs. Counts overlap with the final run and must not be summed. The latter contains the explicit starting-JAR failure image/stack and corrected pixel match.
- `tooling-bounded-final.json.gz`: **87 Python tests passed** under a disposable environment, no skips/failures. CLI checks reject missing paired-artifact and package/measurement conflicts before toolchain access.
- `package-bounded-final.json.gz`: successful existing offline Java/Maven package-only build, with tests explicitly skipped in this phase; tests ran separately above.
- `packaged-smoke-bounded-final.json.gz`, `packaged-smoke-bounded-final-driver.json.gz`: new packaged JAR PASS for math/Mermaid/local images, service providers, invalid-render recovery and another asset session. Compiler/runtime Java home/tmp/preferences use disposable locations.
- `artifact-verification.json`: candidate JAR **885dd51ae7ca684632f5eb19df04618a372e8bcf85769256c6acb277363315b5**; all **1,429** compiled production classes match. Only LineNumberPanel and its three generated nested classes differ from the starting JAR; no test fixture/gate is shipped.
- `baseline.json.gz`, `repository-verification.json`: entry and final repository state, README identity and preservation of prior work. External capture commits are recorded separately; this agent made none.
- `source-hashes.json`, `source-versions/`: exact final changed code, test sources, scripts and admission manifest, plus the entry gutter source. No original test was weakened.
- `starting-jars.json`: identities/backup paths of preserved pre-package binaries. The paired B1A control JAR is also preserved at `/private/tmp/shed-trackb1b1-run/baseline.jar`; no binary is checked into this evidence directory.
- `environment.json`: hardware/OS/JDK/arguments, fixture definitions and limits. Host load was sampled after measurement; per-run initial load/pressure was not captured.
- `checksums.sha256`: evidence integrity, excluding itself.

## Intermediate correction and work-bound regression

`sentinel-work-bound-before.json.gz`: my initial EOF normalization made 10,001 offscreen model-to-view queries on a synthetic 10,001-line document; the new bound assertion failed. `source-versions/intermediate-normalized-unbounded-LineNumberPanel.java.gz` preserves that rejected intermediate implementation. The final loop ends on unavailable geometry and at the visible bottom, instead of traversing offscreen lines. `sentinel-work-bound-after.json.gz` passes all **19** focused tests, including the same at-most-64-query assertion. This failure was introduced by the initial candidate, not attributed to the historical production gutter.

`paired-clipped*`, `final-all-safe*`, `final-eof-regressions.json.gz`, `tooling*.json.gz`, the earlier filenames `package.json.gz`/`packaged-smoke*.json.gz` and `intermediate-artifact-verification.json` retain earlier correction/testing/package evidence. Final results use the explicitly named bounded files above. They must not be relabeled or summed. The prior first-font observation remains visible in its own raw log.

## Measurements and visuals

Primary `final-bounded-paired.json.gz` / `final-bounded-paired-measurements.json`: fresh Maven/test JVM, pinned original JAR then candidate, same process, font/theme/viewport/fixtures. One first plus 50 warm samples per operation/artifact/size, with exact arrays, allocations and fixture hashes. No population p99 or matched cold-cache/startup claim. No source/retained-heap/RSS speedup claim. Current and original classes use separate package namespaces; the baseline loader is closed explicitly.

`final-bounded-regressions-measurements.json` is a **separate warmed-suite** observation with the explicit failure/valid-EOF pixel reference. It must not be pooled with the primary fresh run. The final 182-pass run's raw performance JSON is also available inside its log.

`paired.json.gz`: initial probe lacking an explicit Graphics clip. It painted outside the intended viewport, about 83 ms on 1 MiB for both artifacts; preserved as a measurement-boundary failure. Its implicit-clip timing is not the accepted viewport benchmark. Only the test clipping was corrected.

`visual/before-failed-implied-end.png`: blank starting gutter after the caught exception. `visual/after-implied-end.png`: corrected lines 1–21, exact equality to a valid-EOF control asserted. Other images show pixel-equivalent valid controls in Shed Light/Dracula for ASCII and mixed-script source. These narrow images contain gutter digits only: they do not qualify source glyphs or native desktop capture.

After 24 current-pane disposal cycles, active-document listener count is 1→1, and zero closed-pane weak references remain following three diagnostic collection requests **after** all latency samples. Global descriptor/thread/heap values are reported without native-memory equivalence or arbitrary-root leak guarantees.

## Safe reproduction

Use an existing Java 21/Maven installation and offline dependency cache. The runner copies the cache into a bounded disposable environment, disables user Maven settings/rc, redirects Java home/tmp/preferences and executes only source-hashed admitted tests in-process. Full native tests remain rejected.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --measure-gutter \
  --gutter-baseline /private/tmp/shed-trackb1b1-run/baseline.jar \
  --output /private/tmp/shed-trackb1b1-run/final-bounded-regressions.json
```

The output must be a **new** canonical file; use another name when reproducing. The preserved control must match the pinned B1A JAR SHA-256 `2a1767672c3a08dcc3062be06fef462db338adccd649d8df83f6a1fe7e4a29d9` before class loading. If unavailable, ordinary non-performance gutter tests can still run; do not substitute an arbitrary JAR or disable verification.

- Narrow tests: replace measurement options with `--tests GutterRenderingTest,LineNumberPanelTest,IncrementalDiffGutterTest`.
- Paired component run: `--tests GutterPerformanceTest --measure-gutter --gutter-baseline CONTROL.jar`.
- Normal safe suite: omit both measurement opt-ins; the two heavy tests are skipped.
- Package-only: use `--package-only` instead of test/measurement options.
- Python tooling: `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under the disposable Fixture environment; exact interpreter/argv/output are archived.
- Packaged check: existing `scripts/test-packaged-markdown.py --jar ABSOLUTE_JAR --jdk EXISTING_JDK --timeout 90 --output NEW_REPORT`, launched under a fresh disposable environment/cwd. Exact commands are in its driver report.

Native PTYs, real LSP peers, whole-window Texteditor integration tests, native monitor/IME tests and Windows/Linux GUI qualification were **not executed**. No replay of B0/8H's native harness is permitted by these results. B0.1 remains NO-GO; its failed backend was not repaired or relaxed.
