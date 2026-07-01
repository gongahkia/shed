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

public enum CharacterMotion: Sendable, Equatable {
	case findForward
	case findBackward
	case tillForward
	case tillBackward

	public var reversed: CharacterMotion {
		switch self {
		case .findForward:
			return .findBackward
		case .findBackward:
			return .findForward
		case .tillForward:
			return .tillBackward
		case .tillBackward:
			return .tillForward
		}
	}
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

public struct VimSelection: Sendable, Equatable {
	public var anchor: Int
	public var head: Int

	public init(anchor: Int, head: Int) {
		self.anchor = anchor
		self.head = head
	}
}

public struct SelectionSnapshot: Sendable, Equatable {
	public var primary: VimSelection
	public var secondaries: [VimSelection]

	public init(primary: VimSelection, secondaries: [VimSelection] = []) {
		self.primary = primary
		self.secondaries = secondaries
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

public struct RecordedKey: Sendable, Equatable {
	public var characters: String
	public var charactersIgnoringModifiers: String
	public var keyCode: UInt16
	public var modifierFlags: KeyModifiers

	public init(
		characters: String,
		charactersIgnoringModifiers: String,
		keyCode: UInt16,
		modifierFlags: KeyModifiers = []
	) {
		self.characters = characters
		self.charactersIgnoringModifiers = charactersIgnoringModifiers
		self.keyCode = keyCode
		self.modifierFlags = modifierFlags
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

public enum VimCommandAction: Sendable, Equatable {
	case motion(String)
	case visualMotion(String)
	case beginCharacterMotion(CharacterMotion)
	case repeatCharacterMotion(reversed: Bool)
	case undo
	case redo
	case addNextSelection
	case beginOperator(VimOperator)
	case applySelectionOperator(VimOperator)
	case applyPendingOperatorMotion(String)
	case applyPendingOperatorTextObject(String)
	case lineOperator(VimOperator)
	case jumpBack
	case macroRecordPrefix
	case macroReplayPrefix
	case paste(after: Bool)
	case exStart
	case beginVisualMode(VisualMode)
	case search(String, recordsJump: Bool)
	case save
	case close
	case normalMode
	case insertMode
	case emacsMode
	case handled
}

public struct VimEngine: Sendable {
	private static let motionCommandIDs: [String: String] = [
		"editor.moveLeft": "editor.moveLeft",
		"editor.moveRight": "editor.moveRight",
		"editor.moveDown": "editor.moveDown",
		"editor.moveUp": "editor.moveUp",
		"editor.moveWordForward": "editor.moveWordForward",
		"editor.moveWordBackward": "editor.moveWordBackward",
		"editor.moveWordEnd": "editor.moveWordEnd",
		"editor.moveBigWordForward": "editor.moveBigWordForward",
		"editor.moveBigWordBackward": "editor.moveBigWordBackward",
		"editor.moveBigWordEnd": "editor.moveBigWordEnd",
		"editor.moveLineStart": "editor.moveLineStart",
		"editor.moveLineEnd": "editor.moveLineEnd",
		"editor.moveBufferStart": "editor.moveBufferStart",
		"editor.moveBufferEnd": "editor.moveBufferEnd",
		"editor.moveParagraphForward": "editor.moveParagraphForward",
		"editor.moveParagraphBackward": "editor.moveParagraphBackward",
	]
	private static let textObjectCommandIDs: [String: String] = [
		"vim.textObject.innerWord": "vim.textObject.innerWord",
		"vim.textObject.aroundWord": "vim.textObject.aroundWord",
		"vim.textObject.innerDoubleQuote": "vim.textObject.innerDoubleQuote",
		"vim.textObject.aroundDoubleQuote": "vim.textObject.aroundDoubleQuote",
		"vim.textObject.innerSingleQuote": "vim.textObject.innerSingleQuote",
		"vim.textObject.aroundSingleQuote": "vim.textObject.aroundSingleQuote",
		"vim.textObject.innerParen": "vim.textObject.innerParen",
		"vim.textObject.aroundParen": "vim.textObject.aroundParen",
		"vim.textObject.innerBracket": "vim.textObject.innerBracket",
		"vim.textObject.aroundBracket": "vim.textObject.aroundBracket",
		"vim.textObject.innerBrace": "vim.textObject.innerBrace",
		"vim.textObject.aroundBrace": "vim.textObject.aroundBrace",
		"vim.textObject.innerParagraph": "vim.textObject.innerParagraph",
		"vim.textObject.aroundParagraph": "vim.textObject.aroundParagraph",
	]
	public var mode: Mode
	public var pendingCharacterMotion: CharacterMotion?
	public var lastCharacterMotion: (motion: CharacterMotion, value: Character)?
	public var pendingOperator: VimOperator?
	public var pendingOperatorCount: Int
	public var pendingCount: Int?
	public var register: VimRegister
	public var pendingRegister: String?
	public var registers: [String: String]
	public var visualAnchor: Int?
	public var visualHead: Int?
	public var visualMode: VisualMode?
	public var jumpBackSelection: SelectionSnapshot?
	public var marks: [Character: Position]
	public var macros: [Character: [Key]]
	public var macroRegisters: [String: [RecordedKey]]
	public var macroRecording: Character?
	public var recordingMacroRegister: String?
	public var currentMacroEvents: [RecordedKey]
	public var lastSearch: SearchQuery?
	public var awaitingRegister: Bool
	public var awaitingMacroRecordRegister: Bool
	public var awaitingMacroReplayRegister: Bool
	public var replayingMacro: Bool
	public var pendingExCommand: String?
	private var pendingChord: [Key]
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
		self.pendingCharacterMotion = nil
		self.lastCharacterMotion = nil
		self.pendingOperator = pendingOperator
		self.pendingOperatorCount = 1
		self.pendingCount = pendingCount
		self.register = register
		self.pendingRegister = nil
		self.registers = [:]
		self.visualAnchor = nil
		self.visualHead = nil
		self.visualMode = nil
		self.jumpBackSelection = nil
		self.marks = marks
		self.macros = macros
		self.macroRegisters = [:]
		self.macroRecording = macroRecording
		self.recordingMacroRegister = nil
		self.currentMacroEvents = []
		self.lastSearch = lastSearch
		self.awaitingRegister = false
		self.awaitingMacroRecordRegister = false
		self.awaitingMacroReplayRegister = false
		self.replayingMacro = false
		self.pendingExCommand = nil
		pendingChord = []
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

	public mutating func handle(commandID: String, count: Int, hasSelection: Bool) -> VimCommandAction? {
		if let motion = Self.motionCommandIDs[commandID] {
			if visualMode != nil {
				return .visualMotion(motion)
			}
			if pendingOperator != nil {
				return .applyPendingOperatorMotion(motion)
			}
			return .motion(motion)
		}
		if let textObject = Self.textObjectCommandIDs[commandID], pendingOperator != nil {
			return .applyPendingOperatorTextObject(textObject)
		}
		switch commandID {
		case "editor.findCharForward":
			pendingCharacterMotion = .findForward
			return .handled
		case "editor.findCharBackward":
			pendingCharacterMotion = .findBackward
			return .handled
		case "editor.tillCharForward":
			pendingCharacterMotion = .tillForward
			return .handled
		case "editor.tillCharBackward":
			pendingCharacterMotion = .tillBackward
			return .handled
		case "editor.repeatCharFind":
			return .repeatCharacterMotion(reversed: false)
		case "editor.repeatCharFindReverse":
			return .repeatCharacterMotion(reversed: true)
		case "edit.undo":
			return .undo
		case "edit.redo":
			return .redo
		case "editor.addNextSelection":
			return .addNextSelection
		case "vim.operator.delete":
			return beginOperatorAction(.delete, count: count, hasSelection: hasSelection)
		case "vim.operator.change":
			return beginOperatorAction(.change, count: count, hasSelection: hasSelection)
		case "vim.operator.yank":
			return beginOperatorAction(.yank, count: count, hasSelection: hasSelection)
		case "vim.registerPrefix":
			awaitingRegister = true
			return .handled
		case "vim.jumpBack":
			return .jumpBack
		case "vim.macro.recordPrefix":
			return .macroRecordPrefix
		case "vim.macro.replayPrefix":
			awaitingMacroReplayRegister = true
			return .handled
		case "vim.pasteAfter":
			return .paste(after: true)
		case "vim.pasteBefore":
			return .paste(after: false)
		case "vim.ex.start":
			pendingExCommand = ""
			return .exStart
		case "vim.visual.char":
			return .beginVisualMode(.character)
		case "vim.visual.line":
			return .beginVisualMode(.line)
		case "vim.visual.block":
			return .beginVisualMode(.block)
		case "vim.operator.line.delete":
			return .lineOperator(.delete)
		case "vim.operator.line.change":
			return .lineOperator(.change)
		case "vim.operator.line.yank":
			return .lineOperator(.yank)
		case "edit.findNext", "edit.findPrevious", "vim.searchForward", "vim.searchBackward":
			return .search(commandID, recordsJump: mode == .normal)
		case "file.save":
			return .save
		case "file.close":
			return .close
		case "mode.normal":
			return .normalMode
		case "mode.insert":
			return .insertMode
		case "mode.emacs":
			return .emacsMode
		default:
			return nil
		}
	}

	private mutating func beginOperatorAction(_ op: VimOperator, count: Int, hasSelection: Bool) -> VimCommandAction {
		if hasSelection {
			return .applySelectionOperator(op)
		}
		pendingOperator = op
		pendingOperatorCount = max(1, count)
		return .beginOperator(op)
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
