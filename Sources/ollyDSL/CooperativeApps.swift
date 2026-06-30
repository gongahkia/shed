/// Purpose: Names how olly should treat a cooperative app's windows at runtime.
/// Parameters: Choose float-only, hide-on-switch, reserve-space, or dock-aware behavior.
/// Example: `CooperativeBehavior.floatAndReserveSpace`
/// See also: `CooperativeApps`, `CooperativeApp`.
public enum CooperativeBehavior: String, Codable, Equatable, Sendable {
    case floatOnly
    case floatAndHideOnSwitch
    case floatAndReserveSpace
    case dockAware
}

/// Purpose: Chooses whether configured cooperative apps extend or replace olly's default allowlist.
/// Parameters: Use `.extend` to add bundle IDs or `.replace` to ignore defaults.
/// Example: `CooperativeApps(mode: .replace, bundleIDs: ["com.example.Overlay"])`
/// See also: `CooperativeApps`, `CooperativeApp`.
public enum CooperativeAppsMode: String, Codable, Equatable, Sendable {
    case extend
    case replace
}

/// Purpose: Declares one app bundle ID that olly should avoid tiling by default.
/// Parameters: Pass a non-empty bundle identifier string and optional cooperative behavior.
/// Example: `CooperativeApp("com.felixkratz.SketchyBar", behavior: .floatAndReserveSpace)`
/// See also: `CooperativeApps`, `CooperativeAppBuilder`.
public struct CooperativeApp: Codable, Equatable, ExpressibleByStringLiteral, Sendable {
    public let bundleID: String
    public let behavior: CooperativeBehavior

    public init(_ bundleID: String, behavior: CooperativeBehavior = .floatOnly) {
        precondition(!bundleID.isEmpty)
        self.bundleID = bundleID
        self.behavior = behavior
    }

    public init(stringLiteral value: String) {
        self.init(value)
    }

    private enum CodingKeys: String, CodingKey {
        case bundleID
        case behavior
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try container.decode(String.self, forKey: .bundleID),
            behavior: try container.decodeIfPresent(CooperativeBehavior.self, forKey: .behavior) ?? .floatOnly
        )
    }
}

/// Purpose: Configures apps whose windows should be floated for ecosystem compatibility.
/// Parameters: Select a mode and provide bundle IDs or `CooperativeApp` entries.
/// Example: `CooperativeApps { "com.monuk7735.mew.notch" }`
/// See also: `CooperativeAppsMode`, `CooperativeApp`.
public struct CooperativeApps: Codable, Equatable, Sendable {
    public static let defaultBundleIDs = [
        "com.lowtechguys.Alcove",
        "com.akashpawar.notchnook",
        "com.tymmesyde.boring-notch",
        "com.monuk7735.mew.notch",
        "com.lukegrubb.NotchFlow",
        "com.codykerns.TopNotch",
        "com.notchmeister.Notchmeister",
        "com.brow-app.Brow",
        "com.dynamiclake.pro",
        "com.surteesstudios.Bartender",
        "com.dwarvesf.hidden",
        "com.jordanbaird.Ice",
        "com.runningwithcrayons.Alfred",
        "com.raycast.macos",
        "at.obdev.LaunchBar",
        "com.felixkratz.SketchyBar",
        "de.tracesof.Uebersicht",
        "com.felixkratz.JankyBorders",
        "com.tryklack.Klack",
        "com.klakk.macos",
        "com.obsproject.obs-studio",
        "com.araelium.screenflow6",
        "pl.maketheweb.cleanshotx",
        "org.pqrs.Karabiner-EventViewer",
        "com.koekeishiya.skhd",
        "com.hegenberg.BetterTouchTool",
        "org.hammerspoon.Hammerspoon"
    ]

    public let mode: CooperativeAppsMode
    public let apps: [CooperativeApp]

    public init(mode: CooperativeAppsMode = .extend, _ apps: [CooperativeApp] = []) {
        self.mode = mode
        self.apps = apps
    }

    public init(mode: CooperativeAppsMode = .extend, bundleIDs: [String]) {
        self.init(mode: mode, bundleIDs.map { CooperativeApp($0) })
    }

    public init(mode: CooperativeAppsMode = .extend, @CooperativeAppBuilder _ build: () -> [CooperativeApp]) {
        self.init(mode: mode, build())
    }

    public var resolvedBundleIDs: Set<String> {
        Set(resolvedApps.map(\.bundleID))
    }

    public var resolvedApps: [CooperativeApp] {
        var resolved = mode == .extend ? Self.defaultBundleIDs.map(CooperativeApp.init) : []
        for app in apps {
            if let index = resolved.firstIndex(where: { $0.bundleID == app.bundleID }) {
                resolved[index] = app
            } else {
                resolved.append(app)
            }
        }
        return resolved
    }

    public func behavior(for bundleID: String?) -> CooperativeBehavior? {
        guard let bundleID else {
            return nil
        }
        return resolvedApps.first { $0.bundleID == bundleID }?.behavior
    }

    public func contains(bundleID: String?) -> Bool {
        behavior(for: bundleID) != nil
    }
}

/// Purpose: Builds cooperative-app declarations inside `CooperativeApps { ... }`.
/// Parameters: Accepts `CooperativeApp` and string expressions plus conditionals and arrays.
/// Example: `CooperativeApps { "com.raycast.macos" }`
/// See also: `CooperativeApps`, `CooperativeApp`.
@resultBuilder
public enum CooperativeAppBuilder {
    public static func buildBlock(_ components: [CooperativeApp]...) -> [CooperativeApp] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [CooperativeApp]?) -> [CooperativeApp] {
        component ?? []
    }

    public static func buildEither(first component: [CooperativeApp]) -> [CooperativeApp] {
        component
    }

    public static func buildEither(second component: [CooperativeApp]) -> [CooperativeApp] {
        component
    }

    public static func buildArray(_ components: [[CooperativeApp]]) -> [CooperativeApp] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: CooperativeApp) -> [CooperativeApp] {
        [expression]
    }

    public static func buildExpression(_ expression: String) -> [CooperativeApp] {
        [CooperativeApp(expression)]
    }
}
