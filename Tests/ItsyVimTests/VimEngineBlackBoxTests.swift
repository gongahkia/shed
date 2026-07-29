import ItsyVim
import Testing

@Test(arguments: motionKeyCases)
func vimEngineMotionKeysEmitMove(testCase: MotionKeyCase) {
	var engine = VimEngine()
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.move(testCase.motion)])
}

@Test(arguments: Array(2 ... 10))
func vimEngineCountPrefixesRepeatMotion(count: Int) {
	var engine = VimEngine()
	for digit in String(count) {
		#expect(engine.handle(Key(String(digit)), buffer: blackBoxBuffer).isEmpty)
	}
	#expect(engine.handle(Key("w"), buffer: blackBoxBuffer) == Array(repeating: .move(.wordForward), count: count))
	#expect(engine.pendingCount == nil)
}

@Test func vimEngineZeroWithoutPendingCountIsLineStartMotion() {
	var engine = VimEngine()
	#expect(engine.handle(Key("0"), buffer: blackBoxBuffer) == [.move(.lineStart)])
	#expect(engine.pendingCount == nil)
}

@Test func vimEngineDoubleGEmitsBufferStartMotion() {
	var engine = VimEngine()
	#expect(engine.handle(Key("g"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(Key("g"), buffer: blackBoxBuffer) == [.move(.bufferStart)])
}

@Test func vimEngineEscapeClearsPendingCountChordAndOperator() {
	var engine = VimEngine()
	#expect(engine.handle(Key("2"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(Key("d"), buffer: blackBoxBuffer) == [.setOperator(.delete), .setMode(.operatorPending)])
	#expect(engine.handle(Key("escape"), buffer: blackBoxBuffer) == [.setMode(.normal)])
	#expect(engine.mode == .normal)
	#expect(engine.pendingCount == nil)
	#expect(engine.pendingOperator == nil)
}

@Test(arguments: operatorMotionCases)
func vimEngineOperatorsApplyMotion(testCase: OperatorMotionCase) {
	var engine = VimEngine()
	#expect(engine.handle(testCase.operatorCase.key, buffer: blackBoxBuffer) == [.setOperator(testCase.operatorCase.operatorValue), .setMode(.operatorPending)])
	#expect(engine.handle(testCase.motion.key, buffer: blackBoxBuffer) == [
		.command("operator.\(testCase.operatorCase.commandName).\(testCase.motion.commandName)"),
		.setMode(.normal),
	])
	#expect(engine.mode == .normal)
	#expect(engine.pendingOperator == nil)
}

@Test(arguments: operatorCases)
func vimEngineRepeatedOperatorAppliesLine(testCase: OperatorCase) {
	var engine = VimEngine()
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.setOperator(testCase.operatorValue), .setMode(.operatorPending)])
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.command("operator.\(testCase.commandName).line"), .setMode(.normal)])
}

@Test(arguments: visualModeCases)
func vimEngineVisualModeKeysSetMode(testCase: VisualModeCase) {
	var engine = VimEngine()
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.setMode(.visual(testCase.mode))])
	#expect(engine.mode == .visual(testCase.mode))
}

@Test(arguments: motionKeyCases)
func vimEngineVisualModeKeepsMotionActions(testCase: MotionKeyCase) {
	var engine = VimEngine(mode: .visual(.character))
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.move(testCase.motion)])
}

@Test(arguments: operatorCases)
func vimEngineVisualOperatorsEmitVisualCommand(testCase: OperatorCase) {
	var engine = VimEngine(mode: .visual(.block))
	let expectedMode: Mode = testCase.operatorValue == .change ? .insert : .normal
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.command("visual.\(testCase.commandName)"), .setMode(expectedMode)])
	#expect(engine.mode == expectedMode)
}

@Test(arguments: registerCases)
func vimEngineRegisterPrefixAcceptsNamedRegisters(testCase: RegisterCase) {
	var engine = VimEngine()
	#expect(engine.handle(Key("\""), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.setRegister(testCase.name)])
	#expect(engine.register == VimRegister(testCase.name))
}

@Test func vimEngineInvalidRegisterClearsAwaitingRegister() {
	var engine = VimEngine()
	#expect(engine.handle(Key("\""), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(Key("?"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.awaitingRegister == false)
	#expect(engine.register == VimRegister("\""))
}

