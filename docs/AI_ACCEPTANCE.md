# Focused AI engineering acceptance

Shed exposes Complete, Edit selection, Ask/search and Run task through OpenCode ACP. This is an engineering acceptance record, not a claim of model-output reliability or parity with ThePrimeagen/99. At the user's direction, further model experiments have stopped; hosted-provider acceptance is deferred. The direct local-inference integration and its settings were removed.

## Implemented safeguards

- Legacy local-completion configuration is disabled on migration and removed from active settings; explicit OpenCode opt-in remains enabled after persistence/reload.
- Complete uses a dedicated tool-disabled connection, fresh conversations, bounded prefix/suffix context, strict reply validation, cancellation/deadlines, exact document/revision/caret checks, and explicit Tab acceptance. Escape dismisses. Custom keymaps, LSP/snippet completion and ordinary indentation retain priority. Automatic provider failures back off for 30 seconds; manual completion can retry immediately.
- Edit selection freezes the selected range and version. Only a validated replacement becomes a proposal; subsequent edits invalidate the captured version. Human review/apply and native undo use the shared workspace implementation.
- Ask/search receives workspace samples and access to complete, versioned unsaved snapshots. Locations are checked against workspace paths and actual line/column bounds, and checked again before navigation. Unsupported locations are omitted; an answer is not presented as verified merely because its locations are valid.
- Run task captures file baselines and validates paths, limits, exact nonoverlapping edits and every touched version before creating a shared proposal. Creates/deletes/renames use the existing reviewed resource transaction. Cancellation withdraws pending review requests; nothing applies automatically.
- Malformed replies get at most one visible format correction with the captured request/versions. Exhaustion preserves the user's instruction and shows a bounded unusable-reply excerpt. Stale failures require fresh capture rather than silently rebasing generated code.

These safeguards cannot prove a generated change is logically correct. A syntactically valid replacement can still be wrong; review and project-specific checks remain necessary. No model-specific guess is used to reinterpret an invalid response into an accepted edit.

## Deterministic verification

The full Java 21 headless `clean package` run reported **1,223 tests: 1,138 passed, 85 skipped, zero failures/errors**, across 225 test reports. Native and benchmark cases are opt-in and are covered by the separate checks below. The subsequent focused regression run passed all 73 tests with no skips, failures or errors, covering the final configuration migration plus completion sessions, ACP transport and architecture boundaries.

`OpenCodeCompletionClientTest` runs a real child ACP peer implemented with the official SDK. It checks fresh sessions on a reused process, bounded context, disabled requests, denied reads/writes/permissions, malformed/oversized/incomplete replies, cancellation, timeout, shutdown and retry. No model or network endpoint is involved.

`AgentSessionPanelTest`, `AgentSelectionTest`, `AgentAnswerTest`, `AgentTaskPlanTest` and the shared workspace/proposal tests exercise the actual panel/review controls, unsaved content, resource transactions, stale code, cancellation and undo. Geometry and pixel tests compare ghost previews to the same text actually inserted, including tab stops, multiple lines, soft wrapping and resizing.

`FocusedAiNativeAcceptanceTest` passed with a deterministic ACP peer. It opens one isolated Shed window, dispatches Tab through the real key handling, verifies one Undo, resizes multiline/tabbed previews to 900 and 580 pixels, checks focus/preview retention, dismisses with Escape and checks ordinary Tab indentation. Source stays untouched before acceptance and the saved file remains unchanged. Captures use the displayed Swing frame's `paintAll`; keyboard events are synthetic component events. The window and its services close afterward.

The native checks found and fixed menu construction before the editor existed, layout refreshes that lost focus, bracket highlighting against a pre-insertion snapshot, and a block caret obscuring virtual text. The current OpenCode wiring passes the same native fixture check.

## Local preview measurements

Measured on an Intel Core i5-8279U, 8 GiB RAM, macOS 15.7.7, Java 21.0.12.1. Each case uses three warmups and ten measured iterations; the viewport is 580 × 600 pixels. These measurements isolate Swing preview work from model latency and are not cross-editor benchmarks.

| Source size | Wrapping | Preparation p95 | Paint p95 | Dismissal p95 | Production eligibility |
| --- | --- | --- | --- | --- | --- |
| 10,000 characters | Off | 4.3 ms | 2.8 ms | 0.5 ms | Enabled |
| 10,000 characters | On | 13.0 ms | 1.2 ms | 13.6 ms | Enabled |
| 100,000 characters | Off | 5.2 ms | 2.6 ms | 0.8 ms | Enabled |
| 100,000 characters | On | 34.4 ms | 1.2 ms | 26.1 ms | Enabled |
| 1,000,000 characters | Off | 27.1 ms | 1.2 ms | 5.8 ms | Enabled |
| 1,000,000 characters | On | 187.5 ms | 1.3 ms | 215.8 ms | Disabled by size guard |

The expensive wrapped case motivated the preview-size guard. Files remain editable; explicit completion explains the limit. `:perf` records actual provider/preparation/ready/paint durations and assistant action preparation/provider/total durations without storing source in metric details. Hosted-provider latency is not established by these tests.

## Reproduce

```sh
java_home=$(scripts/resolve-toolchain.sh home)
maven=$(scripts/resolve-toolchain.sh maven)
JAVA_HOME="$java_home" "$maven" -B -q -Djava.awt.headless=true clean package

# Headless, no model: component timings.
JAVA_HOME="$java_home" "$maven" -B -q -Djava.awt.headless=true \
  -Dshed.ai.benchmark=true -Dtest=FocusedAiPerformanceTest test

# Explicitly opens one editor window, still with no model or inference service.
JAVA_HOME="$java_home" "$maven" -B -q -Djava.awt.headless=false \
  -Dshed.ai.native=true -Dtest=FocusedAiNativeAcceptanceTest test
```

Reports/captures are generated in `target/ai-acceptance/`. Native and benchmark tests are opt-in; the normal headless suite skips them. CI runs the native fixture in a virtual display. Hosted authentication, credits, provider output quality and real-provider completion latency remain unverified and were explicitly deferred by the user.
