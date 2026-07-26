# olly DSL Cookbook

Use these snippets inside `public func ollyConfig() -> Config { Config { ... } }` unless shown as a full file.

Shared helper used below:

```swift
private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
```

## 1. Minimal Floating Config

```swift
Workspaces { Tag.named("main") }
Engines { EngineDeclaration.floating }
Keybinds {
    Keybind(KeyChord([.command, .option], .return), do: .cycleEngine)
}
```

## 2. Niri-Only Setup

```swift
Workspaces { Tag.named("web"); Tag.named("code"); Tag.named("chat") }
Engines { EngineDeclaration.niriScroll }
Keybinds {
    Keybind(KeyChord([.option], .h), do: .focus(.left))
    Keybind(KeyChord([.option], .l), do: .focus(.right))
}
```

## 3. Float Slack

```swift
Rules {
    Rule(
        match: bundleID("com.tinyspeck.slackmacgap"),
        apply: RuleApply(tags: tag(0), engine: .floating, floating: true)
    )
}
```

## 4. Float All AX Dialogs

```swift
Rules {
    Rule(match: subrole("AXDialog"), apply: RuleApply(engine: .floating, floating: true))
}
```

## 5. Downloads Always MasterStack

```swift
Rules {
    Rule(
        match: bundleID("com.apple.finder") && titleRegex("^Downloads"),
        apply: RuleApply(tags: tag(3), engine: .masterStack, floating: false)
    )
}
```

## 6. Tag #4 Always BSP

```swift
Workspaces { Tag.named("1"); Tag.named("2"); Tag.named("3"); Tag.named("4") }
Engines { EngineDeclaration.bsp }
Keybinds {
    Keybind(KeyChord([.command, .option], .four), do: .switchTag(3))
    Keybind(KeyChord([.command, .shift], .four), do: .moveWindowToTag(3))
}
Rules {
    Rule(match: bundleID("com.apple.Terminal"), apply: RuleApply(tags: tag(3), engine: .bsp))
}
```

## 7. Scratchpad Tag

```swift
Workspaces { Tag.named("main"); Tag.named("scratch") }
Keybinds {
    Keybind(KeyChord([.command, .option], .s), do: .switchTag(1))
    Keybind(KeyChord([.command, .shift], .s), do: .moveWindowToTag(1))
}
Rules {
    Rule(match: subrole("AXSystemDialog"), apply: RuleApply(tags: tag(1), engine: .floating, floating: true))
}
```

## 8. Different Engine Per App

```swift
Rules {
    Rule(match: bundleID("com.apple.Terminal"), apply: RuleApply(engine: .bsp))
    Rule(match: bundleID("com.apple.Safari"), apply: RuleApply(engine: .niriScroll))
    Rule(match: bundleID("com.apple.finder"), apply: RuleApply(engine: .floating, floating: true))
}
```

## 9. Different Engine Per Display Pattern

Use display-scoped IPC commands from launchers or raw hooks until display-scoped DSL engine policy lands.

```swift
Hooks {
    onDisplayChange { context in
        _ = context.change.displayID
    }
}
Keybinds {
    Keybind.raw(KeyChord([.command, .option], .leftArrow), label: "left-display-bsp") { _ in }
    Keybind.raw(KeyChord([.command, .option], .rightArrow), label: "right-display-niri") { _ in }
}
```

## 10. Follow Focus To Display Hook

```swift
Hooks {
    onTagSwitch { context in
        _ = context.displayID
        _ = context.activeTags
    }
}
```

## 11. Auto-Rotate Workspace On Display Unplug

```swift
Hooks {
    onDisplayChange { context in
        _ = context.change.displays
    }
}
```

## 12. Global Animation

```swift
Animation {
    duration(200.ms)
    curve(.easeOut)
    reduceMotion(.respectSystem)
}
```

## 13. Per-Engine Animation Override

```swift
Engines {
    EngineDeclaration.niriScroll.animated(
        Animation {
            duration(160.ms)
            curve(.easeInOut)
        }
    )
}
```

## 14. Disable Animation In Config

```swift
Animation {
    duration(0.ms)
    curve(.linear)
    reduceMotion(.neverAnimate)
}
```

## 15. Focus Ring

```swift
FocusRing {
    color(.systemBlue)
    width(2)
    cornerRadius(8)
    reduceMotion(.respectSystem)
}
```

