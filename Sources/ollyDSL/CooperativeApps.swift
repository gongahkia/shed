/// Purpose: Chooses whether configured cooperative apps extend or replace olly's default allowlist.
/// Parameters: Use `.extend` to add bundle IDs or `.replace` to ignore defaults.
/// Example: `CooperativeApps(mode: .replace, bundleIDs: ["com.example.Overlay"])`
/// See also: `CooperativeApps`, `CooperativeApp`.
public enum CooperativeAppsMode: String, Codable, Equatable, Sendable {
    case extend
    case replace
}

/// Purpose: Declares one app bundle ID that olly should avoid tiling by default.
/// Parameters: Pass a non-empty bundle identifier string.
/// Example: `CooperativeApp("com.felixkratz.SketchyBar")`
/// See also: `CooperativeApps`, `CooperativeAppBuilder`.
public struct CooperativeApp: Codable, Equatable, ExpressibleByStringLiteral, Sendable {
    public let bundleID: String

    public init(_ bundleID: String) {
        precondition(!bundleID.isEmpty)
        self.bundleID = bundleID
    }

    public init(stringLiteral value: String) {
        self.init(value)
    }
}

/// Purpose: Configures apps whose windows should be floated for ecosystem compatibility.
/// Parameters: Select a mode and provide bundle IDs or `CooperativeApp` entries.
/// Example: `CooperativeApps { "com.example.NotchOverlay" }`
/// See also: `CooperativeAppsMode`, `CooperativeApp`.
public struct CooperativeApps: Codable, Equatable, Sendable {
    public static let defaultBundleIDs = [
        "com.lowtechguys.Alcove",
        "com.akashpawar.notchnook",
        "com.tymmesyde.boring-notch",
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
        let configured = Set(apps.map(\.bundleID))
        switch mode {
        case .extend:
            return Set(Self.defaultBundleIDs).union(configured)
        case .replace:
            return configured
        }
    }

    public func contains(bundleID: String?) -> Bool {
        guard let bundleID else {
            return false
        }
        return resolvedBundleIDs.contains(bundleID)
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
