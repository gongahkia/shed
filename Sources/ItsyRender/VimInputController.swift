import AppKit
import ItsyEditor
import struct ItsyKeymap.Key
import enum ItsyVim.CharacterMotion
import struct ItsyVim.KeyModifiers
import struct ItsyVim.Position
import struct ItsyVim.SelectionSnapshot
import enum ItsyVim.VimCommandAction
import enum ItsyVim.VimOperator
import enum ItsyVim.VisualMode
import struct ItsyVim.VimSelection
import struct ItsyVim.RecordedKey

enum KeyDispatchResult {
	case handled
	case passthrough
}

enum RegisterOperation {
	case yank
	case delete
}

enum TextObject {
	case innerWord
	case aroundWord
	case innerPair(Character, Character)
	case aroundPair(Character, Character)
	case innerParagraph
	case aroundParagraph
}

extension MetalTextView {
	var pendingCharacterMotion: CharacterMotion? {
		get { vimEngine.pendingCharacterMotion }
		set { vimEngine.pendingCharacterMotion = newValue }
	}

	var lastCharacterMotion: (motion: CharacterMotion, value: Character)? {
		get { vimEngine.lastCharacterMotion }
		set { vimEngine.lastCharacterMotion = newValue }
	}

	var pendingOperator: VimOperator? {
		get { vimEngine.pendingOperator }
		set { vimEngine.pendingOperator = newValue }
	}

	var pendingOperatorCount: Int {
		get { vimEngine.pendingOperatorCount }
		set { vimEngine.pendingOperatorCount = newValue }
	}

	var visualAnchor: Int? {
		get { vimEngine.visualAnchor }
		set { vimEngine.visualAnchor = newValue }
	}

	var visualHead: Int? {
		get { vimEngine.visualHead }
		set { vimEngine.visualHead = newValue }
	}

	var visualMode: VisualMode? {
		get { vimEngine.visualMode }
		set { vimEngine.visualMode = newValue }
	}

	var jumpBackSelection: SelectionSet? {
		get { vimEngine.jumpBackSelection.map(SelectionSet.init) }
		set { vimEngine.jumpBackSelection = newValue.map(SelectionSnapshot.init) }
	}

	var awaitingRegister: Bool {
		get { vimEngine.awaitingRegister }
		set { vimEngine.awaitingRegister = newValue }
	}

	var pendingRegister: String? {
		get { vimEngine.pendingRegister }
		set { vimEngine.pendingRegister = newValue }
	}

	var registers: [String: String] {
		get { vimEngine.registers }
		set { vimEngine.registers = newValue }
	}

	var macroRegisters: [String: [RecordedKey]] {
		get { vimEngine.macroRegisters }
		set { vimEngine.macroRegisters = newValue }
	}

	var recordingMacroRegister: String? {
		get { vimEngine.recordingMacroRegister }
		set { vimEngine.recordingMacroRegister = newValue }
	}

	var currentMacroEvents: [RecordedKey] {
		get { vimEngine.currentMacroEvents }
		set { vimEngine.currentMacroEvents = newValue }
	}

	var awaitingMacroRecordRegister: Bool {
		get { vimEngine.awaitingMacroRecordRegister }
		set { vimEngine.awaitingMacroRecordRegister = newValue }
	}

	var awaitingMacroReplayRegister: Bool {
		get { vimEngine.awaitingMacroReplayRegister }
		set { vimEngine.awaitingMacroReplayRegister = newValue }
	}

	var replayingMacro: Bool {
		get { vimEngine.replayingMacro }
		set { vimEngine.replayingMacro = newValue }
	}

	var pendingExCommand: String? {
		get { vimEngine.pendingExCommand }
		set { vimEngine.pendingExCommand = newValue }
	}

	func dispatchKeymap(_ event: NSEvent) -> KeyDispatchResult {
		switch keymapEngine.handle(event) {
		case .command(let commandID):
			return performKeymapCommand(commandID) ? .handled : .passthrough
		case .partial, .consumed:
			return .handled
		case .passthrough:
			return .passthrough
		}
	}

