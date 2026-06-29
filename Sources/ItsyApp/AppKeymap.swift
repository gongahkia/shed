import ItsyKeymap

final class ItsyAppKeymap {
	static let shared = ItsyAppKeymap()

	private var bindings: [KeyBinding] = []
	private var initialMode: Mode = .insert

	private init() {}

	func configure(profile: KeymapProfile, bindings: [KeyBinding]) {
		self.bindings = bindings
		initialMode = profile.initialMode
	}

	func makeEngine() -> KeymapEngine {
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
