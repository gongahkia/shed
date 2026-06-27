import Foundation

/// Purpose: Versions the Swift DSL schema stored in compiled config payloads.
/// Parameters: No direct parameters; choose a case such as `.v1`.
/// Example: `Config(version: .v1) { Keybinds() }`
/// See also: `Config`, `ConfigLoader`.
public enum DSLVersion: Codable, Equatable, Sendable {
    case v1 // swiftlint:disable:this identifier_name
    case unsupported(String)

    public static let current = DSLVersion.v1

    public var rawValue: String {
        switch self {
        case .v1:
            return "v1"
        case let .unsupported(value):
            return value
        }
    }

    public init(rawValue: String) {
        switch rawValue {
        case "v1":
            self = .v1
        default:
            self = .unsupported(rawValue)
        }
    }

    public init(from decoder: Decoder) throws {
        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

/// Purpose: Top-level olly DSL document composed from keybind, rule, workspace, engine, and hook sections.
/// Parameters: Pass section values directly or use `@ConfigBuilder` to compose them.
/// Example: `Config { Workspaces { Tag.named("web") }; Engines { .niriScroll } }`
/// See also: `ConfigBuilder`, `ConfigSection`.
public struct Config: Codable, Equatable, Sendable {
    public let version: DSLVersion
    public let keybinds: Keybinds
    public let rules: Rules
    public let workspaces: Workspaces
    public let engines: Engines
    public let cooperativeApps: CooperativeApps
    public let safeZones: SafeZones
    public let hooks: Hooks

    public init(
        version: DSLVersion = .v1,
        keybinds: Keybinds = Keybinds(),
        rules: Rules = Rules(),
        workspaces: Workspaces = Workspaces(),
        engines: Engines = Engines(),
        cooperativeApps: CooperativeApps = CooperativeApps(),
        safeZones: SafeZones = SafeZones(),
        hooks: Hooks = Hooks()
    ) {
        self.version = version
        self.keybinds = keybinds
        self.rules = rules
        self.workspaces = workspaces
        self.engines = engines
        self.cooperativeApps = cooperativeApps
        self.safeZones = safeZones
        self.hooks = hooks
    }

    public init(version: DSLVersion = .v1, @ConfigBuilder _ build: () -> [ConfigSection]) {
        self.init(version: version, sections: build())
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            version: try container.decodeIfPresent(DSLVersion.self, forKey: .version) ?? .v1,
            keybinds: try container.decodeIfPresent(Keybinds.self, forKey: .keybinds) ?? Keybinds(),
            rules: try container.decodeIfPresent(Rules.self, forKey: .rules) ?? Rules(),
            workspaces: try container.decodeIfPresent(Workspaces.self, forKey: .workspaces) ?? Workspaces(),
            engines: try container.decodeIfPresent(Engines.self, forKey: .engines) ?? Engines(),
            cooperativeApps: try container.decodeIfPresent(CooperativeApps.self, forKey: .cooperativeApps)
                ?? CooperativeApps(),
            safeZones: try container.decodeIfPresent(SafeZones.self, forKey: .safeZones) ?? SafeZones(),
            hooks: try container.decodeIfPresent(Hooks.self, forKey: .hooks) ?? Hooks()
        )
    }

    private init(version: DSLVersion, sections: [ConfigSection]) {
        var keybinds = Keybinds()
        var rules = Rules()
        var workspaces = Workspaces()
        var engines = Engines()
        var cooperativeApps = CooperativeApps()
        var safeZones = SafeZones()
        var hooks = Hooks()

        for section in sections {
            switch section {
            case let .keybinds(value):
                keybinds = value
            case let .rules(value):
                rules = value
            case let .workspaces(value):
                workspaces = value
            case let .engines(value):
                engines = value
            case let .cooperativeApps(value):
                cooperativeApps = value
            case let .safeZones(value):
                safeZones = value
            case let .hooks(value):
                hooks = value
            }
        }

        self.init(
            version: version,
            keybinds: keybinds,
            rules: rules,
            workspaces: workspaces,
            engines: engines,
            cooperativeApps: cooperativeApps,
            safeZones: safeZones,
            hooks: hooks
        )
    }
}

/// Purpose: Builds ordered `ConfigSection` values inside `Config { ... }`.
/// Parameters: Accepts section expressions, conditionals, and arrays emitted by the config body.
/// Example: `Config { Keybinds(); Rules() }`
/// See also: `Config`, `ConfigSection`.
@resultBuilder
public enum ConfigBuilder {
    public static func buildBlock(_ components: [ConfigSection]...) -> [ConfigSection] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [ConfigSection]?) -> [ConfigSection] {
        component ?? []
    }

