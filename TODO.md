# olly — TODO

todo.txt format. Each line is a single actionable task.
Format: `(PRIO) YYYY-MM-DD task description +project @context key:value`
Priority `(A)` = blocker for next phase. `(B)` = important. `(C)` = nice-to-have.
Projects: `+phase0` … `+phase9`. Context tags: `@swift`, `@ax`, `@ipc`, `@dsl`, `@layout`, `@ui`, `@ecosystem`, `@docs`, `@ci`, `@release`.
Read NORTHSTAR.md before starting. Cite NORTHSTAR section anchors in PRs (e.g. `ref:N§5`).

---

## PHASE 0 — Repo bootstrap


---

## PHASE 1 — AX foundation (`ollyKit`)


---

## PHASE 2 — Tag-based workspace model (`ollyCore`)


---

## PHASE 3 — Layout engine plugin contract (`ollyLayouts`)


---

## PHASE 4 — Built-in layout engines


---

## PHASE 5 — Swift DSL config (`ollyDSL`)

---

## PHASE 6 — IPC + `ollyctl`

---

## PHASE 7 — UX surfaces (`ollyApp`)


---

## PHASE 8 — Ecosystem bridges

---

## PHASE 9 — Docs, demo, release

(A) 2026-06-26 Record 30-second screencast: 4-engine hot-swap across 2 displays + tag switching + command palette; commit as `docs/demo.gif` +phase9 @docs @release
(A) 2026-06-26 Cut v0.1.0 GitHub Release with `.dmg`, source tarball, SHA256SUMS; draft Homebrew cask PR +phase9 @release
(B) 2026-06-26 Write a launch post for Hacker News + Lobsters + r/MacOS: lead with the multi-paradigm hot-swap hook, link benchmark table, screenshot grid; schedule for a Tue 09:00 ET +phase9 @release
(B) 2026-06-26 Open RFC issues for the v0.2 backlog (Monocle, Spiral, Grid, ThreeCol, Accordion) inviting community plugins +phase9 @docs @layout
---

## PHASE 10 — Performance & profiling (parallel with all phases; P0 for v0.1)

---

## PHASE 11 — Tier-1 layouts (v0.2)

(B) 2026-06-26 Cut v0.2.0 release with five new layouts; update README comparison table +phase11 @release

---

## PHASE 12 — Tier-2 layouts (v0.3, advanced UX)

(A) 2026-06-26 Implement `Tabbed` engine (i3/Sway precedent): multiple windows share a tile, accessible via a top tab bar; render tab bar as a child `NSWindow` overlay or via AX-managed title bar +phase12 @swift @layout @ui ref:N§6
(A) 2026-06-26 Implement `Stacked` engine: like Tabbed but full-height left title stack +phase12 @swift @layout @ui ref:N§6
(A) 2026-06-26 Implement `TreeTab` engine (Qtile precedent): vertical tree of tabbed windows on a side rail; configurable rail width and side +phase12 @swift @layout @ui ref:N§6
(C) 2026-06-26 Cut v0.3.0 release; update comparison table; blog post: "olly is now the macOS WM with the most layout engines" +phase12 @release

---

## PHASE 13 — Tier-3 architectural layouts + community engines (v1.0)

(B) 2026-06-26 Promote external layout engine API: `.dylib` loading at runtime, signed package format `.ollyplugin`, version negotiation; defer until ABI proves stable across 2+ minor versions +phase13 @swift @dsl @release ref:N§4-D4
(B) 2026-06-26 Publish a `Layout Engine Showcase` repo with 3 community-contributed engines as case studies +phase13 @docs @release
(C) 2026-06-26 Continuous: monitor PaperWM (GNOME), Karousel (KDE), Bismuth (KDE), hy3, hyprscroller, scrollwm; add notes to `docs/layouts-research.md` on each release +phase13 @docs

---

## PHASE 14 — DSL polish & documentation

(A) 2026-06-26 Implement type-safe error catalog: duplicate-chord, duplicate-tag-name, unknown-engine-id, ambiguous-rule — all are compile-time errors via `@_unavailable` or where-clauses +phase14 @swift @dsl ref:N§14
(B) 2026-06-26 Ship a DSL cookbook in `docs/dsl-cookbook.md`: 30+ snippets for common asks — "float Slack", "tag #4 always BSP", "different engine per display", "scratchpad tag", "follow-focus-to-display", "auto-rotate workspace on display unplug" +phase14 @docs @dsl
(C) 2026-06-26 Live config playground: an in-app sheet where users see DSL compile errors with column markers + quick-fix suggestions +phase14 @ui @dsl

---

## Cross-cutting / continuous

(A) 2026-06-26 Every PR cites the NORTHSTAR section(s) it touches; reject PRs that change locked decisions (NORTHSTAR §4) without an RFC issue first +continuous @docs ref:N§4
(A) 2026-06-26 Every PR must demonstrate it does not regress §12a perf budgets — PR template includes a `Perf impact` checkbox referencing CI bench output +continuous @ci ref:N§12a
(A) 2026-06-26 Every DSL change requires a corresponding entry in `examples/` exercising the new primitive and a doc-comment update +continuous @dsl @docs ref:N§14
(B) 2026-06-26 Maintain `docs/layouts-research.md` as living doc; add a new entry whenever a contributor proposes or studies a new layout paradigm +continuous @docs ref:N§6
(B) 2026-06-26 Maintain `docs/menubar-notch-integration.md` as living doc; add an entry whenever a new menubar/notch utility ships or breaks integration +continuous @docs @ecosystem ref:N§7
(B) 2026-06-26 No new private-API usage without an RFC + fallback path; CI gate `scripts/check-no-private-api.sh` blocks +continuous @ci ref:N§4-D2
(B) 2026-06-26 Cooperative-apps allowlist (NORTHSTAR §7b) is updated via PR; require evidence (screenshot or repro) of conflict before adding bundle IDs +continuous @ecosystem @docs ref:N§7b
(C) 2026-06-26 Quarterly: re-survey macOS WM landscape (search for new Niri ports, Nehir/Hiro releases, AeroSpace changes, Hyprland plugin trends); update NORTHSTAR §2 if positioning shifts +continuous @docs ref:N§2
(C) 2026-06-26 Quarterly: re-survey notch-app ecosystem (Alcove/NotchNook/Boring Notch releases, new entrants); update cooperative-apps list + safe-zone defaults if a notch app's expansion behavior changes +continuous @docs @ecosystem ref:N§7b
