public enum VimInputRoute: Sendable, Equatable {
	case action(VimCommandAction)
	case hostCommand(String)
}

public struct VimInputRouter: Sendable {
	public private(set) var engine: VimEngine

	public init(engine: VimEngine = VimEngine()) {
		self.engine = engine
	}

	public mutating func route(commandID: String, count: Int, hasSelection: Bool) -> VimInputRoute {
		if let action = engine.handle(commandID: commandID, count: count, hasSelection: hasSelection) {
			return .action(action)
		}
		return .hostCommand(commandID)
	}
}
