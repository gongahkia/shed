import CoreGraphics
import ollyKit

public struct SafeZoneReservation: Codable, Equatable, Sendable {
    public let rect: CGRect
    public let displayID: DisplayID

    public init(rect: CGRect, displayID: DisplayID) {
        self.rect = rect.standardized
        self.displayID = displayID
    }
}

public enum SafeZoneDeclaration: Codable, Equatable, Sendable {
    case notchPadding(CGFloat)
    case reserve(SafeZoneReservation)
}

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

public func notchPadding(_ value: CGFloat) -> SafeZoneDeclaration {
    .notchPadding(value)
}

public func reserve(rect: CGRect, on displayID: DisplayID) -> SafeZoneDeclaration {
    .reserve(SafeZoneReservation(rect: rect, displayID: displayID))
}

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
