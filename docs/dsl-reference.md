# olly DSL Reference

Generated from the `ollyDSL` DocC symbol graph. Do not edit by hand.

## Animation

### AnimationDuration

`struct AnimationDuration`

- Purpose: Stores an animation duration in milliseconds.
- Parameters: Provide a non-negative millisecond value directly or via `.ms`.
- Example: `200.ms`
- See also: `Animation`, `duration(_:)`.

### AnimationCurve

`enum AnimationCurve`

- Purpose: Selects the timing curve for layout animations.
- Parameters: Choose a named curve.
- Example: `AnimationCurve.easeOut`
- See also: `Animation`, `curve(_:)`.

### ReduceMotionPolicy

`enum ReduceMotionPolicy`

- Purpose: Selects how animation respects macOS Reduce Motion.
- Parameters: Choose system-respecting, always-on, or always-off animation behavior.
- Example: `ReduceMotionPolicy.respectSystem`
- See also: `Animation`, `reduceMotion(_:)`.

### AnimationSetting

`enum AnimationSetting`

- Purpose: Represents one animation builder setting.
- Parameters: Use `duration`, `curve`, or `reduceMotion` builder helpers.
- Example: `duration(200.ms)`
- See also: `Animation`, `AnimationBuilder`.

### Animation

`struct Animation`

- Purpose: Configures global or per-engine layout animation behavior.
- Parameters: Provide duration, timing curve, and Reduce Motion policy.
- Example: `Animation { duration(200.ms); curve(.easeOut); reduceMotion(.respectSystem) }`
- See also: `AnimationBuilder`, `EngineDeclaration`.

### AnimationBuilder

`@resultBuilder enum AnimationBuilder`

- Purpose: Builds animation settings inside `Animation { ... }`.
- Parameters: Accepts animation setting expressions, arrays, and conditionals.
- Example: `Animation { duration(120.ms); curve(.linear) }`
- See also: `Animation`, `AnimationSetting`.

### duration(_:)

`func duration(_ value: AnimationDuration) -> AnimationSetting`

- Purpose: Declares an animation duration setting.
- Parameters: Pass an `AnimationDuration`, commonly with `.ms`.
- Example: `duration(200.ms)`
- See also: `Animation`, `AnimationSetting`.

### curve(_:)

`func curve(_ value: AnimationCurve) -> AnimationSetting`

- Purpose: Declares an animation curve setting.
- Parameters: Pass an `AnimationCurve`.
- Example: `curve(.easeOut)`
- See also: `Animation`, `AnimationSetting`.

### reduceMotion(_:)

`func reduceMotion(_ value: ReduceMotionPolicy) -> AnimationSetting`

- Purpose: Declares a Reduce Motion animation policy.
- Parameters: Pass a `ReduceMotionPolicy`.
- Example: `reduceMotion(.respectSystem)`
- See also: `Animation`, `AnimationSetting`.

## Config

### DSLVersion

`enum DSLVersion`

- Purpose: Versions the Swift DSL schema stored in compiled config payloads.
- Parameters: No direct parameters; choose a case such as `.v1`.
- Example: `Config(version: .v1) { Keybinds() }`
- See also: `Config`, `ConfigLoader`.

### Config

`struct Config`

- Purpose: Top-level olly DSL document composed from keybind, rule, workspace, engine, animation, and hook sections.
- Parameters: Pass section values directly or use `@ConfigBuilder` to compose them.
- Example: `Config { Workspaces { Tag.named("web") }; Animation { duration(200.ms) } }`
- See also: `ConfigBuilder`, `ConfigSection`.

### ConfigBuilder

`@resultBuilder enum ConfigBuilder`

- Purpose: Builds ordered `ConfigSection` values inside `Config { ... }`.
- Parameters: Accepts section expressions, conditionals, and arrays emitted by the config body.
- Example: `Config { Keybinds(); Rules() }`
- See also: `Config`, `ConfigSection`.

### ConfigSection

`enum ConfigSection`

- Purpose: Wraps each top-level DSL section so `ConfigBuilder` can merge defaults deterministically.
- Parameters: Use one case per section, such as `.keybinds(Keybinds())`.
- Example: `ConfigSection.engines(Engines { .bsp })`
- See also: `Config`, `ConfigBuilder`.

### HookDeclaration

`struct HookDeclaration`

