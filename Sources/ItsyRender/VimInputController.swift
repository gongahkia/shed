import AppKit
import ItsyEditor
import ItsyKeymap

enum KeyDispatchResult {
	case handled
	case passthrough
}

enum CharacterMotion {
	case findForward
	case findBackward
	case tillForward
	case tillBackward

	var reversed: CharacterMotion {
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

enum VimOperator {
	case delete
	case change
	case yank
}

enum VisualMode {
	case character
	case line
	case block
}

enum RegisterOperation {
	case yank
	case delete
}

struct RecordedKey {
	var characters: String
	var charactersIgnoringModifiers: String
	var keyCode: UInt16
	var modifierFlags: NSEvent.ModifierFlags

	init(_ event: NSEvent) {
		characters = event.characters ?? ""
		charactersIgnoringModifiers = event.charactersIgnoringModifiers ?? characters
		keyCode = event.keyCode
		modifierFlags = event.modifierFlags.intersection([.command, .shift, .option, .control])
	}
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
		if let motion = motion(for: commandID), visualMode != nil {
			extendVisualSelection(motion: motion)
			return true
		}
		if let textObject = textObject(for: commandID), pendingOperator != nil {
			applyPendingOperator(textObject: textObject)
			return true
		}
		if let motion = motion(for: commandID), pendingOperator != nil {
			applyPendingOperator(motion: motion)
			return true
		}
		switch commandID {
		case "editor.moveLeft":
			repeatMotion(.charBackward)
		case "editor.moveRight":
			repeatMotion(.charForward)
		case "editor.moveDown":
			repeatMotion(.lineDown)
		case "editor.moveUp":
			repeatMotion(.lineUp)
		case "editor.moveWordForward":
			repeatMotion(.wordForward)
		case "editor.moveWordBackward":
			repeatMotion(.wordBackward)
		case "editor.moveWordEnd":
			repeatMotion(.wordEnd)
		case "editor.moveBigWordForward":
			repeatMotion(.bigWordForward)
		case "editor.moveBigWordBackward":
			repeatMotion(.bigWordBackward)
		case "editor.moveBigWordEnd":
			repeatMotion(.bigWordEnd)
		case "editor.moveLineStart":
			repeatMotion(.lineStart)
		case "editor.moveLineEnd":
			repeatMotion(.lineEnd)
		case "editor.moveBufferStart":
			repeatMotion(.bufferStart)
		case "editor.moveBufferEnd":
			repeatMotion(.bufferEnd)
		case "editor.moveParagraphBackward":
			repeatMotion(.paragraphBackward)
		case "editor.moveParagraphForward":
			repeatMotion(.paragraphForward)
		case "editor.findCharForward":
			pendingCharacterMotion = .findForward
			return true
		case "editor.findCharBackward":
			pendingCharacterMotion = .findBackward
			return true
		case "editor.tillCharForward":
			pendingCharacterMotion = .tillForward
			return true
		case "editor.tillCharBackward":
			pendingCharacterMotion = .tillBackward
			return true
		case "editor.repeatCharFind":
			repeatLastCharacterMotion(reversed: false)
			return true
		case "editor.repeatCharFindReverse":
			repeatLastCharacterMotion(reversed: true)
			return true
		case "edit.undo":
			endInsertUndoGroup()
			editor.undo()
			syncEditorState()
			editorDidChange?(editor)
			return true
		case "edit.redo":
			endInsertUndoGroup()
			editor.redo()
			syncEditorState()
			editorDidChange?(editor)
			return true
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
		case "editor.addNextSelection":
			addNextSelectionMatch()
			return true
		case "vim.operator.delete":
			return beginOperator(.delete)
		case "vim.operator.change":
			return beginOperator(.change)
		case "vim.operator.yank":
			return beginOperator(.yank)
		case "vim.registerPrefix":
			awaitingRegister = true
			return true
		case "vim.jumpBack":
			jumpBack()
		case "vim.macro.recordPrefix":
			handleMacroRecordPrefix()
			return true
		case "vim.macro.replayPrefix":
			awaitingMacroReplayRegister = true
			return true
		case "vim.pasteAfter":
			pasteRegister(after: true)
			return true
		case "vim.pasteBefore":
			pasteRegister(after: false)
			return true
		case "vim.ex.start":
			keymapEngine.setMode(.command)
			if exCommandLineRequested?({ [weak self] command in
				self?.finishExCommand(command)
			}) == true {
				return true
			}
			pendingExCommand = ""
			return true
		case "vim.visual.char":
			beginVisualMode(.character)
			return true
		case "vim.visual.line":
			beginVisualMode(.line)
			return true
		case "vim.visual.block":
			beginVisualMode(.block)
			return true
		case "vim.operator.line.delete":
			applyLineOperator(.delete)
			return true
		case "vim.operator.line.change":
			applyLineOperator(.change)
			return true
		case "vim.operator.line.yank":
			applyLineOperator(.yank)
			return true
		case "edit.findNext", "edit.findPrevious", "vim.searchForward", "vim.searchBackward":
			return performHostCommand(commandID, recordsJump: keymapEngine.mode == .normal)
		case "file.save":
			saveRequested?()
			return true
		case "file.close":
			closeRequested?()
			return true
		case "mode.normal":
			endInsertUndoGroup()
			leaveVisualMode(collapse: true)
			keymapEngine.setMode(.normal)
			return true
		case "mode.insert":
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			return true
		case "mode.emacs":
			keymapEngine.setMode(.emacs)
			return true
		default:
			return performHostCommand(commandID)
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
		let text = editor.text
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

	func beginOperator(_ op: VimOperator) -> Bool {
		if !editor.selections.primary.isCaret {
			applySelectionOperator(op)
			leaveVisualMode(collapse: op == .yank)
			keymapEngine.setMode(op == .change ? .insert : .normal)
			return true
		}
		pendingOperator = op
		pendingOperatorCount = keymapRepeatCount
		keymapEngine.pushMode(.operatorPending)
		return true
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
		case .change:
			writeRegister(text, operation: .delete)
			replace(range: range, with: "")
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
		case .yank:
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
			syncEditorState()
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
		editor = Editor(text: editor.text.replacingOccurrences(of: needle, with: replacement))
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

	func registerName(for key: Key) -> String? {
		if key.modifiers == .shift, key.value == "'" {
			return "\""
		}
		if key.modifiers == .shift, key.value == "=" {
			return "+"
		}
		guard key.modifiers.isEmpty, key.value.count == 1 else {
			return nil
		}
		let value = key.value
		if value == "\"" || value == "+" || value == "0" || ("1" ... "9").contains(value) {
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
				modifierFlags: event.modifierFlags
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
		registers["\""] = text
		if operation == .yank {
			registers["0"] = text
		} else {
			for index in stride(from: 9, through: 2, by: -1) {
				registers[String(index)] = registers[String(index - 1)]
			}
			registers["1"] = text
		}
		if target == "+" {
			NSPasteboard.general.clearContents()
			NSPasteboard.general.setString(text, forType: .string)
		} else {
			registers[target] = text
		}
		pendingRegister = nil
	}

	func readRegister() -> String? {
		let target = pendingRegister ?? "\""
		defer { pendingRegister = nil }
		if target == "+" {
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
		return editor.text.map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

}
