public struct KeymapBindingSlot: Hashable, Sendable {
	public let mode: Mode
	public let chord: [Key]

	public init(mode: Mode, chord: [Key]) {
		self.mode = mode
		self.chord = chord
	}

	public init(_ binding: KeyBinding) {
		self.init(mode: binding.mode, chord: binding.chord)
	}
}

public struct KeymapBindingCollision: Equatable, Sendable {
	public let slots: [KeymapBindingSlot]
	public let commandIDs: [String]

	public init(slots: [KeymapBindingSlot], commandIDs: [String]) {
		self.slots = slots
		self.commandIDs = commandIDs
	}
}

public enum KeymapCommandCoverage: Equatable, Sendable {
	case bound([KeymapBindingSlot])
	case unsupported
}

public struct KeymapBindingMatrix: Sendable {
	public let profile: KeymapProfile
	public let collisions: [KeymapBindingCollision]
	private let slotsByCommandID: [String: [KeymapBindingSlot]]

	public init(profile: KeymapProfile, bindings: [KeyBinding]) {
		self.profile = profile
		var commandIDsBySlot: [KeymapBindingSlot: [String]] = [:]
		var slotsByCommandID: [String: [KeymapBindingSlot]] = [:]
		for binding in bindings {
			let slot = KeymapBindingSlot(binding)
			commandIDsBySlot[slot, default: []].append(binding.commandID)
			if !(slotsByCommandID[binding.commandID]?.contains(slot) ?? false) {
				slotsByCommandID[binding.commandID, default: []].append(slot)
			}
		}
		var collisions: [KeymapBindingCollision] = []
		for (slot, commandIDs) in commandIDsBySlot where commandIDs.count > 1 {
			collisions.append(KeymapBindingCollision(slots: [slot], commandIDs: commandIDs.sorted()))
		}
		self.collisions = collisions.sorted { lhs, rhs in
			Self.slotSignature(lhs.slots[0]) < Self.slotSignature(rhs.slots[0])
		}
		self.slotsByCommandID = slotsByCommandID
	}

	public func coverage(for commandID: String) -> KeymapCommandCoverage {
		guard let slots = slotsByCommandID[commandID], !slots.isEmpty else {
			return .unsupported
		}
		return .bound(slots)
	}

	public func coverage(for commandIDs: some Sequence<String>) -> [String: KeymapCommandCoverage] {
		Dictionary(uniqueKeysWithValues: commandIDs.map { commandID in
			(commandID, coverage(for: commandID))
		})
	}

	private static func slotSignature(_ slot: KeymapBindingSlot) -> String {
		let mode: String = switch slot.mode {
		case .global: "global"
		case .normal: "normal"
		case .insert: "insert"
		case .visual: "visual"
		case .operatorPending: "operatorPending"
		case .command: "command"
		case .emacs: "emacs"
		}
		let chord = slot.chord.map { "\($0.modifiers.rawValue):\($0.value)" }.joined(separator: " ")
		return "\(mode)|\(chord)"
	}

}
