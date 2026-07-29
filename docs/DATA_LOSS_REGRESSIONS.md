# Data-Loss Regression Coverage

`DataLossRegressionTest` covers cancellation, simulated post-write verification failure, restart recovery, external deletion of a dirty buffer, and backup-directory failure.

The suite asserts that cancellation leaves dirty content intact, a failed verified write restores the original source, recovery restores dirty content without writing its source or restoring undo history, external deletion retains the dirty buffer, and backup failure leaves both source and in-memory content intact.

Run it with `mvn -Dtest=DataLossRegressionTest test`.
