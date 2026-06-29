# ProMotion audit 2026-06-29

## Implementation evidence

- `MetalTextView.makeBackingLayer()` sets `CAMetalLayer.maximumDrawableCount = 3`.
- `MetalTextView.updateDrawableSize()` reapplies `maximumDrawableCount = 3` after backing-scale/display changes.
- Both layer paths set `wantsExtendedDynamicRangeContent = false`.
- `MetalTextView.startDisplayLink()` creates the display link with `CVDisplayLinkCreateWithCGDisplay(currentDisplayID)` and stores `displayLinkRefreshRate` from `CVDisplayLinkGetNominalOutputVideoRefreshPeriod`.
- `Tests/ItsyRenderTests/MetalTextViewTests.swift` verifies triple-buffered SDR layer configuration.

## Local verification

- Command: `system_profiler SPDisplaysDataType | rg -i "Chipset|Display Type|Resolution|UI Looks|Refresh|ProMotion|Retina"`.
- Result: Apple M3, built-in Liquid Retina Display, 2560 x 1664 Retina.
- No `Refresh` or `ProMotion` field was exposed by `system_profiler` in this environment.
- `CGDisplayCopyDisplayMode(CGMainDisplayID())` reports width `1470`, height `956`, pixel width `2940`, pixel height `1912`, refresh `60.0`.

## Status

- Code and unit coverage for the ProMotion hooks are present.
- I cannot verify 120Hz runtime behavior on this machine because the exposed main display mode is 60 Hz and no 120Hz/ProMotion display is exposed.
