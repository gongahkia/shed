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

/// Purpose: Top-level olly DSL document composed from all config sections.
/// Parameters: Pass section values directly or use `@ConfigBuilder` to compose them.
/// Example: `Config { Permissions { shellExec(.off) }; Gestures { fourFingerVertical(.switchTags) } }`
/// See also: `ConfigBuilder`, `ConfigSection`.
public struct Config: Codable, Equatable, Sendable {
    public let version: DSLVersion
    public let keybinds: Keybinds
    public let rules: Rules
    public let workspaces: Workspaces
    public let engines: Engines
    public let cooperativeApps: CooperativeApps
    public let safeZones: SafeZones
    public let animation: Animation
    public let gestures: Gestures
    public let hooks: Hooks
    public let nativeSpace: NativeSpace
    public let focusPolicy: FocusPolicy
    public let permissions: Permissions

    public init(
        version: DSLVersion = .v1,
        keybinds: Keybinds = Keybinds(),
        rules: Rules = Rules(),
        workspaces: Workspaces = Workspaces(),
        engines: Engines = Engines(),
        cooperativeApps: CooperativeApps = CooperativeApps(),
        safeZones: SafeZones = SafeZones(),
        animation: Animation = Animation(),
        gestures: Gestures = Gestures(),
        hooks: Hooks = Hooks(),
        nativeSpace: NativeSpace = NativeSpace(),
        focusPolicy: FocusPolicy = FocusPolicy(),
        permissions: Permissions = Permissions()
    ) {
        self.version = version
        self.keybinds = keybinds
        self.rules = rules
        self.workspaces = workspaces
        self.engines = engines
        self.cooperativeApps = cooperativeApps
        self.safeZones = safeZones
        self.animation = animation
        self.gestures = gestures
        self.hooks = hooks
        self.nativeSpace = nativeSpace
        self.focusPolicy = focusPolicy
        self.permissions = permissions
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
            animation: try container.decodeIfPresent(Animation.self, forKey: .animation) ?? Animation(),
            gestures: try container.decodeIfPresent(Gestures.self, forKey: .gestures) ?? Gestures(),
            hooks: try container.decodeIfPresent(Hooks.self, forKey: .hooks) ?? Hooks(),
            nativeSpace: try container.decodeIfPresent(NativeSpace.self, forKey: .nativeSpace) ?? NativeSpace(),
            focusPolicy: try container.decodeIfPresent(FocusPolicy.self, forKey: .focusPolicy) ?? FocusPolicy(),
            permissions: try container.decodeIfPresent(Permissions.self, forKey: .permissions) ?? Permissions()
        )
    }

    // swiftlint:disable:next cyclomatic_complexity
    private init(version: DSLVersion, sections: [ConfigSection]) {
        var keybinds = Keybinds(); var rules = Rules(); var workspaces = Workspaces()
        var engines = Engines(); var cooperativeApps = CooperativeApps(); var safeZones = SafeZones()
        var animation = Animation(); var gestures = Gestures(); var hooks = Hooks()
        var nativeSpace = NativeSpace(); var focusPolicy = FocusPolicy(); var permissions = Permissions()

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
            case let .animation(value):
                animation = value
            case let .gestures(value):
                gestures = value
            case let .hooks(value):
                hooks = value
            case let .nativeSpace(value):
                nativeSpace = value
            case let .focusPolicy(value):
                focusPolicy = value
            case let .permissions(value):
                permissions = value
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
            animation: animation,
            gestures: gestures,
            hooks: hooks,
            nativeSpace: nativeSpace,
            focusPolicy: focusPolicy,
            permissions: permissions
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

    public static func buildExpression(_ expression: Animation) -> [ConfigSection] {
        [.animation(expression)]
    }

    public static func buildExpression(_ expression: Gestures) -> [ConfigSection] {
        [.gestures(expression)]
    }

    public static func buildExpression(_ expression: Hooks) -> [ConfigSection] {
        [.hooks(expression)]
    }

    public static func buildExpression(_ expression: NativeSpace) -> [ConfigSection] {
        [.nativeSpace(expression)]
    }

    public static func buildExpression(_ expression: Permissions) -> [ConfigSection] {
        [.permissions(expression)]
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
    case animation(Animation)
    case gestures(Gestures)
    case hooks(Hooks)
    case nativeSpace(NativeSpace)
    case focusPolicy(FocusPolicy)
    case permissions(Permissions)
}