@Test(arguments: macroRegisterCases)
func vimEngineMacroRecordAndReplayRegisters(testCase: MacroRegisterCase) {
	var engine = VimEngine()
	#expect(engine.handle(Key("q"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.beginMacroRecord(testCase.name)])
	#expect(engine.macroRecording == testCase.name)
	#expect(engine.handle(Key("q"), buffer: blackBoxBuffer) == [.endMacroRecord, .command("macro.\(testCase.name).saved")])
	#expect(engine.handle(Key("@"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.playMacro(testCase.name)])
}

@Test(arguments: markCases)
func vimEngineMarksCanBeSetAndJumped(testCase: MarkCase) {
	var engine = VimEngine()
	#expect(engine.handle(Key("m"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.setMark(testCase.name, Position(offset: 0))])
	#expect(engine.marks[testCase.name] == Position(offset: 0))
	#expect(engine.handle(Key("`"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.jumpToMark(testCase.name)])
	#expect(engine.handle(Key("'"), buffer: blackBoxBuffer).isEmpty)
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == [.jumpToMarkLine(testCase.name)])
}

@Test(arguments: searchKeyCases)
func vimEngineSearchKeysEmitSearchActions(testCase: SearchKeyCase) {
	var engine = VimEngine(lastSearch: SearchQuery(text: "alpha", direction: .forward))
	#expect(engine.handle(testCase.key, buffer: blackBoxBuffer) == testCase.actions)
}

@Test func vimEngineCommandModeReturnReturnsToNormal() {
	var engine = VimEngine(mode: .command)
	#expect(engine.handle(Key("return"), buffer: blackBoxBuffer) == [.setMode(.normal)])
	#expect(engine.mode == .normal)
}

@Test(arguments: commandMotionCases)
func vimEngineCommandIDsRouteNormalMotions(testCase: CommandMotionCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == .motion(testCase.commandID))
}

@Test(arguments: commandMotionCases)
func vimEngineCommandIDsRouteVisualMotions(testCase: CommandMotionCase) {
	var engine = VimEngine()
	engine.visualMode = .character
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == .visualMotion(testCase.commandID))
}

@Test(arguments: commandMotionCases)
func vimEngineCommandIDsRoutePendingOperatorMotions(testCase: CommandMotionCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: "vim.operator.delete", count: 1, hasSelection: false) == .beginOperator(.delete))
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == .applyPendingOperatorMotion(testCase.commandID))
}

@Test(arguments: textObjectCommandIDs)
func vimEngineCommandIDsRoutePendingOperatorTextObjects(commandID: String) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: "vim.operator.delete", count: 1, hasSelection: false) == .beginOperator(.delete))
	#expect(engine.handle(commandID: commandID, count: 1, hasSelection: false) == .applyPendingOperatorTextObject(commandID))
}

@Test(arguments: commandActionCases)
func vimEngineCommandIDsEmitAdapterActions(testCase: CommandActionCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == testCase.action)
}

@Test(arguments: characterMotionCommandCases)
func vimEngineCharacterMotionCommandIDsSetPendingMotion(testCase: CharacterMotionCommandCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == .beginCharacterMotion(testCase.motion))
	#expect(engine.pendingCharacterMotion == testCase.motion)
}

@Test(arguments: operatorCases)
func vimEngineCommandIDOperatorsTrackCounts(testCase: OperatorCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.operatorCommandID, count: 7, hasSelection: false) == .beginOperator(testCase.operatorValue))
	#expect(engine.pendingOperator == testCase.operatorValue)
	#expect(engine.pendingOperatorCount == 7)
	#expect(engine.mode == .operatorPending)
}

@Test(arguments: operatorCases)
func vimEngineCommandIDOperatorsApplySelection(testCase: OperatorCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.operatorCommandID, count: 7, hasSelection: true) == .applySelectionOperator(testCase.operatorValue))
	#expect(engine.pendingOperator == nil)
}

@Test(arguments: operatorCases)
func vimEngineCommandIDLineOperatorsEmitActions(testCase: OperatorCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.lineOperatorCommandID, count: 1, hasSelection: false) == .lineOperator(testCase.operatorValue))
}

@Test(arguments: visualCommandCases)
func vimEngineCommandIDVisualModesEmitActions(testCase: VisualCommandCase) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: testCase.commandID, count: 1, hasSelection: false) == .beginVisualMode(testCase.mode))
}

@Test(arguments: searchCommandCases)
func vimEngineCommandIDSearchRecordsNormalModeJumps(commandID: String) {
	var engine = VimEngine()
	#expect(engine.handle(commandID: commandID, count: 1, hasSelection: false) == .search(commandID, recordsJump: true))
	engine.mode = .visual(.character)
	#expect(engine.handle(commandID: commandID, count: 1, hasSelection: false) == .search(commandID, recordsJump: false))
}

