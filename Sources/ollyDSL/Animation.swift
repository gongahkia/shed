import Foundation
import ollyCore

/// Purpose: Stores an animation duration in milliseconds.
/// Parameters: Provide a non-negative millisecond value directly or via `.ms`.
/// Example: `200.ms`
/// See also: `Animation`, `duration(_:)`.
public struct AnimationDuration: Codable, Equatable, Sendable {
    public let milliseconds: Double

    public init(milliseconds: Double) {
        precondition(milliseconds >= 0)
        self.milliseconds = milliseconds
    }

    public var seconds: TimeInterval {
        milliseconds / 1_000
    }
}

public extension BinaryInteger {
    var ms: AnimationDuration { // swiftlint:disable:this identifier_name
        AnimationDuration(milliseconds: Double(self))
    }
}

public extension BinaryFloatingPoint {
    var ms: AnimationDuration { // swiftlint:disable:this identifier_name
        AnimationDuration(milliseconds: Double(self))
    }
}

/// Purpose: Selects the timing curve for layout animations.
/// Parameters: Choose a named curve.
/// Example: `AnimationCurve.easeOut`
/// See also: `Animation`, `curve(_:)`.
public enum AnimationCurve: String, Codable, Equatable, Sendable {
    case linear
    case easeIn
    case easeOut
    case easeInOut
}

/// Purpose: Selects how animation respects macOS Reduce Motion.
/// Parameters: Choose system-respecting, always-on, or always-off animation behavior.
/// Example: `ReduceMotionPolicy.respectSystem`
/// See also: `Animation`, `reduceMotion(_:)`.
public enum ReduceMotionPolicy: String, Codable, Equatable, Sendable {
    case respectSystem
    case alwaysAnimate
    case neverAnimate
}

/// Purpose: Represents one animation builder setting.
/// Parameters: Use `duration`, `curve`, or `reduceMotion` builder helpers.
/// Example: `duration(200.ms)`
/// See also: `Animation`, `AnimationBuilder`.
public enum AnimationSetting: Codable, Equatable, Sendable {
    case duration(AnimationDuration)
    case curve(AnimationCurve)
    case reduceMotion(ReduceMotionPolicy)
}

/// Purpose: Configures global or per-engine layout animation behavior.
/// Parameters: Provide duration, timing curve, and Reduce Motion policy.
/// Example: `Animation { duration(200.ms); curve(.easeOut); reduceMotion(.respectSystem) }`
/// See also: `AnimationBuilder`, `EngineDeclaration`.
public struct Animation: Codable, Equatable, Sendable {
    public let duration: AnimationDuration
    public let curve: AnimationCurve
    public let reduceMotion: ReduceMotionPolicy

    public init(
        duration: AnimationDuration = 200.ms,
        curve: AnimationCurve = .easeOut,
        reduceMotion: ReduceMotionPolicy = .respectSystem
    ) {
        self.duration = duration
        self.curve = curve
        self.reduceMotion = reduceMotion
    }

    public init(@AnimationBuilder _ build: () -> [AnimationSetting]) {
        var animation = Animation()
        for setting in build() {
            switch setting {
            case .duration(let duration):
                animation = Animation(duration: duration, curve: animation.curve, reduceMotion: animation.reduceMotion)
            case .curve(let curve):
                animation = Animation(duration: animation.duration, curve: curve, reduceMotion: animation.reduceMotion)
            case .reduceMotion(let reduceMotion):
                animation = Animation(duration: animation.duration, curve: animation.curve, reduceMotion: reduceMotion)
            }
        }
        self = animation
    }
}

/// Purpose: Builds animation settings inside `Animation { ... }`.
/// Parameters: Accepts animation setting expressions, arrays, and conditionals.
/// Example: `Animation { duration(120.ms); curve(.linear) }`
/// See also: `Animation`, `AnimationSetting`.
@resultBuilder
public enum AnimationBuilder {
    public static func buildBlock(_ components: [AnimationSetting]...) -> [AnimationSetting] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [AnimationSetting]?) -> [AnimationSetting] {
        component ?? []
    }

    public static func buildEither(first component: [AnimationSetting]) -> [AnimationSetting] {
        component
    }

    public static func buildEither(second component: [AnimationSetting]) -> [AnimationSetting] {
        component
    }

    public static func buildArray(_ components: [[AnimationSetting]]) -> [AnimationSetting] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: AnimationSetting) -> [AnimationSetting] {
        [expression]
    }
}

/// Purpose: Declares an animation duration setting.
/// Parameters: Pass an `AnimationDuration`, commonly with `.ms`.
/// Example: `duration(200.ms)`
/// See also: `Animation`, `AnimationSetting`.
public func duration(_ value: AnimationDuration) -> AnimationSetting {
    .duration(value)
}

/// Purpose: Declares an animation curve setting.
/// Parameters: Pass an `AnimationCurve`.
/// Example: `curve(.easeOut)`
/// See also: `Animation`, `AnimationSetting`.
public func curve(_ value: AnimationCurve) -> AnimationSetting {
    .curve(value)
}

/// Purpose: Declares a Reduce Motion animation policy.
/// Parameters: Pass a `ReduceMotionPolicy`.
/// Example: `reduceMotion(.respectSystem)`
/// See also: `Animation`, `AnimationSetting`.
public func reduceMotion(_ value: ReduceMotionPolicy) -> AnimationSetting {
    .reduceMotion(value)
}

public extension EngineDeclaration {
    func animated(_ animation: Animation) -> EngineDeclaration {
        EngineDeclaration(id, config: config, animation: animation, rawHandler: rawHandler)
    }
}

public extension Config {
    func animation(for engineID: LayoutEngineID) -> Animation {
        engines.engines.first { $0.id == engineID }?.animation ?? animation
    }
}
