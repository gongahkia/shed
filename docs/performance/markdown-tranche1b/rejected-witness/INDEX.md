# Rejected witness candidate

These four files preserve the first Tranche 1B experiment. They are **not the selected implementation's performance results**. They include paired baseline/candidate runs and explicitly recorded timeouts. Candidate JAR SHA-256: `5db4be696f5a923c2c7754efbc7cb1b451856dc7b4ee9327b5a960c02bfb81ff`.

The one-line witness improved undo, but changed which tied maximum Swing retained and made subsequent newline edits slow more often (10 MiB newline median 3.20 → 41.35 ms). It was rejected. See the [selected implementation report](../../../SOURCE_VIEW_PERFORMANCE.md) and [current evidence index](../INDEX.md).

The files were moved without changing their contents. `cases.json` contains summaries, `runs.json` fixture/runner/probe/JAR identities, `profiles.json` JFR summaries, and `samples.jsonl.gz` deterministic gzip raw samples. Groups remain named `before-*` and `after-*`; "after" here always means the rejected witness. Do not pool them with the selected summary's samples.
