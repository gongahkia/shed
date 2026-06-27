# olly DSL Reference

Generated from the `ollyDSL` DocC symbol graph. Do not edit by hand.

## Config

### DSLVersion

`enum DSLVersion`

- Purpose: Versions the Swift DSL schema stored in compiled config payloads.
- Parameters: No direct parameters; choose a case such as `.v1`.
- Example: `Config(version: .v1) { Keybinds() }`
- See also: `Config`, `ConfigLoader`.

### Config

`struct Config`

- Purpose: Top-level olly DSL document composed from keybind, rule, workspace, engine, and hook sections.
- Parameters: Pass section values directly or use `@ConfigBuilder` to compose them.
- Example: `Config { Workspaces { Tag.named("web") }; Engines { .niriScroll } }`
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

### Hooks

`struct Hooks`

- Purpose: Placeholder lifecycle hook section reserved for typed runtime callbacks.
- Parameters: Accepts a closure body for future hook declarations.
- Example: `Hooks { }`
- See also: `Config`, `ConfigSection`.

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

## Rules

### RuleMatch

`struct RuleMatch`

- Purpose: Describes the window properties a DSL rule must match.
- Parameters: Provide optional bundle ID, title regex, role, or subrole predicates.
- Example: `RuleMatch(bundleID: "com.apple.Terminal", titleRegex: "ssh")`
- See also: `Rule`, `RuleContext`.

### RuleContext

`struct RuleContext`

- Purpose: Carries runtime window metadata used to evaluate rule matches.
- Parameters: Provide bundle ID, title, role, and subrole values from a window snapshot.
- Example: `RuleContext(bundleID: "com.apple.finder", title: "Downloads")`
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

