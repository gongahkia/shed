import CoreGraphics

/// Purpose: Names system colors supported by the focus-ring overlay.
/// Parameters: Choose a semantic system color.
/// Example: `FocusRingColor.systemBlue`
/// See also: `FocusRing`, `color(_:)`.
public enum FocusRingColor: String, Codable, Equatable, Sendable {
    case systemBlue
    case systemGreen
    case systemOrange
    case systemPink
    case systemPurple
    case systemRed
    case systemYellow
    case white
}

/// Purpose: Represents one focus-ring builder setting.
/// Parameters: Use `color`, `width`, `cornerRadius`, or `reduceMotion`.
/// Example: `color(.systemBlue)`
/// See also: `FocusRing`, `FocusRingBuilder`.
public enum FocusRingSetting: Codable, Equatable, Sendable {
    case color(FocusRingColor)
    case width(CGFloat)
    case cornerRadius(CGFloat)
    case reduceMotion(ReduceMotionPolicy)
}

/// Purpose: Configures the focused-window ring overlay.
/// Parameters: Provide ring color, stroke width, corner radius, and Reduce Motion policy.
/// Example: `FocusRing { color(.systemBlue); width(2) }`
/// See also: `FocusRingBuilder`, `ConfigSection`.
public struct FocusRing: Codable, Equatable, Sendable {
    public let color: FocusRingColor
    public let width: CGFloat
    public let cornerRadius: CGFloat
    public let reduceMotion: ReduceMotionPolicy

    public init(
        color: FocusRingColor = .systemBlue,
        width: CGFloat = 2,
        cornerRadius: CGFloat = 8,
        reduceMotion: ReduceMotionPolicy = .respectSystem
    ) {
        self.color = color
        self.width = max(0, width)
        self.cornerRadius = max(0, cornerRadius)
        self.reduceMotion = reduceMotion
    }

    public init(@FocusRingBuilder _ build: () -> [FocusRingSetting]) {
        var focusRing = FocusRing()
        for setting in build() {
            switch setting {
            case let .color(color):
                focusRing = FocusRing(
                    color: color,
                    width: focusRing.width,
                    cornerRadius: focusRing.cornerRadius,
                    reduceMotion: focusRing.reduceMotion
                )
            case let .width(width):
                focusRing = FocusRing(
                    color: focusRing.color,
                    width: width,
                    cornerRadius: focusRing.cornerRadius,
                    reduceMotion: focusRing.reduceMotion
                )
            case let .cornerRadius(cornerRadius):
                focusRing = FocusRing(
                    color: focusRing.color,
                    width: focusRing.width,
                    cornerRadius: cornerRadius,
                    reduceMotion: focusRing.reduceMotion
                )
            case let .reduceMotion(reduceMotion):
                focusRing = FocusRing(
                    color: focusRing.color,
                    width: focusRing.width,
                    cornerRadius: focusRing.cornerRadius,
                    reduceMotion: reduceMotion
                )
            }
        }
        self = focusRing
    }
}

/// Purpose: Builds focus-ring settings inside `FocusRing { ... }`.
/// Parameters: Accepts focus-ring setting expressions, arrays, and conditionals.
/// Example: `FocusRing { color(.systemBlue); width(2) }`
/// See also: `FocusRing`, `FocusRingSetting`.
@resultBuilder
public enum FocusRingBuilder {
    public static func buildBlock(_ components: [FocusRingSetting]...) -> [FocusRingSetting] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [FocusRingSetting]?) -> [FocusRingSetting] {
        component ?? []
    }

    public static func buildEither(first component: [FocusRingSetting]) -> [FocusRingSetting] {
        component
    }

    public static func buildEither(second component: [FocusRingSetting]) -> [FocusRingSetting] {
        component
    }

    public static func buildArray(_ components: [[FocusRingSetting]]) -> [FocusRingSetting] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: FocusRingSetting) -> [FocusRingSetting] {
        [expression]
    }

    public static func buildExpression(_ expression: AnimationSetting) -> [FocusRingSetting] {
        guard case let .reduceMotion(policy) = expression else {
            return []
        }
        return [.reduceMotion(policy)]
    }
}

/// Purpose: Declares a focus-ring color setting.
/// Parameters: Pass a supported system color.
/// Example: `color(.systemBlue)`
/// See also: `FocusRing`, `FocusRingSetting`.
public func color(_ value: FocusRingColor) -> FocusRingSetting {
    .color(value)
}

/// Purpose: Declares focus-ring stroke width in points.
/// Parameters: Pass a non-negative width.
/// Example: `width(2)`
/// See also: `FocusRing`, `FocusRingSetting`.
public func width(_ value: CGFloat) -> FocusRingSetting {
    .width(value)
}

/// Purpose: Declares focus-ring corner radius in points.
/// Parameters: Pass a non-negative radius.
/// Example: `cornerRadius(8)`
/// See also: `FocusRing`, `FocusRingSetting`.
public func cornerRadius(_ value: CGFloat) -> FocusRingSetting {
    .cornerRadius(value)
}

public extension ConfigBuilder {
    static func buildExpression(_ expression: FocusRing) -> [ConfigSection] {
        [.focusRing(expression)]
    }
}