    public static func buildEither(first component: [ConfigSection]) -> [ConfigSection] {
        component
    }

    public static func buildEither(second component: [ConfigSection]) -> [ConfigSection] {
        component
    }

    public static func buildArray(_ components: [[ConfigSection]]) -> [ConfigSection] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: Keybinds) -> [ConfigSection] {
        [.keybinds(expression)]
    }

    public static func buildExpression(_ expression: Rules) -> [ConfigSection] {
        [.rules(expression)]
    }

    public static func buildExpression(_ expression: Workspaces) -> [ConfigSection] {
        [.workspaces(expression)]
    }

    public static func buildExpression(_ expression: Engines) -> [ConfigSection] {
        [.engines(expression)]
    }

    public static func buildExpression(_ expression: CooperativeApps) -> [ConfigSection] {
        [.cooperativeApps(expression)]
    }

    public static func buildExpression(_ expression: SafeZones) -> [ConfigSection] {
        [.safeZones(expression)]
    }

    public static func buildExpression(_ expression: Hooks) -> [ConfigSection] {
        [.hooks(expression)]
    }
}

/// Purpose: Wraps each top-level DSL section so `ConfigBuilder` can merge defaults deterministically.
/// Parameters: Use one case per section, such as `.keybinds(Keybinds())`.
/// Example: `ConfigSection.engines(Engines { .bsp })`
/// See also: `Config`, `ConfigBuilder`.
public enum ConfigSection: Codable, Equatable, Sendable {
    case keybinds(Keybinds)
    case rules(Rules)
    case workspaces(Workspaces)
    case engines(Engines)
    case cooperativeApps(CooperativeApps)
    case safeZones(SafeZones)
    case hooks(Hooks)
}

/// Purpose: Declares one raw lifecycle hook callback.
/// Parameters: Provide a stable label and closure receiving `RawDSLContext`.
/// Example: `Hooks { .raw("reload") { context in _ = context.event } }`
/// See also: `Hooks`, `RawDSLContext`.
public struct HookDeclaration: Codable, Equatable, Sendable {
    public let label: String
    public let rawHandler: RawDSLBlock<Void>?

    public init(label: String, rawHandler: RawDSLBlock<Void>? = nil) {
        precondition(!label.isEmpty)
        self.label = label
        self.rawHandler = rawHandler
    }

    public static func raw(_ label: String = "raw", _ body: @escaping RawDSLHandler) -> HookDeclaration {
        HookDeclaration(label: label, rawHandler: RawDSLBlock(label, body))
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public static func == (lhs: HookDeclaration, rhs: HookDeclaration) -> Bool {
        lhs.label == rhs.label
    }

    enum CodingKeys: String, CodingKey {
        case label
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(label: try container.decode(String.self, forKey: .label))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(label, forKey: .label)
    }
}

/// Purpose: Placeholder lifecycle hook section reserved for typed runtime callbacks.
/// Parameters: Pass hook declarations directly or use `@HookBuilder`.
/// Example: `Hooks { .raw("startup") { _ in } }`
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
}

/// Purpose: Builds lifecycle hook declarations inside `Hooks { ... }`.
/// Parameters: Accepts `HookDeclaration` expressions, arrays, and conditionals.
/// Example: `Hooks { .raw("reload") { _ in } }`
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
