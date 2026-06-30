import ollyCore

public struct NativeSpace: Codable, Equatable, Sendable {
    public let driftPolicy: NativeSpaceDriftPolicy

    public init(driftPolicy: NativeSpaceDriftPolicy = .followWindow) {
        self.driftPolicy = driftPolicy
    }

    public init(@NativeSpaceBuilder _ build: () -> NativeSpace) {
        self = build()
    }
}

@resultBuilder
public enum NativeSpaceBuilder {
    public static func buildBlock(_ components: NativeSpace...) -> NativeSpace {
        components.last ?? NativeSpace()
    }

    public static func buildExpression(_ expression: NativeSpace) -> NativeSpace {
        expression
    }
}

public func driftPolicy(_ policy: NativeSpaceDriftPolicy) -> NativeSpace {
    NativeSpace(driftPolicy: policy)
}