@Test(arguments: unsupportedNormalSequences)
func vimEngineUnsupportedSequencesStayNoOp(testCase: UnsupportedSequenceCase) {
	var engine = VimEngine()
	var actions: [VimAction] = []
	for key in testCase.keys {
		actions += engine.handle(key, buffer: blackBoxBuffer)
	}
	#expect(actions.isEmpty)
}

private let blackBoxBuffer = BlackBoxBuffer("alpha beta\ngamma delta\n\n<tag>value</tag>\n")

struct MotionKeyCase: Sendable {
	var name: String
	var key: Key
	var motion: Motion
	var commandName: String
}

private let motionKeyCases: [MotionKeyCase] = [
	MotionKeyCase(name: "h", key: Key("h"), motion: .charBackward, commandName: "charBackward"),
	MotionKeyCase(name: "left", key: Key("left"), motion: .charBackward, commandName: "charBackward"),
	MotionKeyCase(name: "l", key: Key("l"), motion: .charForward, commandName: "charForward"),
	MotionKeyCase(name: "right", key: Key("right"), motion: .charForward, commandName: "charForward"),
	MotionKeyCase(name: "j", key: Key("j"), motion: .lineDown, commandName: "lineDown"),
	MotionKeyCase(name: "down", key: Key("down"), motion: .lineDown, commandName: "lineDown"),
	MotionKeyCase(name: "k", key: Key("k"), motion: .lineUp, commandName: "lineUp"),
	MotionKeyCase(name: "up", key: Key("up"), motion: .lineUp, commandName: "lineUp"),
	MotionKeyCase(name: "w", key: Key("w"), motion: .wordForward, commandName: "wordForward"),
	MotionKeyCase(name: "b", key: Key("b"), motion: .wordBackward, commandName: "wordBackward"),
	MotionKeyCase(name: "e", key: Key("e"), motion: .wordEnd, commandName: "wordEnd"),
	MotionKeyCase(name: "W", key: Key("w", modifiers: .shift), motion: .bigWordForward, commandName: "bigWordForward"),
	MotionKeyCase(name: "B", key: Key("b", modifiers: .shift), motion: .bigWordBackward, commandName: "bigWordBackward"),
	MotionKeyCase(name: "E", key: Key("e", modifiers: .shift), motion: .bigWordEnd, commandName: "bigWordEnd"),
	MotionKeyCase(name: "0", key: Key("0"), motion: .lineStart, commandName: "lineStart"),
	MotionKeyCase(name: "^", key: Key("6", modifiers: .shift), motion: .lineStart, commandName: "lineStart"),
	MotionKeyCase(name: "$", key: Key("4", modifiers: .shift), motion: .lineEnd, commandName: "lineEnd"),
	MotionKeyCase(name: "G", key: Key("g", modifiers: .shift), motion: .bufferEnd, commandName: "bufferEnd"),
	MotionKeyCase(name: "{", key: Key("{", modifiers: .shift), motion: .paragraphBackward, commandName: "paragraphBackward"),
	MotionKeyCase(name: "}", key: Key("}", modifiers: .shift), motion: .paragraphForward, commandName: "paragraphForward"),
	MotionKeyCase(name: "ctrl-f", key: Key("f", modifiers: .control), motion: .pageDown, commandName: "pageDown"),
	MotionKeyCase(name: "ctrl-b", key: Key("b", modifiers: .control), motion: .pageUp, commandName: "pageUp"),
]

struct OperatorCase: Sendable {
	var key: Key
	var operatorValue: VimOperator
	var commandName: String

	var operatorCommandID: String {
		"vim.operator.\(commandName)"
	}

	var lineOperatorCommandID: String {
		"vim.operator.line.\(commandName)"
	}
}

private let operatorCases: [OperatorCase] = [
	OperatorCase(key: Key("d"), operatorValue: .delete, commandName: "delete"),
	OperatorCase(key: Key("c"), operatorValue: .change, commandName: "change"),
	OperatorCase(key: Key("y"), operatorValue: .yank, commandName: "yank"),
]

struct OperatorMotionCase: Sendable {
	var operatorCase: OperatorCase
	var motion: MotionKeyCase
}

private let operatorMotionCases = operatorCases.flatMap { op in
	motionKeyCases.map { motion in
		OperatorMotionCase(operatorCase: op, motion: motion)
	}
}

struct VisualModeCase: Sendable {
	var key: Key
	var mode: VisualMode
}

private let visualModeCases: [VisualModeCase] = [
	VisualModeCase(key: Key("v"), mode: .character),
	VisualModeCase(key: Key("v", modifiers: .shift), mode: .line),
	VisualModeCase(key: Key("v", modifiers: .control), mode: .block),
]

