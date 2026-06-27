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

/// Purpose: Represents one safe-zone DSL declaration before it is folded into `SafeZones`.
/// Parameters: Use `.notchPadding` or `.reserve`.
/// Example: `SafeZoneDeclaration.notchPadding(24)`
/// See also: `SafeZones`, `SafeZoneBuilder`.
public enum SafeZoneDeclaration: Codable, Equatable, Sendable {
    case notchPadding(CGFloat)
    case reserve(SafeZoneReservation)
}

/// Purpose: Configures display regions excluded from tiled placements.
/// Parameters: Provide notch padding and user reserve rectangles or use `@SafeZoneBuilder`.
/// Example: `SafeZones { notchPadding(16); reserve(rect: rect, on: displayID) }`
/// See also: `SafeZoneReservation`, `SafeZoneCalculator`.
public struct SafeZones: Codable, Equatable, Sendable {
    public let notchPadding: CGFloat
    public let reserves: [SafeZoneReservation]

    public init(
        notchPadding: CGFloat = SafeZoneCalculator.defaultNotchPadding,
        reserves: [SafeZoneReservation] = []
    ) {
        self.notchPadding = max(0, notchPadding)
        self.reserves = reserves
    }

    public init(@SafeZoneBuilder _ build: () -> [SafeZoneDeclaration]) {
        var notchPadding = SafeZoneCalculator.defaultNotchPadding
        var reserves: [SafeZoneReservation] = []
        for declaration in build() {
            switch declaration {
            case let .notchPadding(value):
                notchPadding = max(0, value)
            case let .reserve(value):
                reserves.append(value)
            }
        }
        self.init(notchPadding: notchPadding, reserves: reserves)
    }

    public func calculator() -> SafeZoneCalculator {
        SafeZoneCalculator(
            notchPadding: notchPadding,
            userReserves: reserves.map {
                SafeZoneReserve(displayID: $0.displayID, kind: .user, rect: $0.rect)
            }
        )
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
