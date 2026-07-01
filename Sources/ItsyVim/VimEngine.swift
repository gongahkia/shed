import Foundation

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
}

public enum Mode: Sendable, Equatable {
	case normal
	case insert
	case visual(VisualMode)
	case operatorPending
	case command
}

public enum VisualMode: Sendable, Equatable {
	case character
	case line
	case block
}

public enum VimOperator: Sendable, Equatable {
	case delete
	case change
	case yank
}

public struct VimRegister: Sendable, Equatable {
	public var name: Character

	public init(_ name: Character = "\"") {
		self.name = name
	}
}

public struct Position: Sendable, Equatable {
	public var offset: Int

	public init(offset: Int) {
		self.offset = offset
	}
}

public enum SearchDirection: Sendable, Equatable {
	case forward
	case backward
}

public struct SearchQuery: Sendable, Equatable {
	public var text: String
	public var direction: SearchDirection

	public init(text: String, direction: SearchDirection) {
		self.text = text
		self.direction = direction
	}
}

public enum Motion: Sendable, Equatable {
	case charForward
	case charBackward
	case lineDown
	case lineUp
	case wordForward
	case wordBackward
	case wordEnd
	case bigWordForward
	case bigWordBackward
	case bigWordEnd
	case lineStart
	case lineEnd
	case bufferStart
	case bufferEnd
	case paragraphForward
	case paragraphBackward
	case pageDown
	case pageUp
}

public protocol BufferQuery {
	var length: Int { get }
	func line(forOffset offset: Int) -> Int
	func substring(_ range: Range<Int>) -> String
	func graphemeBoundary(after offset: Int) -> Int
}

public enum VimAction: Sendable, Equatable {
	case move(Motion)
	case delete(Range<Int>)
	case insert(String, at: Int)
	case yank(Range<Int>, register: Character)
	case paste(after: Bool, register: Character)
	case setMode(Mode)
	case beginMacroRecord(Character)
	case endMacroRecord
	case playMacro(Character)
	case setOperator(VimOperator)
	case setRegister(Character)
	case setMark(Character, Position)
	case jumpToMark(Character)
	case search(SearchQuery)
	case repeatSearch(reverse: Bool)
	case command(String)
}

public struct VimEngine: Sendable {
	public private(set) var mode: Mode
	public private(set) var pendingOperator: VimOperator?
	public private(set) var pendingCount: Int?
	public private(set) var register: VimRegister
	public private(set) var marks: [Character: Position]
	public private(set) var macros: [Character: [Key]]
	public private(set) var macroRecording: Character?
	public private(set) var lastSearch: SearchQuery?
	private var pendingChord: [Key]
	private var awaitingRegister: Bool
	private var awaitingMarkSet: Bool
	private var awaitingMarkJump: Bool
	private var awaitingMacroRecord: Bool
	private var awaitingMacroReplay: Bool

	public init(
		mode: Mode = .normal,
		pendingOperator: VimOperator? = nil,
		pendingCount: Int? = nil,
		register: VimRegister = VimRegister(),
		marks: [Character: Position] = [:],
		macros: [Character: [Key]] = [:],
		macroRecording: Character? = nil,
		lastSearch: SearchQuery? = nil
	) {
		self.mode = mode
		self.pendingOperator = pendingOperator
		self.pendingCount = pendingCount
		self.register = register
		self.marks = marks
		self.macros = macros
		self.macroRecording = macroRecording
		self.lastSearch = lastSearch
		pendingChord = []
		awaitingRegister = false
		awaitingMarkSet = false
		awaitingMarkJump = false
		awaitingMacroRecord = false
		awaitingMacroReplay = false
	}

	public mutating func handle(_ key: Key, buffer: BufferQuery) -> [VimAction] {
		if key.value == "escape" {
			return cancelPendingState()
		}
		if let action = consumeRegister(key) {
			return [action]
		}
		if let actions = consumeMark(key, buffer: buffer) {
			return actions
		}
		if let actions = consumeMacroRegister(key) {
			return actions
		}
		if consumeCountPrefix(key) {
			return []
		}
		switch mode {
		case .insert:
			return []
		case .normal:
			return handleNormal(key, buffer: buffer)
		case .operatorPending:
			return handleOperatorPending(key)
		case .visual:
			return handleVisual(key)
		case .command:
			return handleCommand(key)
		}
	}

