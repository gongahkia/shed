import Foundation

public enum DSLVersion: String, Codable, Equatable, Sendable {
    case v1 // swiftlint:disable:this identifier_name
}

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

public enum ConfigSection: Codable, Equatable, Sendable {
    case keybinds(Keybinds)
    case rules(Rules)
    case workspaces(Workspaces)
    case engines(Engines)
    case cooperativeApps(CooperativeApps)
    case safeZones(SafeZones)
    case hooks(Hooks)
}

public struct Hooks: Codable, Equatable, Sendable {
    public init(_ build: () -> Void = {}) {
        build()
    }
}
