/// Purpose: Names supported touchpad gesture triggers.
/// Parameters: Choose the four-finger horizontal or vertical trigger.
/// Example: `GestureTrigger.fourFingerHorizontal`
/// See also: `Gestures`, `GestureBinding`.
public enum GestureTrigger: String, Codable, Equatable, Sendable {
    case fourFingerHorizontal
    case fourFingerVertical
}

/// Purpose: Names the direction reported by a gesture recognizer shim.
/// Parameters: Choose left, right, upward, or downward.
/// Example: `GestureMotion.left`
/// See also: `GestureBinding`, `GestureCommand`.
public enum GestureMotion: String, Codable, Equatable, Sendable {
    case left
    case right
    case upward
    case downward

    var isHorizontal: Bool {
        self == .left || self == .right
    }

    var isVertical: Bool {
        self == .upward || self == .downward
    }
}

/// Purpose: Declares the semantic action attached to a gesture.
/// Parameters: Choose column scrolling, tag switching, an explicit action, or no-op.
/// Example: `GestureAction.scrollColumns`
/// See also: `GestureBinding`, `GestureCommand`.
public enum GestureAction: Codable, Equatable, Sendable {
    case scrollColumns
    case switchTags
    case action(Action)
    case noop
}

/// Purpose: Represents the concrete command emitted after gesture direction is known.
/// Parameters: Store a resolved action with direction if the gesture action is directional.
/// Example: `GestureCommand.scrollColumns(.left)`
/// See also: `GestureAction`, `Gestures`.
public enum GestureCommand: Codable, Equatable, Sendable {
    case scrollColumns(Direction)
    case switchTags(Direction)
    case action(Action)
    case noop
}

/// Purpose: Maps one gesture trigger to one gesture action.
/// Parameters: Provide a trigger and semantic action.
/// Example: `GestureBinding(.fourFingerHorizontal, .scrollColumns)`
/// See also: `Gestures`, `GestureTrigger`.
public struct GestureBinding: Codable, Equatable, Sendable {
    public let trigger: GestureTrigger
    public let action: GestureAction

    public init(_ trigger: GestureTrigger, _ action: GestureAction) {
        self.trigger = trigger
        self.action = action
    }

    public func command(for motion: GestureMotion) -> GestureCommand? {
        guard trigger.matches(motion) else {
            return nil
        }
        switch action {
        case .scrollColumns:
            return .scrollColumns(motion.horizontalDirection)
        case .switchTags:
            return .switchTags(motion.verticalDirection)
        case .action(let action):
            return .action(action)
        case .noop:
            return .noop
        }
    }
}

/// Purpose: Groups gesture declarations from `Config`.
/// Parameters: Pass bindings directly or use `@GestureBuilder`.
/// Example: `Gestures { fourFingerHorizontal(.scrollColumns); fourFingerVertical(.switchTags) }`
/// See also: `GestureBinding`, `GestureBuilder`.
public struct Gestures: Codable, Equatable, Sendable {
    public let bindings: [GestureBinding]

    public init(_ bindings: [GestureBinding] = []) {
        self.bindings = bindings
    }

    public init(@GestureBuilder _ build: () -> [GestureBinding]) {
        self.bindings = build()
    }

    public func command(for trigger: GestureTrigger, motion: GestureMotion) -> GestureCommand? {
        bindings.reversed().lazy
            .filter { $0.trigger == trigger }
            .compactMap { $0.command(for: motion) }
            .first
    }
}

/// Purpose: Builds gesture bindings inside `Gestures { ... }`.
/// Parameters: Accepts gesture binding expressions, arrays, and conditionals.
/// Example: `Gestures { fourFingerHorizontal(.scrollColumns) }`
/// See also: `Gestures`, `GestureBinding`.
@resultBuilder
public enum GestureBuilder {
    public static func buildBlock(_ components: [GestureBinding]...) -> [GestureBinding] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [GestureBinding]?) -> [GestureBinding] {
        component ?? []
    }

    public static func buildEither(first component: [GestureBinding]) -> [GestureBinding] {
        component
    }

    public static func buildEither(second component: [GestureBinding]) -> [GestureBinding] {
        component
    }

    public static func buildArray(_ components: [[GestureBinding]]) -> [GestureBinding] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: GestureBinding) -> [GestureBinding] {
        [expression]
    }
}

/// Purpose: Declares an action for four-finger horizontal gestures.
/// Parameters: Pass the semantic gesture action.
/// Example: `fourFingerHorizontal(.scrollColumns)`
/// See also: `GestureBinding`, `Gestures`.
public func fourFingerHorizontal(_ action: GestureAction) -> GestureBinding {
    GestureBinding(.fourFingerHorizontal, action)
}

/// Purpose: Declares an action for four-finger vertical gestures.
/// Parameters: Pass the semantic gesture action.
/// Example: `fourFingerVertical(.switchTags)`
/// See also: `GestureBinding`, `Gestures`.
public func fourFingerVertical(_ action: GestureAction) -> GestureBinding {
    GestureBinding(.fourFingerVertical, action)
}

private extension GestureTrigger {
    func matches(_ motion: GestureMotion) -> Bool {
        switch self {
        case .fourFingerHorizontal:
            return motion.isHorizontal
        case .fourFingerVertical:
            return motion.isVertical
        }
    }
}

private extension GestureMotion {
    var horizontalDirection: Direction {
        self == .left ? .left : .right
    }

    var verticalDirection: Direction {
        self == .upward ? .previous : .next
    }
}