	private mutating func handleNormal(_ key: Key, buffer: BufferQuery) -> [VimAction] {
		if let motion = motion(for: key) {
			return repeated(.move(motion))
		}
		if pendingChord == [Key("g")], key.modifiers.isEmpty, key.value == "g" {
			pendingChord = []
			return repeated(.move(.bufferStart))
		}
		if key.modifiers.isEmpty, key.value == "g" {
			pendingChord = [key]
			return []
		}
		pendingChord = []
		if key.modifiers.isEmpty, let op = vimOperator(for: key.value) {
			pendingOperator = op
			mode = .operatorPending
			return [.setOperator(op), .setMode(.operatorPending)]
		}
		switch key {
		case Key("i"):
			mode = .insert
			return [.setMode(.insert)]
		case Key("a"):
			mode = .insert
			return [.move(.charForward), .setMode(.insert)]
		case Key("v"):
			mode = .visual(.character)
			return [.setMode(mode)]
		case Key("v", modifiers: .shift):
			mode = .visual(.line)
			return [.setMode(mode)]
		case Key("v", modifiers: .control):
			mode = .visual(.block)
			return [.setMode(mode)]
		case Key("\""):
			awaitingRegister = true
			return []
		case Key("m"):
			awaitingMarkSet = true
			return []
		case Key("'"):
			awaitingMarkJump = true
			return []
		case Key("q"):
			return toggleMacroRecording()
		case Key("@"):
			awaitingMacroReplay = true
			return []
		case Key("p"):
			return [.paste(after: true, register: register.name)]
		case Key("p", modifiers: .shift):
			return [.paste(after: false, register: register.name)]
		case Key("/"):
			mode = .command
			lastSearch = SearchQuery(text: "", direction: .forward)
			return [.setMode(.command), .search(lastSearch!)]
		case Key("/", modifiers: .shift):
			mode = .command
			lastSearch = SearchQuery(text: "", direction: .backward)
			return [.setMode(.command), .search(lastSearch!)]
		case Key("n"):
			return [.repeatSearch(reverse: false)]
		case Key("n", modifiers: .shift):
			return [.repeatSearch(reverse: true)]
		case Key(";"):
			mode = .command
			return [.setMode(.command)]
		default:
			return []
		}
	}

	private mutating func handleOperatorPending(_ key: Key) -> [VimAction] {
		guard let op = pendingOperator else {
			mode = .normal
			return [.setMode(.normal)]
		}
		defer {
			pendingOperator = nil
			pendingCount = nil
			mode = .normal
		}
		if key.modifiers.isEmpty, vimOperator(for: key.value) == op {
			return [.command("operator.\(op.commandName).line"), .setMode(.normal)]
		}
		guard let motion = motion(for: key) else {
			return [.setMode(.normal)]
		}
		return [.command("operator.\(op.commandName).\(motion.commandName)"), .setMode(.normal)]
	}

	private mutating func handleVisual(_ key: Key) -> [VimAction] {
		if let motion = motion(for: key) {
			return repeated(.move(motion))
		}
		if key.modifiers.isEmpty, let op = vimOperator(for: key.value) {
			mode = op == .change ? .insert : .normal
			return [.command("visual.\(op.commandName)"), .setMode(mode)]
		}
		return []
	}

	private mutating func handleCommand(_ key: Key) -> [VimAction] {
		if key.value == "return" {
			mode = .normal
			return [.setMode(.normal)]
		}
		return []
	}

	private mutating func cancelPendingState() -> [VimAction] {
		pendingOperator = nil
		pendingCount = nil
		pendingChord = []
		awaitingRegister = false
		awaitingMarkSet = false
		awaitingMarkJump = false
		awaitingMacroRecord = false
		awaitingMacroReplay = false
		mode = .normal
		return [.setMode(.normal)]
	}

	private mutating func consumeCountPrefix(_ key: Key) -> Bool {
		guard (mode == .normal || mode == .operatorPending), pendingChord.isEmpty, key.modifiers.isEmpty, key.value.count == 1, let digit = Int(key.value) else {
			return false
		}
		if digit == 0, pendingCount == nil {
			return false
		}
		pendingCount = min((pendingCount ?? 0) * 10 + digit, 9_999)
		return true
	}

	private mutating func consumeRegister(_ key: Key) -> VimAction? {
		guard awaitingRegister else {
			return nil
		}
		awaitingRegister = false
		guard let name = registerName(for: key) else {
			return nil
		}
		register = VimRegister(name)
		return .setRegister(name)
	}