struct RegisterCase: Sendable {
	var key: Key
	var name: Character
}

private let registerCases: [RegisterCase] =
	[RegisterCase(key: Key("\""), name: "\""), RegisterCase(key: Key("+"), name: "+"), RegisterCase(key: Key("*"), name: "*"), RegisterCase(key: Key("_"), name: "_")]
	+ (0 ... 9).map { RegisterCase(key: Key(String($0)), name: Character(String($0))) }
	+ lowercaseLetters.map { RegisterCase(key: Key(String($0)), name: $0) }

struct MacroRegisterCase: Sendable {
	var key: Key
	var name: Character
}

private let macroRegisterCases: [MacroRegisterCase] =
	(0 ... 9).map { MacroRegisterCase(key: Key(String($0)), name: Character(String($0))) }
	+ lowercaseLetters.map { MacroRegisterCase(key: Key(String($0)), name: $0) }

struct MarkCase: Sendable {
	var key: Key
	var name: Character
}

private let markCases: [MarkCase] = lowercaseLetters.map { MarkCase(key: Key(String($0)), name: $0) }

struct SearchKeyCase: Sendable {
	var key: Key
	var actions: [VimAction]
}

private let searchKeyCases: [SearchKeyCase] = [
	SearchKeyCase(key: Key("/"), actions: [.setMode(.command), .search(SearchQuery(text: "", direction: .forward))]),
	SearchKeyCase(key: Key("/", modifiers: .shift), actions: [.setMode(.command), .search(SearchQuery(text: "", direction: .backward))]),
	SearchKeyCase(key: Key("n"), actions: [.repeatSearch(reverse: false)]),
	SearchKeyCase(key: Key("n", modifiers: .shift), actions: [.repeatSearch(reverse: true)]),
]

struct CommandMotionCase: Sendable {
	var commandID: String
}

private let commandMotionCases: [CommandMotionCase] = [
	"editor.moveLeft",
	"editor.moveRight",
	"editor.moveDown",
	"editor.moveUp",
	"editor.moveWordForward",
	"editor.moveWordBackward",
	"editor.moveWordEnd",
	"editor.moveBigWordForward",
	"editor.moveBigWordBackward",
	"editor.moveBigWordEnd",
	"editor.moveLineStart",
	"editor.moveLineEnd",
	"editor.moveBufferStart",
	"editor.moveBufferEnd",
	"editor.moveParagraphForward",
	"editor.moveParagraphBackward",
].map(CommandMotionCase.init)

private let textObjectCommandIDs = [
	"vim.textObject.innerWord",
	"vim.textObject.aroundWord",
	"vim.textObject.innerSentence",
	"vim.textObject.aroundSentence",
	"vim.textObject.innerDoubleQuote",
	"vim.textObject.aroundDoubleQuote",
	"vim.textObject.innerSingleQuote",
	"vim.textObject.aroundSingleQuote",
	"vim.textObject.innerParen",
	"vim.textObject.aroundParen",
	"vim.textObject.innerBracket",
	"vim.textObject.aroundBracket",
	"vim.textObject.innerBrace",
	"vim.textObject.aroundBrace",
	"vim.textObject.innerParagraph",
	"vim.textObject.aroundParagraph",
	"vim.textObject.innerTag",
	"vim.textObject.aroundTag",
]

struct CommandActionCase: Sendable {
	var commandID: String
	var action: VimCommandAction
}

