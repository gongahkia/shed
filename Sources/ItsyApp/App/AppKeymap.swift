// @file app-level keymap defaults and profile mapping.
import ItsyKeymap

enum ItsyAppKeymap {
	private static var bindings: [KeyBinding] = []
	private static var baseBindings: [KeyBinding] = []
	private static var extensionBindings: [KeyBinding] = []
	private static var initialMode: Mode = .insert

	static func configure(profile: KeymapProfile, bindings: [KeyBinding]) {
		baseBindings = bindings
		extensionBindings = []
		self.bindings = baseBindings
		initialMode = profile.initialMode
	}

	static var currentInitialMode: Mode {
		initialMode
	}

	static func setExtensionBindings(_ bindings: [KeyBinding]) {
		extensionBindings = bindings
		self.bindings = baseBindings + extensionBindings
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