## 16. Four-Finger Column Scroll

```swift
Gestures {
    fourFingerHorizontal(.scrollColumns)
}
```

## 17. Four-Finger Tag Switching

```swift
Gestures {
    fourFingerVertical(.switchTags)
}
```

## 18. Gesture Bound To Existing Action

```swift
Gestures {
    fourFingerHorizontal(.action(.cycleEngine))
}
```

## 19. Safe Zone For Notch

```swift
SafeZones {
    notchPadding(16)
}
```

## 20. Reserve A Custom Bar

```swift
SafeZones {
    reserve(rect: CGRect(x: 0, y: 900, width: 1512, height: 82), on: 1)
}
```

## 21. Add A Custom Snap Zone

```swift
SafeZones {
    customZone(name: "leftQuarter", rect: CGRect(x: 0, y: 0, width: 378, height: 982), on: 1)
}
```

## 22. Cooperative Overlay App

```swift
CooperativeApps {
    CooperativeApp("com.example.CustomOverlay", behavior: .floatAndReserveSpace)
}
```

## 23. Replace Cooperative Defaults

```swift
CooperativeApps(mode: .replace) {
    CooperativeApp("com.example.OnlyOverlay")
}
```

## 24. Raw Keybind Escape Hatch

```swift
Keybinds {
    Keybind.raw(KeyChord([.command], .r), label: "custom.reload") { context in
        _ = context.event
    }
}
```

## 25. Raw Rule Escape Hatch

```swift
Rules {
    Rule.raw(match: bundleID("com.example.App"), apply: RuleApply(floating: true), label: "custom.rule") { context in
        _ = context.ruleContext
    }
}
```

## 26. Raw Engine Declaration

```swift
Engines {
    EngineDeclaration.raw(LayoutEngineID(rawValue: "dev.olly.example.dynamic")) { context in
        _ = context.engineID
    }
}
```

## 26. Plugin Author Engine ID

```swift
Engines {
    EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.example.hello"))
}
Rules {
    Rule(
        match: bundleID("com.example.PluginPreview"),
        apply: RuleApply(engine: LayoutEngineID(rawValue: "dev.olly.example.hello"))
    )
}
```

## 27. Parent Bundle Predicate

```swift
Rules {
    Rule(
        match: parentBundleID("com.apple.dt.Xcode") && !subrole("AXDialog"),
        apply: RuleApply(engine: .niriScroll)
    )
}
```

## 28. Window Size Predicate

```swift
Rules {
    Rule(
        match: bundleID("com.apple.Terminal") && windowSize(.largerThan(CGSize(width: 600, height: 400))),
        apply: RuleApply(engine: .bsp, floating: false)
    )
}
```

## 29. Multiple Tags For One App

```swift
Rules {
    Rule(
        match: bundleID("com.apple.Terminal"),
        apply: RuleApply(tags: tag(0).union(tag(4)), engine: .bsp)
    )
}
```

## 30. Ultrawide Three-Column Layout

```swift
Engines {
    ThreeCol(masterRatio: 0.42)
    Grid(.fixedCols(3))
}
Rules {
    Rule(match: bundleID("com.apple.dt.Xcode"), apply: RuleApply(tags: tag(0), engine: .threeCol))
}
```

## 31. Master-Stack Heavy Workflow

```swift
Engines {
    EngineDeclaration.masterStack
    EngineDeclaration.floating
}
Keybinds {
    Keybind(KeyChord([.command, .option], .m), do: .setEngine(.masterStack))
}
```

## 32. Cycle Built-In Engines

```swift
Engines {
    EngineDeclaration.floating
    EngineDeclaration.masterStack
    EngineDeclaration.bsp
    EngineDeclaration.niriScroll
}
Keybinds {
    Keybind(KeyChord([.command, .option], .space), do: .cycleEngine)
}
```

## 33. Named Tag Grid

```swift
Workspaces {
    Tag.named("comms")
    Tag.named("code")
    Tag.named("web")
    Tag.named("docs")
    Tag.named("media")
    Tag.named("ops")
}
```

## 34. Grid Overlay Keybind

```swift
Keybinds {
    Keybind(KeyChord([.command, .shift], .slash), do: .showGridOverlay)
}
```
