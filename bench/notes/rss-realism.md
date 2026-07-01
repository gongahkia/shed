# RSS realism

Date: 2026-07-01

Decision: replace the `<30 MB` idle RAM KPI with a `<100 MB` clean idle footprint target and `<80 MB` stretch.

## Evidence

Committed audit:

- Source: `bench/results/memory-2026-06-29-current.md`
- RSS: `92016 KB`
- Physical footprint: `98611 KB`
- Largest rows: `__TEXT`, `__OBJC_RO`, owned unmapped graphics, `__AUTH_CONST`, IOSurface, `__DATA_CONST`

Current local probes:

| Probe | RSS KB | Physical footprint |
|---|---:|---:|
| `bench/scripts/memory_audit.sh` temp run | 416096 | `200192 KB` |
| second temp run | 418224 | `197837 KB` |
| manual `ps`/`vmmap` sample | 476256 | `182.9M` |

Apple VM docs describe resident pages as pages currently resident in physical memory and point to `vmmap` for detailed process VM inspection:

- https://developer.apple.com/library/archive/documentation/Performance/Conceptual/ManagingMemory/Articles/AboutMemory.html
- https://developer.apple.com/library/archive/documentation/Performance/Conceptual/ManagingMemory/Articles/VMPages.html

## Rationale

[Inference] The `<30 MB` RSS target is not a useful gate for this AppKit + Metal app: the committed audit is already ~90 MB idle, and current no-purge local samples report much higher RSS due resident shared mappings and framework/resource pages.

[Inference] App-owned heap reduction alone cannot close a 60 MB+ gap because the largest contributors are framework metadata, graphics surfaces, mapped resources, and library pages.

[Speculation] Stripping enough AppKit behavior to chase `<30 MB` would exceed 40 hours: it implies replacing or deferring the application/document/menu/window stack, avoiding normal Services/menu integration, and remeasuring launch/restore fallout.

Use `<100 MB` as the clean idle footprint target. Keep RSS in reports for continuity, but treat `vmmap` physical footprint as the primary idle-memory gate.
