# Demo Readiness

Status: `docs/demo.gif` was recorded on 2026-06-28.

## Capture Setup

The machine had one built-in display. For the two-display capture, BetterDisplay
4.3.4 was installed through Homebrew, a temporary virtual display named
`OllyDemo` was created, mirroring was disabled, and the display was discarded
after capture.

Verified capture displays before recording:

```text
Color LCD
Resolution: 2560 x 1664 Retina
Main Display: Yes
Mirror: Off
Online: Yes
Connection Type: Internal

OllyDemo
Resolution: 5120 x 2880
UI Looks like: 2560 x 1440 @ 60.00Hz
Mirror: Off
Online: Yes
```

## Capture Commands

Two display recordings were captured concurrently:

```sh
screencapture -x -v -V 30 -D 1 /tmp/olly-demo-d1.mov &
screencapture -x -v -V 30 -D 2 /tmp/olly-demo-d2.mov &
wait
```

The final GIF was built from the two recordings:

```sh
ffmpeg -y \
  -i /tmp/olly-demo-d1.mov \
  -i /tmp/olly-demo-d2.mov \
  -filter_complex '[0:v]fps=8,scale=480:540:force_original_aspect_ratio=decrease,pad=480:540:(ow-iw)/2:(oh-ih)/2:black[left];[1:v]fps=8,scale=480:540:force_original_aspect_ratio=decrease,pad=480:540:(ow-iw)/2:(oh-ih)/2:black[right];[left][right]hstack=inputs=2,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse=dither=bayer' \
  docs/demo.gif
```

## Artifact Verification

```text
width=960
height=540
duration=30.010000
nb_frames=240
```

The temporary virtual display was discarded after capture and BetterDisplay was
uninstalled.
