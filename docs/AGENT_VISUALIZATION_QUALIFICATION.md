# External-agent editing qualification

The four follow-up areas have been exercised on macOS with the real workbench and a real Codex CLI. The supported bounded workflow passes. This is not exhaustive platform, long-session or whole-IDE release qualification.

Use `shed visualize . --agent Codex`, then let Codex edit files normally. See [the usage guide](LIVE_AGENT_VISUALIZATION.md) and [raw evidence and commands](performance/live-agent-visualization-hardening/INDEX.md).

## What changed

- Watch events now have a one-second maximum batch after Java receives them, rather than allowing every event to extend the quiet deadline indefinitely. Exclusions apply before coalescing. Closed watch generations cannot refresh a replacement watch, and queued changes drain after a transient refresh failure.
- Snapshots support 16,384 files, 1 MiB of UTF-8 text per file and 32 MiB total read bytes, with the existing depth/time limits. Exact rename matching indexes digests instead of repeatedly scanning all files. Larger files and binary data still receive notices. These are admission bounds, not performance guarantees for every project at the maximum.
- Diff formatting runs on the existing worker service. Obsolete selections are discarded, loading is explicit and truncated displays are labeled. Follow checks pane, buffer, revision, caret/selection and input again before installing a background file load. The followed row scrolls into view.
- The external-change checker iterates a snapshot of open buffers: View Both can open a scratch tab without invalidating that iteration. Automatic checks remember an acknowledged disk observation, avoiding repeated prompts for the same observation. This acknowledgment never grants overwrite permission. A different disk observation prompts again; explicit save-conflict handling remains available.
- Diff foreground contrast is calculated against the actual tinted line background, preserving the existing theme roles and satisfying the new 4.5:1 regression check.

No document model, editor renderer, theme engine or settings panel was added. The default theme/font and user preference conventions are unchanged.

## The four areas

| Area | Evidence and outcome |
| --- | --- |
| Real Codex CLI | Actual installed Codex 0.172.0, low reasoning, created/deleted/modified files and moved a file while editing its contents. Shed observed the resulting five rows and displayed the actual Java diff. No review proposal was needed. |
| Human/agent collision | Two successive disk versions against the same dirty buffer; View Both, no repeat for the acknowledged observation, fresh prompt for the next version, ordinary Save refusal, exact human Undo/Redo, and preserved external bytes. |
| Bursts and project size | 5,000 generated source files plus a 240 KiB text fixture; 40 atomic replacements, 100 additional modifications, 20 deletions and 20 exact renames converged to 142 changes. Unit coverage also checks hidden temporary tails, continuous writing and truncation. |
| Visual/lifecycle edges | Light/dark themes, 850-pixel window, three splits, Compact/Comfortable density, focus/centering, custom UI/editor fonts, source hit testing, 24 animation start/clear cycles, actual timer expiration and 12 watch start/stop cycles. |

The delayed-Follow test blocks the existing workers, verifies an actual file-load job was queued, edits the human buffer, then releases the workers. The human pane and draft remain active, and Undo restores the exact prior text.

## Measurements and regressions

On the final packaged artifact:

- Snapshot baseline: 209.49 ms; burst-to-converged changes: 2,339.15 ms. This includes operating-system event delivery, batching and snapshot/diff work. It is not an instant notification guarantee.
- Typing to completed component paint with the 5,000-file watch active: first sample 3.30 ms; warm p95 1.02 ms over 20 samples. These are synthetic editor actions in the real window, not physical-keyboard/scanout measurements.
- Twelve stopped watcher objects: zero retained after bounded diagnostic GC. Used Java heap after that diagnostic was 17,317,216 bytes. This does not establish RSS/native-memory equivalence or an hours-long soak result.
- 401 safe Maven passes, seven skips, zero failures/errors. Six skips are opt-in measurements; DOS hidden-attribute coverage is unavailable on this host.
- 95 tooling passes. Packaged media and launcher results are in the evidence index. The older reviewed Apply/Undo/Redo/save/Git flow also passed on the final artifact.

The first desktop probe exposed a ConcurrentModificationException even though its old completion check said PASS. That run is classified as failed qualification. The probe now rejects uncaught desktop exceptions. Earlier failed/incomplete attempts and the contrast regression failure remain preserved, including an unrelated stale-context rejection in the older proposal route. That route was rechecked successfully; its one rejection is not erased by the later pass.

## Remaining change-visualization gaps

These are product gaps, not newly implemented features:

1. **Coverage and safe configuration files:** a skipped-file/coverage summary is still missing, and dotfiles and dot-directories are excluded broadly, so ordinary `.gitignore` and `.github` changes are not covered by this watch. A future inclusion policy should distinguish ordinary configuration from secrets rather than exposing all hidden paths.
2. **Edited renames and folder moves:** snapshots can prove only unambiguous identical-content renames. A move with rewritten contents appears as deletion plus addition, as in the real Codex run. Directory operations are individual file rows. Reliable grouping needs explicit hints or appropriately qualified rename detection; arbitrary delete/add pairs must not be presented as certain renames.
3. **Assets/binary files:** the panel has a notice, not image-before/after, SVG, PDF or other asset comparisons. Existing previews do not automatically provide a safe before/after asset workflow.
4. **Metadata-only changes:** chmod/executable-bit changes, symlink targets, file-type changes, encoding/line-ending-only changes and submodule-pointer changes lack a dedicated snapshot treatment. Git may expose some metadata in patches, but the watch must not claim complete coverage.
5. **Agent Git operations:** commits, staging, branch switches and rebases lack an agent-session timeline. `.git` is excluded from this watcher, so Git-only state changes can need manual refresh. Existing Git controls remain separate.
6. **Review/revert ergonomics:** the watch is read-only. It has no integrated per-hunk revert, side-by-side merge or multi-file operation summary. External writes do not become human Undo entries; existing Git/review tools have different ownership.
7. **Session history and authorship:** the starting baseline is in memory and resets on restart. Disk events cannot identify an author, generation tokens or a remote cursor. Optional completion/activity hints could reduce perceived watcher delay without routing all edits through Shed.

The next narrowly scoped UX improvement should be safe configuration-file visibility. It affects common coding-agent work that is currently omitted, while retaining explicit protection for credentials.

## Limits

Windows/Linux desktop behavior, other display scales, very large workspaces, long-running concurrent agents, whole-process native memory and crash-soak behavior remain unqualified. The B0.1 interactive native-shell benchmark remains NO-GO; these tests used independently bounded direct Java/Git/Codex execution instead. Known Unicode first-edit, missing-glyph and HTML-preview latency issues remain open. Visual captures are application-owned components, not source-of-truth text or unrestricted desktop screenshots.

Automatic conflict acknowledgment compares disk metadata, not a new content-security identity. Existing save-time content/disk checks remain authoritative; metadata acknowledgment does not authorize saving or claim to detect every same-size/restored-timestamp external edit.

README was never edited. Before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. All work is local; no push.
