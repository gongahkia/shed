# Render highlight cache bench

Date: 2026-07-01

Command:

```sh
swift run -c release ItsyBench render-highlight-cache
```

Result:

```json
{"cache_entries":91,"cache_hit_rate":0.9846354166666667,"cache_hits":3781,"cache_lookups":3840,"cache_misses":59,"elapsed_ms":174.042083,"frames":60,"glyph_instances":90240,"line_count":100000,"visible_lines_per_frame":32}
```

Notes:

- Synthetic file: 100,000 fixed-width Swift-like lines.
- Each frame replaces all 100,000 highlight spans, then renders visible text/highlight glyph instances and scrolls one line.
- Hit rate: 98.4635% after warm-up.
- Misses: 59 new shaped lines while scrolling 60 frames over a stable text buffer.
