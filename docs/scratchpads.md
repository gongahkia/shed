# Scratchpads

Scratchpads park matching windows offscreen and restore them independently of tag visibility.

```sh
ollyctl scratchpad-add --name term --bundle com.apple.Terminal --title-regex Scratch
ollyctl scratchpad-toggle --name term
ollyctl scratchpad-list
ollyctl scratchpad-remove --name term
```

The DSL form mirrors the IPC fields:

```swift
Scratchpads {
    Scratchpad("term") {
        bundleID("com.apple.Terminal")
        titleRegex("^Scratch")
        role("AXWindow")
    }
}
```

Limitation: without `OLLY_ENABLE_PRIVATE_AX_WINDOW_ID`, the public `WindowID` fallback depends on the current
CG window title. Apps that rename windows frequently can be matched incorrectly; add a stable `bundleID`, `role`, and
`titleRegex` when possible.
