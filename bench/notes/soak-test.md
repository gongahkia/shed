# Soak test notes

## 2026-06-29 aborted run

- Command shape: disposable `/tmp/itsy-soak-workspace`, open repo as workspace, open 50 tracked source/doc files, edit each via `System Events`, sample RSS.
- Result: aborted at 240 s after user reported laptop slowdown.
- Process cleanup: verified no remaining `Itsy`, `ItsyBench`, or `xctrace` processes after abort.
- Observed RSS samples before abort: `471984`, `1008032`, `1049520`, `571040`, `925584` KB.
- Failure: peak RSS growth exceeded 10%.

## Fix under test

- Cause: opening files as tabs was creating one `EditorWindowController` and `MetalTextView` per document.
- Change: `ItsyDocumentController` and `ItsyTabCoordinator` now reuse the active editor window when selecting/opening documents.
- Short probe after fix: 20 files opened/edited, `windows_after=1`, RSS `114000 -> 127424` KB.
- Short probe after fix: 50 files opened/edited, `windows_after=1`, RSS `92432 -> 126400` KB.
- UI overflow fix: `TabBarView` now contains its tabs in a horizontal `NSScrollView`.
- Short probe after overflow fix: 50 files opened/edited, `windows_after=1`, window size stayed `1200 x 704`, RSS `89968 -> 149456` KB.

## Full gate

- Harness rule: open/edit 50 files, wait `ITSY_SOAK_SETTLE` (`60` s default), then measure RSS for `ITSY_SOAK_DURATION` (`3600` s default).
- Pass rule: exactly one Itsy window and `peak_growth_percent < 10`.
- Full id:285 gate: pass.
- Result file: `bench/results/soak-2026-06-29.json`.
- CSV file: `bench/results/soak-2026-06-29.csv`.
- Opened files: `50`.
- Windows: `1`.
- Duration: `3601` s.
- Baseline RSS: `114512` KB.
- Peak RSS: `122352` KB.
- Final RSS: `59584` KB.
- Peak growth: `6.846444040799217%`.
- Final growth: `-47.96702528992595%`.
- Process cleanup: verified no remaining `Itsy`, `ItsyBench`, or `xctrace` processes after completion.