	func performKeymapCommand(_ commandID: String) -> Bool {
		if commandID != "emacs.yank", commandID != "emacs.yankPop" {
			lastYankRange = nil
		}
		if let action = vimEngine.handle(
			commandID: commandID,
			count: keymapRepeatCount,
			hasSelection: !editor.selections.primary.isCaret
		) {
			return applyVimCommandAction(action)
		}
		switch commandID {
		case "emacs.killRegion":
			killSelectedText(delete: true)
			return true
		case "emacs.copyRegion":
			killSelectedText(delete: false)
			return true
		case "emacs.yank":
			yankFromKillRing()
			return true
		case "emacs.yankPop":
			yankPopFromKillRing()
			return true
		case "emacs.setMark", "emacs.exchangePointMark", "emacs.transposeChars", "emacs.transposeWords",
		     "emacs.uppercaseWord", "emacs.lowercaseWord", "emacs.capitalizeWord", "emacs.forwardSexp",
		     "emacs.backwardSexp", "emacs.killSexp", "emacs.markSexp", "emacs.macro.start",
		     "emacs.macro.end", "emacs.macro.run", "emacs.rectangle.kill", "emacs.rectangle.yank",
		     "emacs.rectangle.string", "emacs.queryReplace", "nav.gotoLine":
			return true
		default:
			return performHostCommand(commandID)
		}
	}

	func applyVimCommandAction(_ action: VimCommandAction) -> Bool {
		switch action {
		case .motion(let commandID):
			guard let motion = motion(for: commandID) else {
				return false
			}
			repeatMotion(motion)
		case .visualMotion(let commandID):
			guard let motion = motion(for: commandID) else {
				return false
			}
			extendVisualSelection(motion: motion)
		case .beginCharacterMotion:
			return true
		case .repeatCharacterMotion(let reversed):
			repeatLastCharacterMotion(reversed: reversed)
		case .undo:
			endInsertUndoGroup()
			editor.undo()
			syncEditorState()
			editorDidChange?(editor)
			return true
		case .redo:
			endInsertUndoGroup()
			editor.redo()
			syncEditorState()
			editorDidChange?(editor)
			return true
		case .addNextSelection:
			addNextSelectionMatch()
		case .beginOperator:
			vimEngine.mode = .operatorPending
			keymapEngine.pushMode(.operatorPending)
		case .applySelectionOperator(let op):
			applySelectionOperator(op)
			leaveVisualMode(collapse: op == .yank)
			keymapEngine.setMode(op == .change ? .insert : .normal)
			vimEngine.mode = op == .change ? .insert : .normal
		case .applyPendingOperatorMotion(let commandID):
			guard let motion = motion(for: commandID) else {
				return false
			}
			applyPendingOperator(motion: motion)
		case .applyPendingOperatorTextObject(let commandID):
			guard let textObject = textObject(for: commandID) else {
				return false
			}
			applyPendingOperator(textObject: textObject)
		case .lineOperator(let op):
			applyLineOperator(op)
		case .jumpBack:
			jumpBack()
		case .macroRecordPrefix:
			handleMacroRecordPrefix()
			return true
		case .macroReplayPrefix:
			awaitingMacroReplayRegister = true
			return true
		case .paste(let after):
			pasteRegister(after: after)
		case .exStart:
			keymapEngine.setMode(.command)
			vimEngine.mode = .command
			if exCommandLineRequested?({ [weak self] command in
				self?.finishExCommand(command)
			}) == true {
				pendingExCommand = nil
			}
			return true
		case .beginVisualMode(let mode):
			beginVisualMode(mode)
		case .search(let commandID, let recordsJump):
			return performHostCommand(commandID, recordsJump: recordsJump)
		case .save:
			saveRequested?()
			return true
		case .close:
			closeRequested?()
			return true
		case .normalMode:
			endInsertUndoGroup()
			leaveVisualMode(collapse: true)
			keymapEngine.setMode(.normal)
			vimEngine.mode = .normal
			return true
		case .insertMode:
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			vimEngine.mode = .insert
			return true
		case .emacsMode:
			keymapEngine.setMode(.emacs)
			return true
		case .setMark(let mark):
			vimEngine.marks[mark] = Position(offset: editor.selections.primary.head)
		case .jumpToMark(let mark):
			if let position = vimEngine.marks[mark] {
				editor.setSelection(SelectionSet(primary: Selection(anchor: position.offset, head: position.offset)))
				syncEditorState()
			}
		case .macroRecord(let register):
			startMacroRecording(register)
			return true
		case .handled:
			return true
		}
		syncEditorState()
		return true
	}

	func performHostCommand(_ commandID: String, recordsJump: Bool = false) -> Bool {
		let before = editor.selections
		guard commandRequested?(commandID) == true else {
			return false
		}
		if recordsJump {
			jumpBackSelection = before
		}
		return true
	}

	func beginInsertUndoGroup() {
		guard !insertUndoGroupActive else {
			return
		}
		editor.beginUndoGroup()
		insertUndoGroupActive = true
	}

	func endInsertUndoGroup() {
		guard insertUndoGroupActive else {
			return
		}
		editor.endUndoGroup()
		insertUndoGroupActive = false
	}

