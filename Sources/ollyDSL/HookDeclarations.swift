/// Purpose: Declares one raw or typed lifecycle hook callback.
/// Parameters: Provide a stable label, hook kind, and optional in-memory closure.
/// Example: `Hooks { onTagSwitch { context in _ = context.activeTags } }`
/// See also: `Hooks`, `RawDSLContext`.
public struct HookDeclaration: Codable, Equatable, Sendable {
    public let label: String
    public let kind: HookKind
    public let rawHandler: RawDSLBlock<Void>?
    public let tagSwitchHandler: TagSwitchHookHandler?
    public let displayChangeHandler: DisplayChangeHookHandler?
    public let windowAppearedHandler: WindowAppearedHookHandler?
    public let windowClosedHandler: WindowClosedHookHandler?
    public let configReloadHandler: ConfigReloadHookHandler?
    public let engineChangeHandler: EngineChangeHookHandler?
    public let fullscreenHandler: FullscreenHookHandler?
    public let axPermissionHandler: AXPermissionHookHandler?

    public init(
        label: String,
        kind: HookKind = .raw,
        rawHandler: RawDSLBlock<Void>? = nil,
        tagSwitchHandler: TagSwitchHookHandler? = nil,
        displayChangeHandler: DisplayChangeHookHandler? = nil,
        windowAppearedHandler: WindowAppearedHookHandler? = nil,
        windowClosedHandler: WindowClosedHookHandler? = nil,
        configReloadHandler: ConfigReloadHookHandler? = nil,
        engineChangeHandler: EngineChangeHookHandler? = nil,
        fullscreenHandler: FullscreenHookHandler? = nil,
        axPermissionHandler: AXPermissionHookHandler? = nil
    ) {
        precondition(!label.isEmpty)
        self.label = label
        self.kind = kind
        self.rawHandler = rawHandler
        self.tagSwitchHandler = tagSwitchHandler
        self.displayChangeHandler = displayChangeHandler
        self.windowAppearedHandler = windowAppearedHandler
        self.windowClosedHandler = windowClosedHandler
        self.configReloadHandler = configReloadHandler
        self.engineChangeHandler = engineChangeHandler
        self.fullscreenHandler = fullscreenHandler
        self.axPermissionHandler = axPermissionHandler
    }

    public static func raw(_ label: String = "raw", _ body: @escaping RawDSLHandler) -> HookDeclaration {
        HookDeclaration(label: label, rawHandler: RawDSLBlock(label, body))
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public func runTagSwitch(context: TagSwitchHookContext) {
        tagSwitchHandler?(context)
    }

    public func runDisplayChange(context: DisplayChangeHookContext) {
        displayChangeHandler?(context)
    }

    public func runWindowAppeared(context: WindowAppearedHookContext) {
        windowAppearedHandler?(context)
    }

    public func runWindowClosed(context: WindowClosedHookContext) {
        windowClosedHandler?(context)
    }

    public func runConfigReload(context: ConfigReloadHookContext) {
        configReloadHandler?(context)
    }

    public func runEngineChange(context: EngineChangeHookContext) {
        engineChangeHandler?(context)
    }

    public func runFullscreen(context: FullscreenHookContext) {
        fullscreenHandler?(context)
    }

    public func runAXPermissionChanged(context: AXPermissionHookContext) {
        axPermissionHandler?(context)
    }

    public static func == (lhs: HookDeclaration, rhs: HookDeclaration) -> Bool {
        lhs.label == rhs.label && lhs.kind == rhs.kind
    }

    enum CodingKeys: String, CodingKey {
        case label
        case kind
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            label: try container.decode(String.self, forKey: .label),
            kind: try container.decodeIfPresent(HookKind.self, forKey: .kind) ?? .raw
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(label, forKey: .label)
        try container.encode(kind, forKey: .kind)
    }
}

/// Purpose: Groups raw and typed lifecycle hook declarations.
/// Parameters: Pass hook declarations directly or use `@HookBuilder`.
/// Example: `Hooks { onTagSwitch { context in _ = context.activeTags } }`
/// See also: `HookDeclaration`, `ConfigSection`.
public struct Hooks: Codable, Equatable, Sendable {
    public let declarations: [HookDeclaration]

    public init(_ declarations: [HookDeclaration] = []) {
        self.declarations = declarations
    }

    public init(@HookBuilder _ build: () -> [HookDeclaration]) {
        self.declarations = build()
    }

    public func runRaw(context: RawDSLContext) {
        declarations.forEach { $0.runRaw(context: context) }
    }

    public func runTagSwitch(context: TagSwitchHookContext) {
        declarations.forEach { $0.runTagSwitch(context: context) }
    }

    public func runDisplayChange(context: DisplayChangeHookContext) {
        declarations.forEach { $0.runDisplayChange(context: context) }
    }

    public func runWindowAppeared(context: WindowAppearedHookContext) {
        declarations.forEach { $0.runWindowAppeared(context: context) }
    }

    public func runWindowClosed(context: WindowClosedHookContext) {
        declarations.forEach { $0.runWindowClosed(context: context) }
    }

    public func runConfigReload(context: ConfigReloadHookContext) {
        declarations.forEach { $0.runConfigReload(context: context) }
    }

    public func runEngineChange(context: EngineChangeHookContext) {
        declarations.forEach { $0.runEngineChange(context: context) }
    }

    public func runFullscreenEnter(context: FullscreenHookContext) {
        declarations.filter { $0.kind == .fullscreenEnter }.forEach { $0.runFullscreen(context: context) }
    }

    public func runFullscreenExit(context: FullscreenHookContext) {
        declarations.filter { $0.kind == .fullscreenExit }.forEach { $0.runFullscreen(context: context) }
    }

    public func runAXPermissionChanged(context: AXPermissionHookContext) {
        declarations.forEach { $0.runAXPermissionChanged(context: context) }
    }
}

/// Purpose: Builds lifecycle hook declarations inside `Hooks { ... }`.
/// Parameters: Accepts `HookDeclaration` expressions, arrays, and conditionals.
/// Example: `Hooks { onDisplayChange { context in _ = context.change } }`
/// See also: `Hooks`, `HookDeclaration`.
@resultBuilder
public enum HookBuilder {
    public static func buildBlock(_ components: [HookDeclaration]...) -> [HookDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [HookDeclaration]?) -> [HookDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [HookDeclaration]) -> [HookDeclaration] {
        component
    }

    public static func buildEither(second component: [HookDeclaration]) -> [HookDeclaration] {
        component
    }

    public static func buildArray(_ components: [[HookDeclaration]]) -> [HookDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: HookDeclaration) -> [HookDeclaration] {
        [expression]
    }
}