- Purpose: Declares one raw or typed lifecycle hook callback.
- Parameters: Provide a stable label, hook kind, and optional in-memory closure.
- Example: `Hooks { onTagSwitch { context in _ = context.activeTags } }`
- See also: `Hooks`, `RawDSLContext`.

### Hooks

`struct Hooks`

- Purpose: Groups raw and typed lifecycle hook declarations.
- Parameters: Pass hook declarations directly or use `@HookBuilder`.
- Example: `Hooks { onTagSwitch { context in _ = context.activeTags } }`
- See also: `HookDeclaration`, `ConfigSection`.

### HookBuilder

`@resultBuilder enum HookBuilder`

- Purpose: Builds lifecycle hook declarations inside `Hooks { ... }`.
- Parameters: Accepts `HookDeclaration` expressions, arrays, and conditionals.
- Example: `Hooks { onDisplayChange { context in _ = context.change } }`
- See also: `Hooks`, `HookDeclaration`.

## Hooks

### HookKind

`enum HookKind`

- Purpose: Names the lifecycle event kind represented by a hook declaration.
- Parameters: Choose raw, tag switch, display change, or window appeared.
- Example: `HookKind.tagSwitch`
- See also: `HookDeclaration`, `Hooks`.

### TagSwitchHookContext

`struct TagSwitchHookContext`

- Purpose: Carries typed context for tag-switch lifecycle hooks.
- Parameters: Provide display ID, previous tags, and active tags after the switch.
- Example: `TagSwitchHookContext(displayID: 1, previousTags: [], activeTags: TagSet(rawValue: 2))`
- See also: `onTagSwitch(_:_:)`, `Hooks`.

### DisplayChangeHookContext

`struct DisplayChangeHookContext`

- Purpose: Carries typed context for display-change lifecycle hooks.
- Parameters: Provide the `DisplayChange` emitted by `DisplayMonitor`.
- Example: `DisplayChangeHookContext(change: change)`
- See also: `onDisplayChange(_:_:)`, `Hooks`.

### WindowAppearedHookContext

`struct WindowAppearedHookContext`

- Purpose: Carries typed context for window-appeared lifecycle hooks.
- Parameters: Provide the `WindowState` that appeared.
- Example: `WindowAppearedHookContext(window: window)`
- See also: `onWindowAppeared(_:_:)`, `Hooks`.

### onTagSwitch(_:_:)

`func onTagSwitch(_ label: String = "onTagSwitch", _ body: @escaping TagSwitchHookHandler) -> HookDeclaration`

- Purpose: Declares a typed hook for tag switches.
- Parameters: Provide an optional stable label and a handler receiving `TagSwitchHookContext`.
- Example: `onTagSwitch { context in _ = context.activeTags }`
- See also: `TagSwitchHookContext`, `Hooks`.

### onDisplayChange(_:_:)

`func onDisplayChange(_ label: String = "onDisplayChange", _ body: @escaping DisplayChangeHookHandler) -> HookDeclaration`

- Purpose: Declares a typed hook for display changes.
- Parameters: Provide an optional stable label and a handler receiving `DisplayChangeHookContext`.
- Example: `onDisplayChange { context in _ = context.change.displayID }`
- See also: `DisplayChangeHookContext`, `Hooks`.

### onWindowAppeared(_:_:)

`func onWindowAppeared(_ label: String = "onWindowAppeared", _ body: @escaping WindowAppearedHookHandler) -> HookDeclaration`

- Purpose: Declares a typed hook for newly appeared windows.
- Parameters: Provide an optional stable label and a handler receiving `WindowAppearedHookContext`.
- Example: `onWindowAppeared { context in _ = context.window.bundleID }`
- See also: `WindowAppearedHookContext`, `Hooks`.

## Keybinds

### KeyModifiers

`struct KeyModifiers`

- Purpose: Represents command, shift, option, and control modifiers for DSL key chords.
- Parameters: Use predefined flags or initialize from a raw Carbon-compatible bit mask.
- Example: `KeyModifiers([.command, .option])`
- See also: `KeyChord`, `Keybind`.

### Key

`struct Key`

- Purpose: Represents one physical keyboard key in a DSL key chord.
- Parameters: Use predefined static keys or initialize from a Carbon virtual key code.
- Example: `Key.a`
- See also: `KeyModifiers`, `KeyChord`.