	func killSelectedText(delete: Bool) {
		let ranges = selectedNonEmptyRanges()
		guard !ranges.isEmpty else {
			return
		}
		let text = ranges.map { editor.rope.slice($0) }.joined()
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		if delete {
			editor.deleteForward()
			syncEditorState()
			editorDidChange?(editor)
		}
	}

	func yankFromKillRing() {
		let text = killRing.current ?? NSPasteboard.general.string(forType: .string)
		guard let text, !text.isEmpty else {
			return
		}
		let range = editor.selections.primary.range
		replace(range: range, with: text)
		lastYankRange = range.lowerBound ..< range.lowerBound + text.utf8.count
		syncEditorState()
		editorDidChange?(editor)
	}

	func yankPopFromKillRing() {
		guard let lastYankRange, let text = killRing.rotate() else {
			return
		}
		replace(range: lastYankRange, with: text)
		self.lastYankRange = lastYankRange.lowerBound ..< lastYankRange.lowerBound + text.utf8.count
		syncEditorState()
		editorDidChange?(editor)
	}

	func selectedNonEmptyRanges() -> [Range<Int>] {
		([editor.selections.primary] + editor.selections.secondaries)
			.map(\.range)
			.filter { !$0.isEmpty }
			.sorted { $0.lowerBound < $1.lowerBound }
	}