private let commandActionCases: [CommandActionCase] = [
	CommandActionCase(commandID: "editor.repeatCharFind", action: .repeatCharacterMotion(reversed: false)),
	CommandActionCase(commandID: "editor.repeatCharFindReverse", action: .repeatCharacterMotion(reversed: true)),
	CommandActionCase(commandID: "edit.undo", action: .undo),
	CommandActionCase(commandID: "edit.redo", action: .redo),
	CommandActionCase(commandID: "editor.addNextSelection", action: .addNextSelection),
	CommandActionCase(commandID: "vim.registerPrefix", action: .handled),
	CommandActionCase(commandID: "vim.jumpBack", action: .jumpBack),
	CommandActionCase(commandID: "vim.macro.recordPrefix", action: .macroRecordPrefix),
	CommandActionCase(commandID: "vim.macro.replayPrefix", action: .macroReplayPrefix),
	CommandActionCase(commandID: "vim.pasteAfter", action: .paste(after: true)),
	CommandActionCase(commandID: "vim.pasteBefore", action: .paste(after: false)),
	CommandActionCase(commandID: "vim.ex.start", action: .exStart),
	CommandActionCase(commandID: "file.save", action: .save),
	CommandActionCase(commandID: "file.close", action: .close),
	CommandActionCase(commandID: "mode.normal", action: .normalMode),
	CommandActionCase(commandID: "mode.insert", action: .insertMode),
	CommandActionCase(commandID: "mode.emacs", action: .emacsMode),
	CommandActionCase(commandID: "vim.mark.set.a", action: .setMark("a")),
	CommandActionCase(commandID: "vim.mark.jump.z", action: .jumpToMark("z")),
	CommandActionCase(commandID: "vim.mark.jumpLine.b", action: .jumpToMarkLine("b")),
	CommandActionCase(commandID: "vim.macro.record.a", action: .macroRecord("a")),
	CommandActionCase(commandID: "vim.case.toggle", action: .toggleCaseAtCursor),
	CommandActionCase(commandID: "vim.case.toggleOperator", action: .beginOperator(.toggleCase)),
	CommandActionCase(commandID: "vim.case.lowerOperator", action: .beginOperator(.lowercase)),
	CommandActionCase(commandID: "vim.case.upperOperator", action: .beginOperator(.uppercase)),
	CommandActionCase(commandID: "vim.indent.right", action: .lineOperator(.indentRight)),
	CommandActionCase(commandID: "vim.indent.left", action: .lineOperator(.indentLeft)),
	CommandActionCase(commandID: "vim.repeatChange", action: .repeatLastChange),
	CommandActionCase(commandID: "vim.replace.char", action: .replaceCharacter),
	CommandActionCase(commandID: "vim.searchHistory.forward", action: .search("edit.findNext", recordsJump: false)),
]

struct CharacterMotionCommandCase: Sendable {
	var commandID: String
	var motion: CharacterMotion
}

private let characterMotionCommandCases: [CharacterMotionCommandCase] = [
	CharacterMotionCommandCase(commandID: "editor.findCharForward", motion: .findForward),
	CharacterMotionCommandCase(commandID: "editor.findCharBackward", motion: .findBackward),
	CharacterMotionCommandCase(commandID: "editor.tillCharForward", motion: .tillForward),
	CharacterMotionCommandCase(commandID: "editor.tillCharBackward", motion: .tillBackward),
]

struct VisualCommandCase: Sendable {
	var commandID: String
	var mode: VisualMode
}

private let visualCommandCases: [VisualCommandCase] = [
	VisualCommandCase(commandID: "vim.visual.char", mode: .character),
	VisualCommandCase(commandID: "vim.visual.line", mode: .line),
	VisualCommandCase(commandID: "vim.visual.block", mode: .block),
]

private let searchCommandCases = [
	"edit.findNext",
	"edit.findPrevious",
	"vim.searchForward",
	"vim.searchBackward",
]

struct UnsupportedSequenceCase: Sendable {
	var keys: [Key]
}

private let unsupportedNormalSequences: [UnsupportedSequenceCase] = [
	UnsupportedSequenceCase(keys: [Key("`", modifiers: .shift)]),
	UnsupportedSequenceCase(keys: [Key("g"), Key("`", modifiers: .shift)]),
	UnsupportedSequenceCase(keys: [Key("g"), Key("u")]),
	UnsupportedSequenceCase(keys: [Key("g"), Key("u", modifiers: .shift)]),
	UnsupportedSequenceCase(keys: [Key(".", modifiers: .shift), Key(".", modifiers: .shift)]),
	UnsupportedSequenceCase(keys: [Key(",", modifiers: .shift), Key(",", modifiers: .shift)]),
	UnsupportedSequenceCase(keys: [Key(".")]),
]

private let lowercaseLetters = Array("abcdefghijklmnopqrstuvwxyz")

private struct BlackBoxBuffer: BufferQuery {
	private let text: String

	init(_ text: String) {
		self.text = text
	}

	var length: Int {
		text.utf8.count
	}

	func line(forOffset offset: Int) -> Int {
		text.utf8.prefix(max(0, min(offset, length))).filter { $0 == 10 }.count
	}

	func substring(_ range: Range<Int>) -> String {
		let lower = text.utf8.index(text.utf8.startIndex, offsetBy: range.lowerBound)
		let upper = text.utf8.index(text.utf8.startIndex, offsetBy: range.upperBound)
		return String(decoding: text.utf8[lower ..< upper], as: UTF8.self)
	}

	func graphemeBoundary(after offset: Int) -> Int {
		min(length, offset + 1)
	}
}