	private mutating func consumeMark(_ key: Key, buffer: BufferQuery) -> [VimAction]? {
		if awaitingMarkSet {
			awaitingMarkSet = false
			guard let name = markName(for: key) else {
				return []
			}
			let position = Position(offset: min(buffer.length, 0))
			marks[name] = position
			return [.setMark(name, position)]
		}
		if awaitingMarkJump {
			awaitingMarkJump = false
			guard let name = markName(for: key) else {
				return []
			}
			return [.jumpToMark(name)]
		}
		return nil
	}

	private mutating func consumeMacroRegister(_ key: Key) -> [VimAction]? {
		if awaitingMacroRecord {
			awaitingMacroRecord = false
			guard let name = macroName(for: key) else {
				return []
			}
			macroRecording = name
			macros[name] = []
			return [.beginMacroRecord(name)]
		}
		if awaitingMacroReplay {
			awaitingMacroReplay = false
			guard let name = macroName(for: key) else {
				return []
			}
			return [.playMacro(name)]
		}
		return nil
	}

	private mutating func toggleMacroRecording() -> [VimAction] {
		if let name = macroRecording {
			macroRecording = nil
			return [.endMacroRecord, .command("macro.\(name).saved")]
		}
		awaitingMacroRecord = true
		return []
	}

	private mutating func repeated(_ action: VimAction) -> [VimAction] {
		defer { pendingCount = nil }
		return Array(repeating: action, count: max(1, pendingCount ?? 1))
	}

	private func motion(for key: Key) -> Motion? {
		switch key {
		case Key("h"), Key("left"):
			return .charBackward
		case Key("l"), Key("right"):
			return .charForward
		case Key("j"), Key("down"):
			return .lineDown
		case Key("k"), Key("up"):
			return .lineUp
		case Key("w"):
			return .wordForward
		case Key("b"):
			return .wordBackward
		case Key("e"):
			return .wordEnd
		case Key("w", modifiers: .shift):
			return .bigWordForward
		case Key("b", modifiers: .shift):
			return .bigWordBackward
		case Key("e", modifiers: .shift):
			return .bigWordEnd
		case Key("0"):
			return .lineStart
		case Key("4", modifiers: .shift), Key("$"):
			return .lineEnd
		case Key("g", modifiers: .shift):
			return .bufferEnd
		case Key("}", modifiers: .shift):
			return .paragraphForward
		case Key("{", modifiers: .shift):
			return .paragraphBackward
		case Key("f", modifiers: .control):
			return .pageDown
		case Key("b", modifiers: .control):
			return .pageUp
		default:
			return nil
		}
	}

	private func vimOperator(for value: String) -> VimOperator? {
		switch value {
		case "d":
			return .delete
		case "c":
			return .change
		case "y":
			return .yank
		default:
			return nil
		}
	}

	private func registerName(for key: Key) -> Character? {
		if key == Key("\"") {
			return "\""
		}
		if key == Key("+") {
			return "+"
		}
		guard key.modifiers.isEmpty, key.value.count == 1, let character = key.value.first else {
			return nil
		}
		if character.isLetter || character.isNumber {
			return character
		}
		return nil
	}

	private func markName(for key: Key) -> Character? {
		guard key.modifiers.isEmpty, key.value.count == 1, let character = key.value.first, character.isLetter else {
			return nil
		}
		return character
	}

	private func macroName(for key: Key) -> Character? {
		guard key.modifiers.isEmpty, key.value.count == 1, let character = key.value.first, character.isLetter || character.isNumber else {
			return nil
		}
		return character
	}
}

private extension VimOperator {
	var commandName: String {
		switch self {
		case .delete:
			return "delete"
		case .change:
			return "change"
		case .yank:
			return "yank"
		}
	}
}

private extension Motion {
	var commandName: String {
		switch self {
		case .charForward:
			return "charForward"
		case .charBackward:
			return "charBackward"
		case .lineDown:
			return "lineDown"
		case .lineUp:
			return "lineUp"
		case .wordForward:
			return "wordForward"
		case .wordBackward:
			return "wordBackward"
		case .wordEnd:
			return "wordEnd"
		case .bigWordForward:
			return "bigWordForward"
		case .bigWordBackward:
			return "bigWordBackward"
		case .bigWordEnd:
			return "bigWordEnd"
		case .lineStart:
			return "lineStart"
		case .lineEnd:
			return "lineEnd"
		case .bufferStart:
			return "bufferStart"
		case .bufferEnd:
			return "bufferEnd"
		case .paragraphForward:
			return "paragraphForward"
		case .paragraphBackward:
			return "paragraphBackward"
		case .pageDown:
			return "pageDown"
		case .pageUp:
			return "pageUp"
		}
	}
}
