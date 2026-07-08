@MainActor enum ItsyAppCommandBridge {
	static var runCommand: ((String) -> Bool)?

	static func requestRunCommand(_ commandID: String) -> Bool {
		runCommand?(commandID) ?? false
	}
}
