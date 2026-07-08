@MainActor enum ItsyAppCommandBridge {
	static var runCommand: ((String) -> Bool)?
	static var commandIDs: (() -> [String])?

	static func requestRunCommand(_ commandID: String) -> Bool {
		runCommand?(commandID) ?? false
	}

	static func availableCommandIDs() -> [String] {
		commandIDs?() ?? []
	}
}