	func addNextSelectionMatch() {
		let selectedRanges = selectedNonEmptyRanges()
		let queryRange: Range<Int>
		if let selectedRange = selectedRanges.first {
			queryRange = selectedRange
		} else if let wordRange = wordRange(at: editor.selections.primary.head) {
			editor.setSelection(SelectionSet(primary: Selection(anchor: wordRange.lowerBound, head: wordRange.upperBound)))
			syncEditorState()
			return
		} else {
			return
		}
		let query = editor.rope.slice(queryRange)
		guard !query.isEmpty, let nextRange = nextMatchRange(for: query, after: selectedRanges.map(\.upperBound).max() ?? queryRange.upperBound, excluding: selectedRanges) else {
			return
		}
		let selections = [editor.selections.primary] + editor.selections.secondaries + [Selection(anchor: nextRange.lowerBound, head: nextRange.upperBound)]
		editor.setSelection(SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst())))
		syncEditorState()
	}

	func wordRange(at offset: Int) -> Range<Int>? {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return nil
		}
		let clamped = min(max(offset, 0), editor.rope.length)
		let index = offsets.lastIndex { $0.offset <= clamped } ?? 0
		guard isTextObjectWordCharacter(offsets[index].character) else {
			return nil
		}
		var lowerIndex = index
		while lowerIndex > 0, isTextObjectWordCharacter(offsets[lowerIndex - 1].character) {
			lowerIndex -= 1
		}
		var upperIndex = index
		while upperIndex + 1 < offsets.count, isTextObjectWordCharacter(offsets[upperIndex + 1].character) {
			upperIndex += 1
		}
		return offsets[lowerIndex].offset ..< offsets[upperIndex].offset + String(offsets[upperIndex].character).utf8.count
	}

	func nextMatchRange(for query: String, after offset: Int, excluding excluded: [Range<Int>]) -> Range<Int>? {
		let text = editorStorageString(editor)
		guard let startIndex = String.Index(text.utf8.index(text.utf8.startIndex, offsetBy: min(offset, text.utf8.count)), within: text) else {
			return nil
		}
		let ranges = [
			startIndex ..< text.endIndex,
			text.startIndex ..< startIndex,
		]
		for searchRange in ranges {
			var cursor = searchRange.lowerBound
			while cursor < searchRange.upperBound, let match = text.range(of: query, range: cursor ..< searchRange.upperBound) {
				let utf8Match = utf8Range(match, in: text)
				if !excluded.contains(where: { $0 == utf8Match }) {
					return utf8Match
				}
				cursor = match.upperBound
			}
		}
		return nil
	}

	func utf8Range(_ range: Range<String.Index>, in text: String) -> Range<Int> {
		let lower = text.utf8.distance(from: text.utf8.startIndex, to: range.lowerBound.samePosition(in: text.utf8) ?? text.utf8.startIndex)
		let upper = text.utf8.distance(from: text.utf8.startIndex, to: range.upperBound.samePosition(in: text.utf8) ?? text.utf8.endIndex)
		return lower ..< upper
	}

	func motion(for commandID: String) -> Motion? {
		switch commandID {
		case "editor.moveLeft":
			return .charBackward
		case "editor.moveRight":
			return .charForward
		case "editor.moveDown":
			return .lineDown
		case "editor.moveUp":
			return .lineUp
		case "editor.moveWordForward":
			return .wordForward
		case "editor.moveWordBackward":
			return .wordBackward
		case "editor.moveWordEnd":
			return .wordEnd
		case "editor.moveBigWordForward":
			return .bigWordForward
		case "editor.moveBigWordBackward":
			return .bigWordBackward
		case "editor.moveBigWordEnd":
			return .bigWordEnd
		case "editor.moveLineStart":
			return .lineStart
		case "editor.moveLineEnd":
			return .lineEnd
		case "editor.moveBufferStart":
			return .bufferStart
		case "editor.moveBufferEnd":
			return .bufferEnd
		case "editor.moveParagraphForward":
			return .paragraphForward
		case "editor.moveParagraphBackward":
			return .paragraphBackward
		default:
			return nil
		}
	}

	func textObject(for commandID: String) -> TextObject? {
		switch commandID {
		case "vim.textObject.innerWord":
			return .innerWord
		case "vim.textObject.aroundWord":
			return .aroundWord
		case "vim.textObject.innerDoubleQuote":
			return .innerPair("\"", "\"")
		case "vim.textObject.aroundDoubleQuote":
			return .aroundPair("\"", "\"")
		case "vim.textObject.innerSingleQuote":
			return .innerPair("'", "'")
		case "vim.textObject.aroundSingleQuote":
			return .aroundPair("'", "'")
		case "vim.textObject.innerParen":
			return .innerPair("(", ")")
		case "vim.textObject.aroundParen":
			return .aroundPair("(", ")")
		case "vim.textObject.innerBracket":
			return .innerPair("[", "]")
		case "vim.textObject.aroundBracket":
			return .aroundPair("[", "]")
		case "vim.textObject.innerBrace":
			return .innerPair("{", "}")
		case "vim.textObject.aroundBrace":
			return .aroundPair("{", "}")
		case "vim.textObject.innerParagraph":
			return .innerParagraph
		case "vim.textObject.aroundParagraph":
			return .aroundParagraph
		default:
			return nil
		}
	}

	func applyPendingOperator(motion: Motion) {
		guard let pendingOperator else {
			return
		}
		let start = editor.selections.primary.head
		var projected = editor
		let count = max(1, pendingOperatorCount * keymapRepeatCount)
		for _ in 0 ..< count {
			projected.moveCursor(motion)
		}
		let end = projected.selections.primary.head
		clearPendingOperator()
		applyOperator(pendingOperator, range: min(start, end) ..< max(start, end))
	}

	func applyPendingOperator(textObject: TextObject) {
		guard let pendingOperator, let range = textObjectRange(textObject) else {
			return
		}
		clearPendingOperator()
		applyOperator(pendingOperator, range: range)
	}

	func applyLineOperator(_ op: VimOperator) {
		clearPendingOperator()
		applyOperator(op, range: currentLineIncludingNewline())
	}

	func clearPendingOperator() {
		pendingOperator = nil
		pendingOperatorCount = 1
		vimEngine.mode = .normal
		if keymapEngine.mode == .operatorPending {
			_ = keymapEngine.popMode()
		}
	}

	func applyOperator(_ op: VimOperator, range: Range<Int>) {
		guard !range.isEmpty else {
			return
		}
		let text = editor.rope.slice(range)
		switch op {
		case .delete:
			writeRegister(text, operation: .delete)
			replace(range: range, with: "")
			vimEngine.mode = .normal
		case .change:
			writeRegister(text, operation: .delete)
			replace(range: range, with: "")
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			vimEngine.mode = .insert
		case .yank:
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
			syncEditorState()
			vimEngine.mode = .normal
		}
	}

	func applySelectionOperator(_ op: VimOperator) {
		let selections = ([editor.selections.primary] + editor.selections.secondaries)
			.map(\.range)
			.filter { !$0.isEmpty }
			.sorted { $0.lowerBound < $1.lowerBound }
		guard !selections.isEmpty else {
			return
		}
		switch op {
		case .delete:
			writeRegister(selections.map { editor.rope.slice($0) }.joined(), operation: .delete)
			editor.deleteForward()
			syncEditorState()
			editorDidChange?(editor)
		case .change:
			writeRegister(selections.map { editor.rope.slice($0) }.joined(), operation: .delete)
			editor.deleteForward()
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			syncEditorState()
			editorDidChange?(editor)
		case .yank:
			let text = selections.map { editor.rope.slice($0) }.joined()
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: selections[0].lowerBound, head: selections[0].lowerBound)))
			syncEditorState()
		}
	}

	func currentLineIncludingNewline() -> Range<Int> {
		let line = editor.rope.line(forOffset: editor.selections.primary.head)
		let start = editor.rope.offset(forLine: line)
		let end = line + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: line + 1) : editor.rope.length
		return start ..< end
	}

	func handleExCommandInput(_ event: NSEvent) -> Bool {
		guard pendingExCommand != nil else {
			return false
		}
		switch event.keyCode {
		case 36:
			let command = pendingExCommand ?? ""
			pendingExCommand = nil
			finishExCommand(command)
		case 51:
			if pendingExCommand?.isEmpty == false {
				pendingExCommand?.removeLast()
			}
		case 53:
			pendingExCommand = nil
			keymapEngine.setMode(.normal)
			vimEngine.mode = .normal
		default:
			guard event.modifierFlags.intersection([.command, .control]).isEmpty, let characters = event.characters, !characters.isEmpty else {
				return true
			}
			pendingExCommand? += characters
		}
		return true
	}

	func finishExCommand(_ command: String?) {
		keymapEngine.setMode(.normal)
		vimEngine.mode = .normal
		guard let command else {
			return
		}
		executeExCommand(command)
	}

	func executeExCommand(_ command: String) {
		let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
		let normalized = trimmed.hasPrefix(":") ? String(trimmed.dropFirst()) : trimmed
		if executeSubstitution(normalized) {
			return
		}
		_ = exCommandRequested?(normalized)
	}

	func executeSubstitution(_ command: String) -> Bool {
		guard command.hasPrefix("%s/"), command.hasSuffix("/g") else {
			return false
		}
		let body = command.dropFirst(3).dropLast(2)
		let parts = body.split(separator: "/", omittingEmptySubsequences: false)
		guard parts.count == 2 else {
			return false
		}
		let needle = String(parts[0])
		let replacement = String(parts[1])
		guard !needle.isEmpty else {
			return true
		}
		editor = Editor(text: editorStorageString(editor).replacingOccurrences(of: needle, with: replacement))
		syncEditorState()
		editorDidChange?(editor)
		return true
	}

	func handlePendingRegister(_ event: NSEvent) -> Bool {
		guard awaitingRegister, let key = Key(event: event), let register = registerName(for: key) else {
			return false
		}
		pendingRegister = register
		awaitingRegister = false
		return true
	}

	func handlePendingMacroRegister(_ event: NSEvent) -> Bool {
		if awaitingMacroRecordRegister {
			awaitingMacroRecordRegister = false
			if let register = macroRegisterName(for: event) {
				startMacroRecording(register)
			}
			return true
		}
		if awaitingMacroReplayRegister {
			let recordsRegister = recordingMacroRegister != nil && !replayingMacro
			awaitingMacroReplayRegister = false
			recordMacroEvent(event, when: recordsRegister)
			if let register = macroRegisterName(for: event) {
				replayMacro(register)
			}
			return true
		}
		return false
	}

	func handleMacroStop(_ event: NSEvent) -> Bool {
		guard recordingMacroRegister != nil, keymapEngine.mode == .normal, let key = Key(event: event), key.modifiers.isEmpty, key.value == "q" else {
			return false
		}
		stopMacroRecording()
		return true
	}

	func registerName(for key: Key) -> String? {
		if key.modifiers == .shift, key.value == "'" {
			return "\""
		}
		if key.modifiers == .shift, key.value == "=" {
			return "+"
		}
		if key.modifiers == .shift, key.value == "8" {
			return "*"
		}
		if key.modifiers == .shift, key.value == "-" {
			return "_"
		}
		guard key.modifiers.isEmpty, key.value.count == 1 else {
			return nil
		}
		let value = key.value
		if value == "\"" || value == "+" || value == "*" || value == "_" || value == "0" || ("1" ... "9").contains(value) {
			return value
		}
		if value >= "a", value <= "z" {
			return value
		}
		return nil
	}

	func macroRegisterName(for event: NSEvent) -> String? {
		guard let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1 else {
			return nil
		}
		if ("a" ... "z").contains(key.value) || ("0" ... "9").contains(key.value) {
			return key.value
		}
		return nil
	}

	func handleMacroRecordPrefix() {
		if recordingMacroRegister != nil {
			stopMacroRecording()
		} else {
			awaitingMacroRecordRegister = true
		}
	}

	func startMacroRecording(_ register: String) {
		recordingMacroRegister = register
		currentMacroEvents = []
	}

	func stopMacroRecording() {
		guard let register = recordingMacroRegister else {
			return
		}
		macroRegisters[register] = currentMacroEvents
		recordingMacroRegister = nil
		currentMacroEvents = []
	}

	func replayMacro(_ register: String) {
		guard !replayingMacro, let events = macroRegisters[register], !events.isEmpty else {
			return
		}
		replayingMacro = true
		defer { replayingMacro = false }
		for event in events {
			_ = handleKey(
				characters: event.characters,
				charactersIgnoringModifiers: event.charactersIgnoringModifiers,
				keyCode: event.keyCode,
				modifierFlags: event.modifierFlags.appKitModifierFlags
			)
		}
	}

	func shouldRecordMacroEvent(_ event: NSEvent) -> Bool {
		guard recordingMacroRegister != nil, !replayingMacro else {
			return false
		}
		guard let key = Key(event: event) else {
			return false
		}
		if keymapEngine.mode == .normal, key.modifiers.isEmpty, key.value == "q" {
			return false
		}
		return true
	}

	func recordMacroEvent(_ event: NSEvent, when shouldRecord: Bool) {
		if shouldRecord {
			currentMacroEvents.append(RecordedKey(event))
		}
	}

	func writeRegister(_ text: String, operation: RegisterOperation) {
		let target = pendingRegister ?? "\""
		defer { pendingRegister = nil }
		if target == "_" {
			return
		}
		registers["\""] = text
		if operation == .yank {
			registers["0"] = text
		} else {
			for index in stride(from: 9, through: 2, by: -1) {
				registers[String(index)] = registers[String(index - 1)]
			}
			registers["1"] = text
		}
		if target == "+" || target == "*" {
			NSPasteboard.general.clearContents()
			NSPasteboard.general.setString(text, forType: .string)
		} else {
			registers[target] = text
		}
	}

	func readRegister() -> String? {
		let target = pendingRegister ?? "\""
		defer { pendingRegister = nil }
		if target == "_" {
			return nil
		}
		if target == "+" || target == "*" {
			return NSPasteboard.general.string(forType: .string)
		}
		return registers[target] ?? registers["\""]
	}

	func pasteRegister(after: Bool) {
		guard let text = readRegister(), !text.isEmpty else {
			return
		}
		let head = editor.selections.primary.head
		let offset = after ? offsetAfterCharacter(at: head) : head
		editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))
		editor.insert(text)
		syncEditorState()
		editorDidChange?(editor)
	}

	func offsetAfterCharacter(at offset: Int) -> Int {
		let offsets = characterOffsets()
		guard let index = offsets.firstIndex(where: { $0.offset >= offset }) else {
			return editor.rope.length
		}
		return min(editor.rope.length, offsets[index].offset + String(offsets[index].character).utf8.count)
	}

	func beginVisualMode(_ mode: VisualMode) {
		visualAnchor = editor.selections.primary.head
		visualHead = editor.selections.primary.head
		visualMode = mode
		keymapEngine.setMode(.visual)
		vimEngine.mode = .visual(mode)
		updateVisualSelection(head: editor.selections.primary.head)
	}

	func extendVisualSelection(motion: Motion) {
		guard visualMode != nil else {
			return
		}
		var projected = editor
		let head = visualHead ?? editor.selections.primary.head
		projected.setSelection(SelectionSet(primary: Selection(anchor: head, head: head)))
		for _ in 0 ..< keymapRepeatCount {
			projected.moveCursor(motion)
		}
		updateVisualSelection(head: projected.selections.primary.head)
	}

	func updateVisualSelection(head: Int) {
		guard let visualAnchor, let visualMode else {
			return
		}
		visualHead = head
		switch visualMode {
		case .character:
			editor.setSelection(SelectionSet(primary: Selection(anchor: visualAnchor, head: head)))
		case .line:
			editor.setSelection(SelectionSet(primary: Selection(anchor: lineStart(for: visualAnchor), head: lineEndIncludingNewline(for: head))))
		case .block:
			editor.setSelection(blockSelection(anchor: visualAnchor, head: head))
		}
		syncEditorState()
	}

	func leaveVisualMode(collapse: Bool) {
		visualAnchor = nil
		visualHead = nil
		visualMode = nil
		if collapse {
			let head = editor.selections.primary.range.lowerBound
			editor.setSelection(SelectionSet(primary: Selection(anchor: head, head: head)))
			syncEditorState()
		}
	}

	func lineStart(for offset: Int) -> Int {
		editor.rope.offset(forLine: editor.rope.line(forOffset: offset))
	}

	func lineEndIncludingNewline(for offset: Int) -> Int {
		let line = editor.rope.line(forOffset: offset)
		return line + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: line + 1) : editor.rope.length
	}

	func blockSelection(anchor: Int, head: Int) -> SelectionSet {
		let anchorLine = editor.rope.line(forOffset: anchor)
		let headLine = editor.rope.line(forOffset: head)
		let lowerLine = min(anchorLine, headLine)
		let upperLine = max(anchorLine, headLine)
		let anchorColumn = anchor - editor.rope.offset(forLine: anchorLine)
		let headColumn = head - editor.rope.offset(forLine: headLine)
		let lowerColumn = min(anchorColumn, headColumn)
		let upperColumn = max(anchorColumn, headColumn) + 1
		let selections = (lowerLine ... upperLine).map { line -> Selection in
			let lineStart = editor.rope.offset(forLine: line)
			let lineEnd = editor.rope.lineRange(line).upperBound
			let lower = min(lineStart + lowerColumn, lineEnd)
			let upper = min(lineStart + upperColumn, lineEnd)
			return Selection(anchor: lower, head: upper)
		}
		return SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst()))
	}

	func textObjectRange(_ textObject: TextObject) -> Range<Int>? {
		switch textObject {
		case .innerWord:
			return wordTextObjectRange(includeWhitespace: false)
		case .aroundWord:
			return wordTextObjectRange(includeWhitespace: true)
		case .innerPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: false)
		case .aroundPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: true)
		case .innerParagraph:
			return paragraphTextObjectRange(includeBlankLine: false)
		case .aroundParagraph:
			return paragraphTextObjectRange(includeBlankLine: true)
		}
	}

	func wordTextObjectRange(includeWhitespace: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return nil
		}
		let head = editor.selections.primary.head
		let index = offsets.lastIndex { $0.offset <= head } ?? 0
		var lowerIndex = index
		while lowerIndex > 0, isTextObjectWordCharacter(offsets[lowerIndex - 1].character) {
			lowerIndex -= 1
		}
		var upperIndex = index
		while upperIndex + 1 < offsets.count, isTextObjectWordCharacter(offsets[upperIndex + 1].character) {
			upperIndex += 1
		}
		guard isTextObjectWordCharacter(offsets[lowerIndex].character) else {
			return nil
		}
		var lower = offsets[lowerIndex].offset
		var upper = offsets[upperIndex].offset + String(offsets[upperIndex].character).utf8.count
		if includeWhitespace {
			var cursor = upperIndex + 1
			while cursor < offsets.count, offsets[cursor].character.isWhitespace {
				upper = offsets[cursor].offset + String(offsets[cursor].character).utf8.count
				cursor += 1
			}
			if cursor == upperIndex + 1 {
				cursor = lowerIndex - 1
				while cursor >= 0, offsets[cursor].character.isWhitespace {
					lower = offsets[cursor].offset
					cursor -= 1
				}
			}
		}
		return lower ..< upper
	}

	func pairTextObjectRange(open: Character, close: Character, includeDelimiters: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		guard let openIndex = offsets.lastIndex(where: { $0.offset <= head && $0.character == open }) else {
			return nil
		}
		guard let closeIndex = offsets.firstIndex(where: { $0.offset > head && $0.character == close }) else {
			return nil
		}
		let openEnd = offsets[openIndex].offset + String(offsets[openIndex].character).utf8.count
		let closeEnd = offsets[closeIndex].offset + String(offsets[closeIndex].character).utf8.count
		return includeDelimiters ? offsets[openIndex].offset ..< closeEnd : openEnd ..< offsets[closeIndex].offset
	}

	func paragraphTextObjectRange(includeBlankLine: Bool) -> Range<Int>? {
		let line = editor.rope.line(forOffset: editor.selections.primary.head)
		var startLine = line
		while startLine > 0, !lineIsBlank(startLine - 1) {
			startLine -= 1
		}
		var endLine = line
		while endLine + 1 < editor.rope.lineCount, !lineIsBlank(endLine + 1) {
			endLine += 1
		}
		let start = editor.rope.offset(forLine: startLine)
		let endLineAfterObject = min(endLine + 1, editor.rope.lineCount - 1)
		var end = endLine + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: endLine + 1) : editor.rope.length
		if includeBlankLine, endLineAfterObject + 1 < editor.rope.lineCount, lineIsBlank(endLineAfterObject) {
			end = editor.rope.offset(forLine: endLineAfterObject + 1)
		}
		return start ..< end
	}

	func lineIsBlank(_ line: Int) -> Bool {
		editor.rope.slice(editor.rope.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
	}

	func isTextObjectWordCharacter(_ character: Character) -> Bool {
		!character.isWhitespace && character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0.value == 95 }
	}

	func repeatMotion(_ motion: Motion) {
		let before = editor.selections
		for _ in 0 ..< keymapRepeatCount {
			editor.moveCursor(motion)
		}
		if keymapEngine.mode == .normal, isJumpMotion(motion), editor.selections != before {
			jumpBackSelection = before
		}
	}

	func isJumpMotion(_ motion: Motion) -> Bool {
		switch motion {
		case .bufferStart, .bufferEnd, .paragraphForward, .paragraphBackward, .pageDown, .pageUp:
			return true
		default:
			return false
		}
	}

	func jumpBack() {
		let current = editor.selections
		guard let target = jumpBackSelection else {
			return
		}
		editor.setSelection(clampedSelectionSet(target))
		jumpBackSelection = current
	}

	func clampedSelectionSet(_ selectionSet: SelectionSet) -> SelectionSet {
		let length = editor.rope.length
		func clamped(_ selection: Selection) -> Selection {
			Selection(
				anchor: min(max(selection.anchor, 0), length),
				head: min(max(selection.head, 0), length),
				affinity: selection.affinity
			)
		}
		return SelectionSet(primary: clamped(selectionSet.primary), secondaries: selectionSet.secondaries.map { clamped($0) })
	}

	var keymapRepeatCount: Int {
		max(1, min(keymapEngine.lastCommandCount, 9_999))
	}

	func handlePendingCharacterMotion(_ event: NSEvent) -> Bool {
		guard let motion = pendingCharacterMotion, let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1, let value = key.value.first else {
			return false
		}
		pendingCharacterMotion = nil
		moveToCharacter(value, motion: motion, count: keymapRepeatCount)
		lastCharacterMotion = (motion, value)
		return true
	}

	func repeatLastCharacterMotion(reversed: Bool) {
		guard let lastCharacterMotion else {
			return
		}
		let motion = reversed ? lastCharacterMotion.motion.reversed : lastCharacterMotion.motion
		moveToCharacter(lastCharacterMotion.value, motion: motion, count: keymapRepeatCount)
	}

	func moveToCharacter(_ value: Character, motion: CharacterMotion, count: Int) {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return
		}
		let lineRange = editor.rope.lineRange(editor.rope.line(forOffset: editor.selections.primary.head))
		let lineOffsets = offsets.enumerated().filter { _, element in
			lineRange.contains(element.offset)
		}
		let head = editor.selections.primary.head
		let matches: [(offset: Int, index: Int)]
		switch motion {
		case .findForward, .tillForward:
			matches = lineOffsets.filter { $0.element.offset > head && $0.element.character == value }.map { ($0.element.offset, $0.offset) }
		case .findBackward, .tillBackward:
			matches = Array(lineOffsets.filter { $0.element.offset < head && $0.element.character == value }.map { ($0.element.offset, $0.offset) }.reversed())
		}
		guard count > 0, count <= matches.count else {
			return
		}
		let match = matches[count - 1]
		let targetIndex: Int
		switch motion {
		case .findForward, .findBackward:
			targetIndex = match.index
		case .tillForward:
			targetIndex = max(match.index - 1, 0)
		case .tillBackward:
			targetIndex = min(match.index + 1, offsets.count - 1)
		}
		let target = offsets[targetIndex].offset
		editor.setSelection(SelectionSet(primary: Selection(anchor: target, head: target)))
		syncEditorState()
	}

	func characterOffsets() -> [(offset: Int, character: Character)] {
		var offset = 0
		return editorStorageString(editor).map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

}

