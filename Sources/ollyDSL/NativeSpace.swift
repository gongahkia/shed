import ollyCore

/// Purpose: Configures native Mission Control Space drift handling.
/// Parameters: Use `driftPolicy(.followWindow)`, `.rehome`, or `.unmanage`.
/// Example: `NativeSpace { driftPolicy(.followWindow) }`
/// See also: `ConfigSection`, `NativeSpaceDriftPolicy`.
public struct NativeSpace: Codable, Equatable, Sendable {
    public let driftPolicy: NativeSpaceDriftPolicy

    public init(driftPolicy: NativeSpaceDriftPolicy = .followWindow) {
        self.driftPolicy = driftPolicy
    }

    public init(@NativeSpaceBuilder _ build: () -> NativeSpace) {
        self = build()
    }
}

/// Purpose: Builds native Space drift settings inside `NativeSpace { ... }`.
/// Parameters: Accepts native Space expressions, using the last declaration when repeated.
/// Example: `NativeSpace { driftPolicy(.rehome) }`
/// See also: `NativeSpace`, `driftPolicy(_:)`.
@resultBuilder
public enum NativeSpaceBuilder {
    public static func buildBlock(_ components: NativeSpace...) -> NativeSpace {
        components.last ?? NativeSpace()
    }

    public static func buildExpression(_ expression: NativeSpace) -> NativeSpace {
        expression
    }
}

/// Purpose: Declares a native Space drift handling policy.
/// Parameters: Pass a `NativeSpaceDriftPolicy`.
/// Example: `driftPolicy(.followWindow)`
/// See also: `NativeSpace`, `NativeSpaceDriftPolicy`.
public func driftPolicy(_ policy: NativeSpaceDriftPolicy) -> NativeSpace {
    NativeSpace(driftPolicy: policy)
}