### KeyChord

`struct KeyChord`

- Purpose: Combines modifiers and a key into one bindable shortcut chord.
- Parameters: Pass `KeyModifiers` and `Key` values in order.
- Example: `KeyChord([.command, .option], .return)`
- See also: `Keybind`, `Keybinds`.

### Direction

`enum Direction`

- Purpose: Names directional movement targets for focus, swap, and move actions.
- Parameters: Use a case such as `.left`, `.next`, or `.previous`.
- Example: `Action.focus(.next)`
- See also: `Action`, `Keybind`.

### Action

`enum Action`

- Purpose: Declares the command performed when a keybind fires.
- Parameters: Select a case and provide its associated direction, tag, engine, or raw command.
- Example: `Action.setEngine(BSPLayoutEngine.engineID)`
- See also: `Keybind`, `Direction`.

### Keybind

`struct Keybind`

- Purpose: Maps one `KeyChord` to one olly action.
- Parameters: Pass the chord and the action to execute.
- Example: `Keybind(KeyChord([.command], .return), do: .cycleEngine)`
- See also: `Keybinds`, `Action`.

### Keybinds

`struct Keybinds`

- Purpose: Groups keybind declarations for the top-level config.
- Parameters: Pass an array of `Keybind` values or use `@KeybindBuilder`.
- Example: `Keybinds { Keybind(KeyChord([.command], .j), do: .focus(.next)) }`
- See also: `Keybind`, `KeybindBuilder`.

### KeybindBuilder

`@resultBuilder enum KeybindBuilder`

- Purpose: Builds keybind declarations inside `Keybinds { ... }`.
- Parameters: Accepts `Keybind` expressions, arrays, and conditionals.
- Example: `Keybinds { Keybind(KeyChord([.command], .space), do: .noop) }`
- See also: `Keybinds`, `Keybind`.

## Workspaces

### WorkspacesError

`enum WorkspacesError`

- Purpose: Reports invalid workspace tag declarations before they become runtime state.
- Parameters: Inspect the associated duplicate name or tag count.
- Example: `XCTAssertThrowsError(try Workspaces(validating: declarations))`
- See also: `Workspaces`, `NamedTagDeclaration`.

### NamedTagDeclaration

`struct NamedTagDeclaration`

- Purpose: Captures a user-facing workspace tag name before assigning its numeric tag.
- Parameters: Pass a static tag name.
- Example: `Tag.named("web")`
- See also: `NamedTag`, `Workspaces`.

### NamedTag

`struct NamedTag`

- Purpose: Binds a display name to a concrete River-style tag bit.
- Parameters: Pass the visible name and assigned `Tag`.
- Example: `NamedTag(name: "code", tag: try Tag(index: 1))`
- See also: `NamedTagDeclaration`, `Workspaces`.

### Workspaces

`struct Workspaces`

- Purpose: Groups named workspace tags for the top-level config.
- Parameters: Pass resolved tags or build named tag declarations.
- Example: `Workspaces { Tag.named("web"); Tag.named("code") }`
- See also: `NamedTagDeclaration`, `WorkspacesBuilder`.

### WorkspacesBuilder

`@resultBuilder enum WorkspacesBuilder`

- Purpose: Builds named tag declarations inside `Workspaces { ... }`.
- Parameters: Accepts `NamedTagDeclaration` expressions, arrays, and conditionals.
- Example: `Workspaces { Tag.named("chat") }`
- See also: `Workspaces`, `NamedTagDeclaration`.

## Engines

### EngineConfigDeclaration

`enum EngineConfigDeclaration`

- Purpose: Stores typed built-in engine configuration payloads for DSL-declared engines.
- Parameters: Choose the case matching the engine primitive being configured.
- Example: `EngineConfigDeclaration.grid(GridLayoutEngine.Config(policy: .squareish))`
- See also: `EngineDeclaration`, `Engines`.

### EngineDeclaration

`struct EngineDeclaration`

- Purpose: Declares one layout engine and its optional typed configuration.
- Parameters: Pass a `LayoutEngineID` and optional `EngineConfigDeclaration`.
- Example: `EngineDeclaration(BSPLayoutEngine.engineID)`
- See also: `Engines`, `EngineConfigDeclaration`.

### Monocle()

`func Monocle() -> EngineDeclaration`

