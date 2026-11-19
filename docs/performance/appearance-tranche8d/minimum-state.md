# Minimum transient state for 8D

- One per-window focus session, no source snapshots: prior header/tabs/footer/status/command visibility; prior centered-writing flag; tree hidden flags for surviving pane identities; minimap visibility without destroying it; tool host's existing dock/detached ownership with prior selection and size.
- Preserve the layout tree in place. Remember actual divider ratios at entry; do not restore a stale tree or resurrect removed panes. Source navigation and new/closed splits win. Resize preserves proportions and clamps to existing minimum sizes.
- Center each source scroll pane from its own available viewport width and current font metrics; preserve its base border and wrap state. No document copies, additional views, semantic parsing, new worker or cache.
- Focus owns temporary visibility while active. Exit restores captured visibility; explicit saved tab preference changes win. Commands can still reveal tools temporarily. Keep the current active editor and its live caret/selection on exit.
- Session/layout replacement and workspace-root changes end the old focus/centering presentation before replacement. Disposal drops transient references without restoring dead UI. Session save records pre-focus dock visibility, not the temporary hidden state.
- Use bounded per-pane presentation metadata, pruned for closed panes; snapshot pane/component references are weak. No full document or undo references in the capture.
