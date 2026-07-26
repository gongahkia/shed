import ollyCore
import ollyKit
import ollyLayouts

public typealias RawDSLHandler = @Sendable (RawDSLContext) -> Void

/// Purpose: Carries runtime state into raw Swift DSL closures.
/// Parameters: Provide any currently available config, window, rule, engine, tag, or event value.
/// Example: `RawDSLContext(engineID: BSPLayoutEngine.engineID)`
/// See also: `RawDSLBlock`, `Hooks`.
public struct RawDSLContext: Equatable, Sendable {
    public let config: Config?
    public let window: WindowState?
    public let ruleContext: RuleContext?
    public let engineID: LayoutEngineID?
    public let tag: Tag?
    public let event: String?

    public init(
        config: Config? = nil,
        window: WindowState? = nil,
        ruleContext: RuleContext? = nil,
        engineID: LayoutEngineID? = nil,
        tag: Tag? = nil,
        event: String? = nil
    ) {
        self.config = config
        self.window = window
        self.ruleContext = ruleContext
        self.engineID = engineID
        self.tag = tag
        self.event = event
    }
}

/// Purpose: Stores a named raw Swift closure attached to a DSL primitive.
/// Parameters: Provide a stable label and closure receiving `RawDSLContext`.
/// Example: `RawDSLBlock("trace") { context in _ = context.event }`
/// See also: `RawDSLContext`, `HookDeclaration`.
public struct RawDSLBlock<Output>: @unchecked Sendable {
    public let label: String
    private let body: @Sendable (RawDSLContext) -> Output

    public init(_ label: String = "raw", _ body: @Sendable @escaping (RawDSLContext) -> Output) {
        precondition(!label.isEmpty)
        self.label = label
        self.body = body
    }

    public func callAsFunction(_ context: RawDSLContext) -> Output {
        body(context)
    }
}

extension RawDSLBlock: Equatable {
    public static func == (lhs: RawDSLBlock<Output>, rhs: RawDSLBlock<Output>) -> Bool {
        lhs.label == rhs.label
    }
}