- Purpose: Declares the Monocle layout engine.
- Parameters: No parameters; Monocle expands focus to display bounds and hides siblings.
- Example: `Engines { Monocle() }`
- See also: `Spiral()`, `Grid(_:)`.

### Spiral(splitRatio:)

`func Spiral(splitRatio: CGFloat = SpiralLayoutEngine.Config.goldenRatio) -> EngineDeclaration`

- Purpose: Declares the Spiral layout engine.
- Parameters: `splitRatio` controls each recursive split and defaults to the golden ratio.
- Example: `Engines { Spiral(splitRatio: 0.6) }`
- See also: `Monocle()`, `Grid(_:)`.

### Grid(_:)

`func Grid(_ policy: GridLayoutPolicy = .squareish) -> EngineDeclaration`

- Purpose: Declares the Grid layout engine.
- Parameters: `policy` selects square-ish, fixed-row, or fixed-column grid sizing.
- Example: `Engines { Grid(.fixedCols(3)) }`
- See also: `Spiral(splitRatio:)`, `ThreeCol(masterRatio:)`.

### ThreeCol(masterRatio:)

`func ThreeCol(masterRatio: CGFloat = 0.5) -> EngineDeclaration`

- Purpose: Declares the ThreeCol layout engine.
- Parameters: `masterRatio` sets the centered master column width fraction.
- Example: `Engines { ThreeCol(masterRatio: 0.45) }`
- See also: `Grid(_:)`, `Accordion(stripHeight:)`.

### Accordion(stripHeight:)

`func Accordion(stripHeight: CGFloat = 48) -> EngineDeclaration`

- Purpose: Declares the Accordion layout engine.
- Parameters: `stripHeight` sets the collapsed sibling strip height in points.
- Example: `Engines { Accordion(stripHeight: 40) }`
- See also: `ThreeCol(masterRatio:)`, `Monocle()`.

### Engines

`struct Engines`

- Purpose: Groups engine declarations available to workspace and tag bindings.
- Parameters: Pass an array or use `@EngineBuilder` with `EngineDeclaration` expressions.
- Example: `Engines { .niriScroll; Grid() }`
- See also: `EngineDeclaration`, `EngineBuilder`.

### EngineBuilder

`@resultBuilder enum EngineBuilder`

- Purpose: Builds engine declarations inside `Engines { ... }`.
- Parameters: Accepts engine expressions, arrays, and conditional branches.
- Example: `Engines { .floating; .bsp; Monocle() }`
- See also: `Engines`, `EngineDeclaration`.

## Rule Predicates

### WindowSizePredicate

`enum WindowSizePredicate`

- Purpose: Selects a window-size comparison for rule predicates.
- Parameters: Use `.smallerThan` or `.largerThan` with a target size.
- Example: `windowSize(.smallerThan(CGSize(width: 800, height: 600)))`
- See also: `windowSize(_:)`, `RulePredicate`.

### RulePredicate

`struct RulePredicate`

- Purpose: Represents a composable rule predicate tree.
- Parameters: Build values with `bundleID`, `titleRegex`, `role`, `subrole`, `windowSize`, and operators.
- Example: `bundleID("com.apple.Terminal") && role("AXWindow")`
- See also: `RuleMatch`, `Rule`.

### bundleID(_:)

`func bundleID(_ value: String) -> RulePredicate`

- Purpose: Matches windows owned by a bundle identifier.
- Parameters: Pass the exact application bundle identifier.
- Example: `bundleID("com.apple.Safari")`
- See also: `RulePredicate`, `RuleMatch`.

### titleRegex(_:)

`func titleRegex(_ pattern: String) -> RulePredicate`

- Purpose: Matches windows whose title satisfies a regular expression.
- Parameters: Pass an `NSRegularExpression` pattern string.
- Example: `titleRegex("^Downloads")`
- See also: `RulePredicate`, `RuleMatch`.

### role(_:)

`func role(_ value: String) -> RulePredicate`

- Purpose: Matches windows by Accessibility role.
- Parameters: Pass the exact AX role string, such as `AXWindow`.
- Example: `role("AXWindow")`
- See also: `RulePredicate`, `RuleMatch`.

### subrole(_:)

`func subrole(_ value: String) -> RulePredicate`

