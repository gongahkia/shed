import CoreGraphics
import ollyKit

/// Purpose: Declares one user-reserved rectangle that tiled windows must avoid.
/// Parameters: Pass the reserved `CGRect` and target display ID.
/// Example: `SafeZoneReservation(rect: CGRect(x: 0, y: 0, width: 100, height: 40), displayID: 1)`
/// See also: `reserve(rect:on:)`, `SafeZones`.
public struct SafeZoneReservation: Codable, Equatable, Sendable {
    public let rect: CGRect
    public let displayID: DisplayID

    public init(rect: CGRect, displayID: DisplayID) {
        self.rect = rect.standardized
        self.displayID = displayID
    }
}

/// Purpose: Declares one named snap preview rectangle on a display.
/// Parameters: Pass a stable name, rectangle, and target display ID.
/// Example: `CustomSnapZone(name: "leftQuarter", rect: rect, displayID: 1)`
/// See also: `customZone(name:rect:on:)`, `SafeZones`.
public struct CustomSnapZone: Codable, Equatable, Sendable {
    public let name: String
    public let rect: CGRect
    public let displayID: DisplayID

    public init(name: String, rect: CGRect, displayID: DisplayID) {
        self.name = name
        self.rect = rect.standardized
        self.displayID = displayID
    }
}

/// Purpose: Represents one safe-zone DSL declaration before it is folded into `SafeZones`.
/// Parameters: Use `.notchPadding`, `.reserve`, or `.customZone`.
/// Example: `SafeZoneDeclaration.customZone(CustomSnapZone(name: "leftQuarter", rect: rect, displayID: 1))`
/// See also: `SafeZones`, `SafeZoneBuilder`.
public enum SafeZoneDeclaration: Codable, Equatable, Sendable {
    case notchPadding(CGFloat)
    case reserve(SafeZoneReservation)
    case customZone(CustomSnapZone)
}

/// Purpose: Configures display regions excluded from tiled placements and named snap preview zones.
/// Parameters: Provide notch padding, reserve rectangles, custom zones, or use `@SafeZoneBuilder`.
/// Example: `SafeZones { notchPadding(16); customZone(name: "leftQuarter", rect: rect, on: displayID) }`
/// See also: `SafeZoneReservation`, `SafeZoneCalculator`.
public struct SafeZones: Codable, Equatable, Sendable {
    public let notchPadding: CGFloat
    public let reserves: [SafeZoneReservation]
    public let customZones: [CustomSnapZone]

    public init(
        notchPadding: CGFloat = SafeZoneCalculator.defaultNotchPadding,
        reserves: [SafeZoneReservation] = [],
        customZones: [CustomSnapZone] = []
    ) {
        self.notchPadding = max(0, notchPadding)
        self.reserves = reserves
        self.customZones = customZones
    }

    public init(@SafeZoneBuilder _ build: () -> [SafeZoneDeclaration]) {
        var notchPadding = SafeZoneCalculator.defaultNotchPadding
        var reserves: [SafeZoneReservation] = []
        var customZones: [CustomSnapZone] = []
        for declaration in build() {
            switch declaration {
            case let .notchPadding(value):
                notchPadding = max(0, value)
            case let .reserve(value):
                reserves.append(value)
            case let .customZone(value):
                customZones.append(value)
            }
        }
        self.init(notchPadding: notchPadding, reserves: reserves, customZones: customZones)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            notchPadding: try container.decodeIfPresent(CGFloat.self, forKey: .notchPadding)
                ?? SafeZoneCalculator.defaultNotchPadding,
            reserves: try container.decodeIfPresent([SafeZoneReservation].self, forKey: .reserves) ?? [],
            customZones: try container.decodeIfPresent([CustomSnapZone].self, forKey: .customZones) ?? []
        )
    }

    public func calculator(dynamicReserves: [SafeZoneReserve] = []) -> SafeZoneCalculator {
        SafeZoneCalculator(
            notchPadding: notchPadding,
            userReserves: reserves.map {
                SafeZoneReserve(displayID: $0.displayID, kind: .user, rect: $0.rect)
            } + dynamicReserves
        )
    }

    private enum CodingKeys: String, CodingKey {
        case notchPadding
        case reserves
        case customZones
    }
}

/// Purpose: Declares extra padding around the detected display notch safe area.
/// Parameters: Pass a non-negative padding value in points.
/// Example: `SafeZones { notchPadding(24) }`
/// See also: `SafeZones`, `reserve(rect:on:)`.
public func notchPadding(_ value: CGFloat) -> SafeZoneDeclaration {
    .notchPadding(value)
}

/// Purpose: Declares a custom no-tile rectangle on a display.
/// Parameters: Pass the rectangle and display ID to reserve.
/// Example: `SafeZones { reserve(rect: CGRect(x: 0, y: 0, width: 200, height: 40), on: 1) }`
/// See also: `SafeZones`, `notchPadding(_:)`.
public func reserve(rect: CGRect, on displayID: DisplayID) -> SafeZoneDeclaration {
    .reserve(SafeZoneReservation(rect: rect, displayID: displayID))
}

/// Purpose: Declares a named snap preview zone on a display.
/// Parameters: Pass the name, rectangle, and display ID for the custom zone.
/// Example: `SafeZones { customZone(name: "leftQuarter", rect: CGRect(x: 0, y: 0, width: 200, height: 800), on: 1) }`
/// See also: `SafeZones`, `CustomSnapZone`.
public func customZone(name: String, rect: CGRect, on displayID: DisplayID) -> SafeZoneDeclaration {
    .customZone(CustomSnapZone(name: name, rect: rect, displayID: displayID))
}

/// Purpose: Builds safe-zone declarations inside `SafeZones { ... }`.
/// Parameters: Accepts safe-zone declarations, arrays, and conditionals.
/// Example: `SafeZones { notchPadding(12) }`
/// See also: `SafeZones`, `SafeZoneDeclaration`.
@resultBuilder
public enum SafeZoneBuilder {
    public static func buildBlock(_ components: [SafeZoneDeclaration]...) -> [SafeZoneDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [SafeZoneDeclaration]?) -> [SafeZoneDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [SafeZoneDeclaration]) -> [SafeZoneDeclaration] {
        component
    }

    public static func buildEither(second component: [SafeZoneDeclaration]) -> [SafeZoneDeclaration] {
        component
    }

    public static func buildArray(_ components: [[SafeZoneDeclaration]]) -> [SafeZoneDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: SafeZoneDeclaration) -> [SafeZoneDeclaration] {
        [expression]
    }
}
