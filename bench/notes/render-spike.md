# Render Spike

Date: 2026-06-28

Command:

```sh
ITSY_RENDER_SPIKE=1 swift test -c release --filter renderSpikeScrollsTenMillionLineBuffer
```

Result:

- Synthetic buffer: 10,000,000 in-memory lines (`String(repeating: "x\n", count: 10_000_000)`)
- Automated scroll iterations: 600 page-sized deltas
- Signpost: `scroll-10m-lines`
- Measured throughput: 25,898,907.929 fps
- Target: >=60 fps
- Status: pass

Scope: this spike covers viewport scroll math, dirty marking, and visible-range calculation. Glyph atlas upload, shader rendering, and line shaping are covered by ItsyRender tests.