- Purpose: Matches windows by Accessibility subrole.
- Parameters: Pass the exact AX subrole string, such as `AXDialog`.
- Example: `subrole("AXDialog")`
- See also: `RulePredicate`, `RuleMatch`.

### windowSize(_:)

`func windowSize(_ predicate: WindowSizePredicate) -> RulePredicate`

- Purpose: Matches windows by their current frame size.
- Parameters: Pass a `WindowSizePredicate` comparison.
- Example: `windowSize(.largerThan(CGSize(width: 1200, height: 700)))`
- See also: `WindowSizePredicate`, `RulePredicate`.

### parentBundleID(_:)

`func parentBundleID(_ value: String) -> RulePredicate`

- Purpose: Matches windows whose parent process has a bundle identifier.
- Parameters: Pass the exact parent application bundle identifier.
- Example: `parentBundleID("com.apple.dt.Xcode")`
- See also: `RulePredicate`, `RuleMatch`.

### &&(_:_:)

`func && (lhs: RulePredicate, rhs: RulePredicate) -> RulePredicate`

- Purpose: Combines two rule predicates and requires both to match.
- Parameters: Put `&&` between two `RulePredicate` values.
- Example: `bundleID("com.apple.Terminal") && role("AXWindow")`
- See also: `RulePredicate`, `Rule`.

### ||(_:_:)

`func || (lhs: RulePredicate, rhs: RulePredicate) -> RulePredicate`

- Purpose: Combines two rule predicates and accepts either match.
- Parameters: Put `||` between two `RulePredicate` values.
- Example: `bundleID("com.apple.Safari") || parentBundleID("com.apple.dt.Xcode")`
- See also: `RulePredicate`, `Rule`.

### !(_:)

`func ! (predicate: RulePredicate) -> RulePredicate`

- Purpose: Negates one rule predicate.
- Parameters: Prefix a `RulePredicate` with `!`.
- Example: `!subrole("AXDialog")`
- See also: `RulePredicate`, `Rule`.

## Rules

### RuleMatch

`struct RuleMatch`

- Purpose: Describes the window properties a DSL rule must match.
- Parameters: Provide optional legacy fields and/or a composed `RulePredicate`.
- Example: `RuleMatch(bundleID: "com.apple.Terminal", predicate: role("AXWindow"))`
- See also: `Rule`, `RulePredicate`.

### RuleContext

`struct RuleContext`

- Purpose: Carries runtime window metadata used to evaluate rule matches.
- Parameters: Provide bundle ID, title, role, subrole, parent bundle ID, and window size values.
- Example: `RuleContext(bundleID: "com.apple.finder", windowSize: CGSize(width: 500, height: 400))`
- See also: `RuleMatch`, `Rules`.

### RuleApply

`struct RuleApply`

- Purpose: Declares the tag, engine, and floating changes applied by matching rules.
- Parameters: Provide optional tags, engine override, or floating state.
- Example: `RuleApply(tags: TagSet(try Tag(index: 2)), engine: BSPLayoutEngine.engineID)`
- See also: `Rule`, `Rules`.

### Rule

`struct Rule`

- Purpose: Couples one `RuleMatch` predicate with one `RuleApply` payload.
- Parameters: Pass a match object and the changes to apply when it matches.
- Example: `Rule(match: RuleMatch(bundleID: "com.slack.Slack"), apply: RuleApply(floating: true))`
- See also: `Rules`, `RuleBuilder`.

### Rules

`struct Rules`

- Purpose: Groups rule declarations and resolves their cumulative apply payload.
- Parameters: Pass an array of `Rule` values or use `@RuleBuilder`.
- Example: `Rules { Rule(match: RuleMatch(role: "AXDialog"), apply: RuleApply(floating: true)) }`
- See also: `Rule`, `RuleApply`.

### RuleBuilder

`@resultBuilder enum RuleBuilder`

- Purpose: Builds rule declarations inside `Rules { ... }`.
- Parameters: Accepts `Rule` expressions, arrays, and conditional branches.
- Example: `Rules { Rule(match: RuleMatch(subrole: "AXSystemDialog"), apply: RuleApply(floating: true)) }`
- See also: `Rules`, `Rule`.

## Safe Zones

### SafeZoneReservation

`struct SafeZoneReservation`

