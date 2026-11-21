# Track A0 evidence index

Report: [Agent-control-plane architecture audit](../../AGENT_CONTROL_PLANE_AUDIT.md).

**Decision: CONDITIONAL GO for a limited follow-up; no control plane implemented.** Reuse the existing workspace CLI/bridge after attachment authentication, narrow grants and bounded metadata gates. Do not infer a running control endpoint from this evidence directory.

## Evidence inventory

| File | Meaning |
|---|---|
| `baseline.json.gz` | Hashes of 2,122 pre-existing source/test/script/doc/asset/build inputs, clean recorded status and HEAD after an external capture of the pre-existing B1B.2 report/evidence. |
| `source-map.json` | Exact source paths, hashes and line anchors for inspected application interfaces; supporting test methods distinguished as selected for execution or source review only; prior report/input hashes. Search anchors identify implementation, not proof of runtime qualification. |
| `environment.json` | Host/JDK/Python identification and explicit measurement/desktop exclusions. No environment-variable or private-configuration dump. |
| `safe-tests.json.gz` | Full safe-runner command/output, selected classes, return status, fixture cleanup and recorded fixture timings. 192 cases, 189 passes, three opt-in skips, zero failures/errors. |
| `tooling.json.gz` | Isolated Python tooling command/output: 87 passes, zero failures/skips; fixture removal recorded. |
| `web-sources.json` | Official Java 21 API references used for transport constraints; unavailable JEP reference explicitly excluded as evidence. |
| `repository-verification.json` | Final HEAD/status snapshot, baseline-file comparison, README hash, diff/whitespace checks and documented capture-commit provenance. |
| `checksums.sha256` | Content hashes for this directory (excluding the checksum file) and the A0 report. |

Only this directory and the new report are A0 deliverables. The table plus checksum manifest enumerates every added file. Existing source, tests, scripts, manifests, prior evidence, themes and fonts remain unchanged.

## Reproduction boundary and commands

The safe runner checks selected test-source hashes against the existing admission manifest **before** starting Java. It copies a bounded existing dependency cache into a disposable fixture, invokes the Maven Java entry directly, runs offline/headless without forking test JVMs, and isolates home/temp/preferences. This is reviewed-test admission, **not general native-process confinement**. Native-shell benchmarks remain blocked. No new admission entries were added by A0.

Executed from the repository root:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --output /private/tmp/shed-tracka0-run/safe-tests.json
```

The three skipped measurement classes were `LspDefinitionPerformanceTest`, `GutterPerformanceTest` and `GlyphRenderingMeasurementTest`. Their opt-in flags were not supplied. Maven warned that `useSystemClassLoader` has no effect without forking and recommended forking for general test isolation; this is the deliberate admitted single-JVM path, not full-suite qualification.

Tooling used the existing `native_fixture.Fixture`: create/validate, use a copy of its controlled environment with `PYTHONNOUSERSITE=1`, execute the following argument array directly with the repository as working directory and a bounded timeout, record output, and `close()` in `finally`. Python bytecode generation was disabled by the driver. The actual command and cleanup outcome are in `tooling.json.gz`:

```text
/opt/homebrew/opt/python@3.15/bin/python3.15 -m unittest discover -s scripts -p test_*.py -v
```

Do not replace this invocation with an interactive shell, the blocked native benchmark runner or a full Maven suite. `WorkspaceBridgeTest` and `WorkspaceCliTest` include process/socket behavior outside the admission list; A0 reviewed them without executing them. Safe tests do not substantiate an external CLI reaching an actual window, nor do they exercise a proposed protocol.

## Interpretation

No new startup, transport, editor-paint, RSS or retained-heap benchmark was run. Recorded fixture creation/guard/cleanup times describe test infrastructure only and are not control-plane budgets. Historical B1A/B1B.2 latency, glyph and packaged-media results remain in their original evidence directories; A0 does not relabel them as new results. Every numerical control-plane budget in the report is proposed.

Windows/Linux desktop/ACL qualification, native capture, app focus activation, real LSP startup, IPC attack/replay execution and future checked edits were not performed. The protocol-security findings are source-derived risks; no credential-disclosure attack was run. No personal history/configuration file was inspected for this audit.

The report records initial observed HEAD `daefafca8b405f1c95fd54eab683f204d0f032a5` and pre-existing untracked B1B.2 deliverables. An external capture recorded those as `4852f154386a3e54d4ff8cf5da426c28bbd38bce` before the stored hash baseline. The agent created no commits or pushes and preserved those external changes. Final status is a point-in-time snapshot; an external capture may subsequently record A0 documents as well.