private extension RecordedKey {
	init(_ event: NSEvent) {
		self.init(
			characters: event.characters ?? "",
			charactersIgnoringModifiers: event.charactersIgnoringModifiers ?? event.characters ?? "",
			keyCode: event.keyCode,
			modifierFlags: KeyModifiers(event.modifierFlags)
		)
	}
}

private extension SelectionSnapshot {
	init(_ selectionSet: SelectionSet) {
		self.init(
			primary: VimSelection(selectionSet.primary),
			secondaries: selectionSet.secondaries.map(VimSelection.init)
		)
	}
}

private extension VimSelection {
	init(_ selection: Selection) {
		self.init(anchor: selection.anchor, head: selection.head)
	}
}

private extension SelectionSet {
	init(_ snapshot: SelectionSnapshot) {
		self.init(
			primary: Selection(snapshot.primary),
			secondaries: snapshot.secondaries.map(Selection.init)
		)
	}
}

private extension Selection {
	init(_ selection: VimSelection) {
		self.init(anchor: selection.anchor, head: selection.head)
	}
}

private extension KeyModifiers {
	init(_ flags: NSEvent.ModifierFlags) {
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

	var appKitModifierFlags: NSEvent.ModifierFlags {
		var flags: NSEvent.ModifierFlags = []
		if contains(.command) {
			flags.insert(.command)
		}
		if contains(.shift) {
			flags.insert(.shift)
		}
		if contains(.option) {
			flags.insert(.option)
		}
		if contains(.control) {
			flags.insert(.control)
		}
		return flags
	}
}