- Purpose: Declares one user-reserved rectangle that tiled windows must avoid.
- Parameters: Pass the reserved `CGRect` and target display ID.
- Example: `SafeZoneReservation(rect: CGRect(x: 0, y: 0, width: 100, height: 40), displayID: 1)`
- See also: `reserve(rect:on:)`, `SafeZones`.

### SafeZoneDeclaration

`enum SafeZoneDeclaration`

- Purpose: Represents one safe-zone DSL declaration before it is folded into `SafeZones`.
- Parameters: Use `.notchPadding` or `.reserve`.
- Example: `SafeZoneDeclaration.notchPadding(24)`
- See also: `SafeZones`, `SafeZoneBuilder`.

### SafeZones

`struct SafeZones`

- Purpose: Configures display regions excluded from tiled placements.
- Parameters: Provide notch padding and user reserve rectangles or use `@SafeZoneBuilder`.
- Example: `SafeZones { notchPadding(16); reserve(rect: rect, on: displayID) }`
- See also: `SafeZoneReservation`, `SafeZoneCalculator`.

### notchPadding(_:)

`func notchPadding(_ value: CGFloat) -> SafeZoneDeclaration`

- Purpose: Declares extra padding around the detected display notch safe area.
- Parameters: Pass a non-negative padding value in points.
- Example: `SafeZones { notchPadding(24) }`
- See also: `SafeZones`, `reserve(rect:on:)`.

### reserve(rect:on:)

`func reserve(rect: CGRect, on displayID: DisplayID) -> SafeZoneDeclaration`

- Purpose: Declares a custom no-tile rectangle on a display.
- Parameters: Pass the rectangle and display ID to reserve.
- Example: `SafeZones { reserve(rect: CGRect(x: 0, y: 0, width: 200, height: 40), on: 1) }`
- See also: `SafeZones`, `notchPadding(_:)`.

### SafeZoneBuilder

`@resultBuilder enum SafeZoneBuilder`

- Purpose: Builds safe-zone declarations inside `SafeZones { ... }`.
- Parameters: Accepts safe-zone declarations, arrays, and conditionals.
- Example: `SafeZones { notchPadding(12) }`
- See also: `SafeZones`, `SafeZoneDeclaration`.

## Cooperative Apps

### CooperativeAppsMode

`enum CooperativeAppsMode`

- Purpose: Chooses whether configured cooperative apps extend or replace olly's default allowlist.
- Parameters: Use `.extend` to add bundle IDs or `.replace` to ignore defaults.
- Example: `CooperativeApps(mode: .replace, bundleIDs: ["com.example.Overlay"])`
- See also: `CooperativeApps`, `CooperativeApp`.

### CooperativeApp

`struct CooperativeApp`

- Purpose: Declares one app bundle ID that olly should avoid tiling by default.
- Parameters: Pass a non-empty bundle identifier string.
- Example: `CooperativeApp("com.felixkratz.SketchyBar")`
- See also: `CooperativeApps`, `CooperativeAppBuilder`.

### CooperativeApps

`struct CooperativeApps`

- Purpose: Configures apps whose windows should be floated for ecosystem compatibility.
- Parameters: Select a mode and provide bundle IDs or `CooperativeApp` entries.
- Example: `CooperativeApps { "com.example.NotchOverlay" }`
- See also: `CooperativeAppsMode`, `CooperativeApp`.

### CooperativeAppBuilder

`@resultBuilder enum CooperativeAppBuilder`

- Purpose: Builds cooperative-app declarations inside `CooperativeApps { ... }`.
- Parameters: Accepts `CooperativeApp` and string expressions plus conditionals and arrays.
- Example: `CooperativeApps { "com.raycast.macos" }`
- See also: `CooperativeApps`, `CooperativeApp`.

## Raw Escape Hatches

### RawDSLContext

`struct RawDSLContext`

- Purpose: Carries runtime state into raw Swift DSL closures.
- Parameters: Provide any currently available config, window, rule, engine, tag, or event value.
- Example: `RawDSLContext(engineID: BSPLayoutEngine.engineID)`
- See also: `RawDSLBlock`, `Hooks`.

### RawDSLBlock

`struct RawDSLBlock<Output>`

- Purpose: Stores a named raw Swift closure attached to a DSL primitive.
- Parameters: Provide a stable label and closure receiving `RawDSLContext`.
- Example: `RawDSLBlock("trace") { context in _ = context.event }`
- See also: `RawDSLContext`, `HookDeclaration`.

