import ItsyKeymap

enum ItsyAppKeymap {
	private static var bindings: [KeyBinding] = []
	private static var initialMode: Mode = .insert

	static func configure(profile: KeymapProfile, bindings: [KeyBinding]) {
		self.bindings = bindings
		initialMode = profile.initialMode
	}

	static func makeEngine() -> KeymapEngine {
		KeymapEngine(modeStack: [initialMode], bindings: bindings)
	}
}

private extension KeymapProfile {
	var initialMode: Mode {
		switch self {
		case .plain:
			return .insert
		case .vim:
			return .normal
		case .emacs:
			return .emacs
		}
	}
}
