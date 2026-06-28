import AppKit

public enum Mode: Sendable, Equatable, Hashable {
	case normal
	case insert
	case visual
	case operatorPending
	case command
	case emacs
}

public struct KeyModifiers: OptionSet, Sendable, Hashable {
	public let rawValue: UInt8

	public init(rawValue: UInt8) {
		self.rawValue = rawValue
	}

	public static let command = KeyModifiers(rawValue: 1 << 0)
	public static let shift = KeyModifiers(rawValue: 1 << 1)
	public static let option = KeyModifiers(rawValue: 1 << 2)
	public static let control = KeyModifiers(rawValue: 1 << 3)
}

public struct Key: Sendable, Hashable {
	public var value: String
	public var modifiers: KeyModifiers

	public init(_ value: String, modifiers: KeyModifiers = []) {
		self.value = value
		self.modifiers = modifiers
	}

	public init?(event: NSEvent) {
		guard let value = Key.value(for: event) else {
			return nil
		}
		self.value = value
		modifiers = KeyModifiers(event.modifierFlags)
	}

	private static func value(for event: NSEvent) -> String? {
		switch event.keyCode {
		case 36:
			return "return"
		case 48:
			return "tab"
		case 51:
			return "delete"
		case 53:
			return "escape"
		case 123:
			return "left"
		case 124:
			return "right"
		case 125:
			return "down"
		case 126:
			return "up"
		default:
			break
		}
		guard let characters = event.charactersIgnoringModifiers, !characters.isEmpty else {
			return nil
		}
		return characters.lowercased()
	}
}

extension KeyModifiers {
	public init(_ flags: NSEvent.ModifierFlags) {
		var modifiers: KeyModifiers = []
		if flags.contains(.command) {
			modifiers.insert(.command)
		}
		if flags.contains(.shift) {
			modifiers.insert(.shift)
		}
		if flags.contains(.option) {
			modifiers.insert(.option)
		}
		if flags.contains(.control) {
			modifiers.insert(.control)
		}
		self = modifiers
	}
}

public struct KeyBinding: Sendable, Equatable {
	public var mode: Mode
	public var chord: [Key]
	public var commandID: String

	public init(mode: Mode, chord: [Key], commandID: String) {
		self.mode = mode
		self.chord = chord
		self.commandID = commandID
	}
}

public enum KeymapResult: Sendable, Equatable {
	case command(String)
	case partial
	case passthrough
	case consumed
}

public final class KeymapEngine {
	public var modeStack: [Mode]
	public private(set) var pendingChord: [Key] = []
	private var bindingsByMode: [Mode: [[Key]: String]] = [:]

	public init(modeStack: [Mode] = [.insert], bindings: [KeyBinding] = []) {
		self.modeStack = modeStack
		setBindings(bindings)
	}

	public var mode: Mode {
		modeStack.last ?? .insert
	}

	public func setBindings(_ bindings: [KeyBinding]) {
		bindingsByMode = [:]
		for binding in bindings where !binding.chord.isEmpty {
			bindingsByMode[binding.mode, default: [:]][binding.chord] = binding.commandID
		}
		pendingChord = []
	}

	public func setMode(_ mode: Mode) {
		modeStack = [mode]
		pendingChord = []
	}

	public func pushMode(_ mode: Mode) {
		modeStack.append(mode)
		pendingChord = []
	}

	@discardableResult
	public func popMode() -> Mode? {
		guard modeStack.count > 1 else {
			return nil
		}
		pendingChord = []
		return modeStack.popLast()
	}

	public func handle(_ event: NSEvent) -> KeymapResult {
		guard let key = Key(event: event) else {
			pendingChord = []
			return .passthrough
		}
		if key.value == "escape", !pendingChord.isEmpty {
			pendingChord = []
			return .consumed
		}
		return resolve(key)
	}

	private func resolve(_ key: Key) -> KeymapResult {
		let bindings = bindingsByMode[mode] ?? [:]
		let chord = pendingChord + [key]
		if hasPrefix(chord, in: bindings) {
			if let commandID = bindings[chord], !hasLongerMatch(chord, in: bindings) {
				pendingChord = []
				return .command(commandID)
			}
			pendingChord = chord
			return .partial
		}
		pendingChord = []
		if let commandID = bindings[[key]] {
			return .command(commandID)
		}
		return .passthrough
	}

	private func hasPrefix(_ chord: [Key], in bindings: [[Key]: String]) -> Bool {
		bindings.keys.contains { candidate in
			candidate.count >= chord.count && Array(candidate.prefix(chord.count)) == chord
		}
	}

	private func hasLongerMatch(_ chord: [Key], in bindings: [[Key]: String]) -> Bool {
		bindings.keys.contains { candidate in
			candidate.count > chord.count && Array(candidate.prefix(chord.count)) == chord
		}
	}
}
