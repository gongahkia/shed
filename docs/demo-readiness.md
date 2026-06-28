# Demo Readiness

Status: `docs/demo.gif` is not recorded. The current machine exposes only one
display, so the two-display TODO cannot be completed from this environment.

## Verified Blocker

Checked on 2026-06-28:

```sh
system_profiler SPDisplaysDataType
```

Current output shows one online display:

```text
Color LCD
Resolution: 2560 x 1664 Retina
Main Display: Yes
Connection Type: Internal
```

`/Applications/BetterDisplay.app` is also absent, so no local virtual display is
available.

## Capture Runbook

Once two displays are online, record both displays for 30 seconds:

```sh
screencapture -v -V 30 -D 1 /tmp/olly-demo-display-1.mov &
screencapture -v -V 30 -D 2 /tmp/olly-demo-display-2.mov &
wait
```

During the capture, show:

- four-engine hot-swap
- tag switching
- command palette

Then build the GIF:

```sh
ffmpeg \
  -i /tmp/olly-demo-display-1.mov \
  -i /tmp/olly-demo-display-2.mov \
  -filter_complex "[0:v]scale=960:-1[a];[1:v]scale=960:-1[b];[a][b]hstack=inputs=2,fps=12" \
  docs/demo.gif
```

Verify the artifact before removing the TODO:

```sh
test -s docs/demo.gif
git diff --stat docs/demo.gif
```
