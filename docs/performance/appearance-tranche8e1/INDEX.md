# Tranche 8E.1 evidence

Verdict: **NO-GO for production automatic following under the stated native appearance constraints.** Read [the A–N report](../../SYSTEM_APPEARANCE_FEASIBILITY.md). A successful experiment is not a successful production adapter.

- `baseline.json.gz`: entry HEAD/status and 1,539 pre-existing file hashes.
- `runtime-report.json.gz`: complete five-child experiment, eight actual OS transitions, six positive inherited-appearance KVO deliveries, Java native-state queries, sampled RSS, host data, child commands/exits and verified restoration.
- `summary.json`: readable native/default/system control results. Read queries are not notifications. Initial KVO callbacks and native pin/unpin control commands must not be counted as real OS transitions.
- `native.log.gz`, `java-default-light.log.gz`, `java-system-light.log.gz`, `java-default-dark.log.gz`, `java-system-dark.log.gz`: original child stdout/stderr. Native `request` identifies the latest helper command, not necessarily the cause of a later OS callback; the orchestrator's transition records associate genuine OS requests with subsequent matching events.
- `environment.json`: host/JDK/compiler/display/load/swap and artifact identity. Runtime Java/native probe source hashes are in the raw report and match the retained sources.
- `jdk-source-audit.json`: versioned primary-source URLs/hashes and matching line numbers/tokens. Downloaded OpenJDK source files remain in scratch storage; no JDK code was patched or vendored.
- `full-results.json`, `verification.json`: exact Maven/tooling/runtime/smoke commands and counts, skip reasons, setup failures and measurement limitations.
- `full-tests.log.gz`, `focused.log.gz`, `tooling-final.log.gz`, `packaged-smoke.json`: regression/packaged evidence. Full counts were preserved before the focused rerun overwrote its three Surefire XML files.
- `java-build.log.gz`: initial diagnostic-only compile failure from nonexistent JNA `Function.invokeByte`; corrected to `Function.invoke(byte.class, ...)` before the live-transition run. `java-build-final.log.gz` and `native-build.log.gz` record successful preparation; `java-initial-final.log.gz` records the successful read-only initial control.
- `runtime.log.gz`: experiment completed and original dark-mode/automatic preference restored. This is not a GO assertion.
- `preservation.json`: unchanged pre-existing files, README identity, HEAD/status and absence of diagnostic helpers from the production JAR.
- `inventory.json` / `checksums.sha256`: explicit file inventory and integrity checks, excluding recursive self hashes.

The experiment requires explicit `--execute-transitions`, uses a NEW output directory outside the checkout, temporary homes, finite command/event waits and owned child PIDs. It briefly changes the actual macOS light/dark setting and restores its original value and automatic-mode key presence/value. It does not poll OS appearance or add a production helper/service. The Java read callback list is bounded to 64 diagnostic requests; it is not an observer implementation.

The standalone AppKit helper was dynamically inheriting OS appearance, then deliberately pinned to Aqua to model the JDK policy. The `system` JVM control demonstrates a changed native inheritance policy; that flag was not installed in Shed. No native observer object was injected into the Java runtime.

After the successful measured run, wrapper safeguards were strengthened for failed-readiness cleanup, ambient JVM override removal and child-exit validation. No overrides were present in the measured environment. Native/Java probe sources and measured event data remained unchanged; no additional global transitions were performed to retest only failure-path cleanup.

Measurements distinguish OS-request→native-KVO timing, native registration, command-driven read latency, used/committed heap and checkpoint RSS. They do not establish notification→source-paint, settled idle/true peak RSS, retained heap/native-resource attribution or production automatic-mode startup costs. Historical 8E typing/switch results and existing Markdown/Unicode/preview limitations are not reclassified.
