import AppKit
import ItsyEditor
import struct ItsyKeymap.Key
import enum ItsyVim.CharacterMotion
import struct ItsyVim.KeyModifiers
import struct ItsyVim.Position
import struct ItsyVim.SelectionSnapshot
import enum ItsyVim.VimCommandAction
import struct ItsyVim.VimLastChange
import enum ItsyVim.VimOperator
import enum ItsyVim.VisualMode
import struct ItsyVim.VimSelection
import struct ItsyVim.RecordedKey
import struct ItsyVim.VimInputRouter
import enum ItsyVim.VimInputRoute

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
	case innerSentence
	case aroundSentence
	case innerPair(Character, Character)
	case aroundPair(Character, Character)
	case innerParagraph
	case aroundParagraph
	case innerTag
	case aroundTag
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
			prepareVimChangeRecording(for: commandID, event: event)
			let handled = performKeymapCommand(commandID)
			if !handled {
				cancelVimChangeRecording()
			}
			return handled ? .handled : .passthrough
		case .partial:
			recordVimKeymapPrefixEvent(event)
			return .handled
		case .consumed:
			vimPendingChordEvents = []
			return .handled
		case .passthrough:
			vimPendingChordEvents = []
			return .passthrough
		}
	}

	func performKeymapCommand(_ commandID: String) -> Bool {
		if commandID != "emacs.yank", commandID != "emacs.yankPop" {
			lastYankRange = nil
		}
		var router = VimInputRouter(engine: vimEngine)
		switch router.route(
			commandID: commandID,
			count: keymapRepeatCount,
			hasSelection: !editor.selections.primary.isCaret
		) {
		case .action(let action):
			vimEngine = router.engine
			return applyVimCommandAction(action)
		case .hostCommand:
			vimEngine = router.engine
		}
		switch commandID {
		case "edit.cut":
			return cutSelectedText()
		case "edit.copy":
			return copySelectedText()
		case "edit.paste":
			return pasteTextFromPasteboard()
		case "edit.selectAll":
			let end = editor.rope.length
			editor.setSelection(SelectionSet(primary: Selection(anchor: 0, head: end)))
			syncEditorState()
			return true
		case "emacs.killRegion":
			killSelectedText(delete: true)
			return true
		case "emacs.deleteForward":
			deleteEmacsCharacter(backward: false)
			return true
		case "emacs.deleteBackward":
			deleteEmacsCharacter(backward: true)
			return true
		case "emacs.killLine":
			killEmacsLine()
			return true
		case "emacs.killWordForward":
			killEmacsWord(forward: true)
			return true
		case "emacs.killWordBackward":
			killEmacsWord(forward: false)
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
		case "emacs.setMark":
			emacsMark = editor.selections.primary.head
			return true
		case "emacs.exchangePointMark":
			guard let mark = emacsMark else { return true }
			let point = editor.selections.primary.head
			emacsMark = point
			editor.setSelection(SelectionSet(primary: Selection(anchor: mark, head: mark)))
			syncEditorState()
			return true
		case "emacs.transposeChars":
			transposeEmacsCharacters()
			return true
		case "emacs.transposeWords":
			transposeEmacsWords()
			return true
		case "emacs.uppercaseWord":
			transformEmacsWord(using: { $0.uppercased() })
			return true
		case "emacs.lowercaseWord":
			transformEmacsWord(using: { $0.lowercased() })
			return true
		case "emacs.capitalizeWord":
			transformEmacsWord(using: { $0.capitalized })
			return true
		case "emacs.forwardSexp":
			moveEmacsSexp(forward: true)
			return true
		case "emacs.backwardSexp":
			moveEmacsSexp(forward: false)
			return true
		case "emacs.killSexp":
			killEmacsSexp()
			return true
		case "emacs.markSexp":
			markEmacsSexp()
			return true
		case "emacs.macro.start":
			startMacroRecording("emacs")
			return true
		case "emacs.macro.end":
			stopEmacsMacroRecording()
			return true
		case "emacs.macro.run":
			replayMacro("emacs")
			return true
		case "emacs.rectangle.kill":
			killEmacsRectangle()
			return true
		case "emacs.rectangle.yank":
			yankEmacsRectangle()
			return true
		case "emacs.rectangle.string":
			stringEmacsRectangle()
			return true
		case "emacs.queryReplace":
			return performHostCommand("emacs.queryReplace")
		case "editor.extendLeft":
			extendSelection(motion: .charBackward)
			return true
		case "editor.extendRight":
			extendSelection(motion: .charForward)
			return true
		case "editor.extendDown":
			extendSelection(motion: .lineDown)
			return true
		case "editor.extendUp":
			extendSelection(motion: .lineUp)
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
			if keymapEngine.mode == .emacs, let mark = emacsMark {
				extendEmacsMark(mark, motion: motion)
			} else {
				repeatMotion(motion)
			}
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
		case .toggleCaseAtCursor:
			toggleCaseAtCursor()
		case .repeatLastChange:
			replayLastChange()
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
		case .insertLineStart:
			enterInsertMode(at: firstNonWhitespaceOffsetInCurrentLine())
		case .appendAfterCursor:
			enterInsertMode(at: vimAppendOffsetAfterCursor())
		case .appendLineEnd:
			enterInsertMode(at: lineEndOffsetInCurrentLine())
		case .openLine(let after):
			openVimLine(after: after)
		case .deleteCharacter(let backward, let count):
			deleteVimCharacter(backward: backward, count: count)
		case .deleteToLineEnd(let change):
			deleteToLineEnd(change: change)
		case .substituteCharacter(let count):
			substituteVimCharacters(count: count)
		case .joinLines:
			joinVimLines()
		case .replaceCharacter:
			return true
		case .replaceMode:
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			vimEngine.mode = .insert
			return true
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
		case .hostCommand(let commandID):
			return performHostCommand(commandID)
		case .save:
			saveRequested?()
			return true
		case .close:
			closeRequested?()
			return true
		case .normalMode:
			endInsertUndoGroup()
			vimEngine.replaceMode = false
			vimEngine.pendingReplacementCount = nil
			leaveVisualMode(collapse: true)
			keymapEngine.setMode(.normal)
			vimEngine.mode = .normal
			finishVimChangeRecording()
			return true
		case .insertMode:
			beginInsertUndoGroup()
			vimEngine.replaceMode = false
			keymapEngine.setMode(.insert)
			vimEngine.mode = .insert
			return true
		case .emacsMode:
			keymapEngine.setMode(.emacs)
			return true
		case .setMark(let mark):
			vimEngine.marks[mark] = Position(offset: editor.selections.primary.head)
			savePersistedVimMarks()
		case .jumpToMark(let mark):
			jumpToVimMark(mark, linewise: false)
		case .jumpToMarkLine(let mark):
			jumpToVimMark(mark, linewise: true)
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

	func enterInsertMode(at offset: Int) {
		let clamped = min(max(offset, 0), editor.rope.length)
		editor.setSelection(SelectionSet(primary: Selection(anchor: clamped, head: clamped)))
		beginInsertUndoGroup()
		keymapEngine.setMode(.insert)
		vimEngine.mode = .insert
		syncEditorState()
	}

	func firstNonWhitespaceOffsetInCurrentLine() -> Int {
		let range = currentLineRange(count: 1)
		let text = editor.rope.slice(range)
		var offset = range.lowerBound
		for character in text where character != "\n" {
			if !character.isWhitespace {
				return offset
			}
			offset += String(character).utf8.count
		}
		return lineEndOffsetInCurrentLine()
	}

	func lineEndOffsetInCurrentLine() -> Int {
		let range = currentLineRange(count: 1)
		let text = editor.rope.slice(range)
		guard let newline = text.firstIndex(of: "\n") else {
			return range.lowerBound + text.utf8.count
		}
		return range.lowerBound + text[..<newline].utf8.count
	}

	func vimAppendOffsetAfterCursor() -> Int {
		let head = editor.selections.primary.head
		let lineEnd = lineEndOffsetInCurrentLine()
		guard let character = characterOffsets().first(where: { $0.offset >= head }), character.offset < lineEnd else {
			return lineEnd
		}
		return min(lineEnd, currentOffsetAfter(character))
	}

	func openVimLine(after: Bool) {
		let range = currentLineRange(count: 1)
		let offset: Int
		if after {
			let text = editor.rope.slice(range)
			offset = text.hasSuffix("\n") ? max(range.lowerBound, range.upperBound - 1) : range.upperBound
		} else {
			offset = range.lowerBound
		}
		replace(range: offset ..< offset, with: "\n")
		enterInsertMode(at: after ? offset + 1 : offset)
		editorDidChange?(editor)
	}

	func deleteVimCharacter(backward: Bool, count: Int) {
		let range = vimCharacterRange(backward: backward, count: count)
		guard !range.isEmpty else {
			return
		}
		let text = editor.rope.slice(range)
		writeRegister(text, operation: .delete)
		replace(range: range, with: "")
		syncEditorState()
		editorDidChange?(editor)
		finishVimChangeRecording()
	}

	func deleteToLineEnd(change: Bool) {
		let head = editor.selections.primary.head
		let end = lineEndOffsetInCurrentLine()
		guard head < end else {
			if change { enterInsertMode(at: head) }
			return
		}
		let range = head ..< end
		writeRegister(editor.rope.slice(range), operation: .delete)
		replace(range: range, with: "")
		if change {
			enterInsertMode(at: head)
		} else {
			syncEditorState()
			finishVimChangeRecording()
		}
		editorDidChange?(editor)
	}

	func substituteVimCharacters(count: Int) {
		let range = vimCharacterRange(backward: false, count: count)
		guard !range.isEmpty else {
			return
		}
		writeRegister(editor.rope.slice(range), operation: .delete)
		replace(range: range, with: "")
		enterInsertMode(at: range.lowerBound)
		editorDidChange?(editor)
	}

	func joinVimLines() {
		let range = currentLineRange(count: 1)
		guard range.upperBound > range.lowerBound, editor.rope.slice(range).hasSuffix("\n") else {
			return
		}
		let newline = range.upperBound - 1
		let text = editorStorageString(editor)
		let suffix = text.utf8.dropFirst(min(text.utf8.count, newline + 1))
		let whitespaceCount = suffix.prefix { $0 == 32 || $0 == 9 }.count
		let replacement = newline > range.lowerBound && text.utf8[text.utf8.index(text.utf8.startIndex, offsetBy: newline - 1)] == 32 ? "" : " "
		replace(range: newline ..< min(editor.rope.length, newline + 1 + whitespaceCount), with: replacement)
		editor.setSelection(SelectionSet(primary: Selection(anchor: newline, head: newline)))
		syncEditorState()
		editorDidChange?(editor)
		finishVimChangeRecording()
	}

	func vimCharacterRange(backward: Bool, count: Int = 1) -> Range<Int> {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		let lineRange = editor.rope.lineRange(editor.rope.line(forOffset: head))
		let lineOffsets = offsets.filter { lineRange.contains($0.offset) && $0.character != "\n" }
		if backward {
			let selected = Array(lineOffsets.filter { $0.offset < head }.suffix(max(1, count)))
			guard let first = selected.first, let last = selected.last else { return head ..< head }
			return first.offset ..< currentOffsetAfter(last)
		}
		let selected = Array(lineOffsets.filter { $0.offset >= head }.prefix(max(1, count)))
		guard let first = selected.first, let last = selected.last else { return head ..< head }
		return first.offset ..< currentOffsetAfter(last)
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

	func deleteEmacsCharacter(backward: Bool) {
		let range = vimCharacterRange(backward: backward)
		guard !range.isEmpty else { return }
		replace(range: range, with: "")
		syncEditorState()
		editorDidChange?(editor)
	}

	func killEmacsLine() {
		let head = editor.selections.primary.head
		let lineRange = currentLineRange(count: 1)
		let lineEnd = lineEndOffsetInCurrentLine()
		let end = head < lineEnd ? lineEnd : min(lineRange.upperBound, editor.rope.length)
		guard head < end else { return }
		let range = head ..< end
		let text = editor.rope.slice(range)
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		replace(range: range, with: "")
		syncEditorState()
		editorDidChange?(editor)
	}

	func killEmacsWord(forward: Bool) {
		let start = editor.selections.primary.head
		var projected = editor
		projected.moveCursor(forward ? .wordForward : .wordBackward)
		let end = projected.selections.primary.head
		let range = min(start, end) ..< max(start, end)
		guard !range.isEmpty else { return }
		let text = editor.rope.slice(range)
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		replace(range: range, with: "")
		editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
		syncEditorState()
		editorDidChange?(editor)
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

	func extendEmacsMark(_ mark: Int, motion: Motion) {
		var projected = editor
		for _ in 0 ..< keymapRepeatCount {
			projected.moveCursor(motion)
		}
		let point = projected.selections.primary.head
		editor.setSelection(SelectionSet(primary: Selection(anchor: mark, head: point)))
		syncEditorState()
	}

	func transposeEmacsCharacters() {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		let rightIndex = offsets.firstIndex(where: { $0.offset >= head }) ?? max(0, offsets.count - 1)
		guard rightIndex > 0 else {
			return
		}
		let left = offsets[rightIndex - 1]
		let right = offsets[rightIndex]
		guard left.character != "\n", right.character != "\n" else { return }
		let end = right.offset + String(right.character).utf8.count
		replace(range: left.offset ..< end, with: String(right.character) + String(left.character))
		editor.setSelection(SelectionSet(primary: Selection(anchor: end, head: end)))
		syncEditorState()
		editorDidChange?(editor)
	}

	func transposeEmacsWords() {
		let text = editorStorageString(editor)
		let ranges = wordRanges(in: text)
		let point = editor.selections.primary.head
		let rightIndex = ranges.firstIndex(where: { $0.lowerBound >= point || $0.contains(point) }) ?? max(0, ranges.count - 1)
		guard rightIndex > 0 else {
			return
		}
		let leftRange = ranges[rightIndex - 1]
		let rightRange = ranges[rightIndex]
		let separator = textSlice(leftRange.upperBound ..< rightRange.lowerBound)
		let rightWord = textSlice(rightRange)
		let replacement = rightWord + separator + textSlice(leftRange)
		replace(range: leftRange.lowerBound ..< rightRange.upperBound, with: replacement)
		let pointAfterReplacement = leftRange.lowerBound + rightWord.utf8.count + separator.utf8.count
		editor.setSelection(SelectionSet(primary: Selection(anchor: pointAfterReplacement, head: pointAfterReplacement)))
		syncEditorState()
		editorDidChange?(editor)
	}

	func transformEmacsWord(using transform: (String) -> String) {
		let point = editor.selections.primary.head
		guard let range = wordRanges(in: editorStorageString(editor)).first(where: { $0.upperBound > point }) else {
			return
		}
		replace(range: range, with: transform(textSlice(range)))
		editor.setSelection(SelectionSet(primary: Selection(anchor: range.upperBound, head: range.upperBound)))
		syncEditorState()
		editorDidChange?(editor)
	}

	func wordRanges(in text: String) -> [Range<Int>] {
		var ranges: [Range<Int>] = []
		var start: Int?
		var offset = 0
		for character in text {
			let width = String(character).utf8.count
			if isTextObjectWordCharacter(character) {
				start = start ?? offset
		} else if let lower = start {
			ranges.append(lower ..< offset)
			start = nil
			}
			offset += width
		}
		if let start { ranges.append(start ..< offset) }
		return ranges
	}

	func textSlice(_ range: Range<Int>) -> String {
		editor.rope.slice(range)
	}

	func rectangleRanges() -> [Range<Int>] {
		let selection = editor.selections.primary
		let startLine = editor.rope.line(forOffset: selection.anchor)
		let endLine = editor.rope.line(forOffset: selection.head)
		let lowerLine = min(startLine, endLine)
		let upperLine = max(startLine, endLine)
		let lowerColumn = selection.anchor - editor.rope.offset(forLine: startLine)
		let upperColumn = selection.head - editor.rope.offset(forLine: endLine)
		let left = min(lowerColumn, upperColumn)
		let right = max(lowerColumn, upperColumn)
		return (lowerLine ... upperLine).map { line in
			let lineStart = editor.rope.offset(forLine: line)
			let lineEnd = lineContentEnd(for: line)
			return min(lineStart + left, lineEnd) ..< min(lineStart + right, lineEnd)
		}
	}

	func killEmacsRectangle() {
		let ranges = rectangleRanges()
		guard !ranges.isEmpty else { return }
		let text = ranges.map(textSlice).joined(separator: "\n")
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		for range in ranges.reversed() where !range.isEmpty {
			replace(range: range, with: "")
		}
		editor.setSelection(SelectionSet(primary: Selection(anchor: ranges[0].lowerBound, head: ranges[0].lowerBound)))
		syncEditorState()
		editorDidChange?(editor)
	}

	func yankEmacsRectangle() {
		guard let text = killRing.current ?? NSPasteboard.general.string(forType: .string) else { return }
		let lines = text.components(separatedBy: "\n")
		guard !lines.isEmpty else { return }
		let point = editor.selections.primary.head
		let startLine = editor.rope.line(forOffset: point)
		let column = point - editor.rope.offset(forLine: startLine)
		while editor.rope.lineCount < startLine + lines.count {
			replace(range: editor.rope.length ..< editor.rope.length, with: "\n")
		}
		for (index, line) in lines.enumerated().reversed() {
			let lineStart = editor.rope.offset(forLine: startLine + index)
			let lineEnd = editor.rope.lineRange(startLine + index).upperBound
			let insertion = min(lineStart + column, lineEnd)
			replace(range: insertion ..< insertion, with: line)
		}
		lastYankRange = point ..< point + lines[0].utf8.count
		syncEditorState()
		editorDidChange?(editor)
	}

	func stringEmacsRectangle() {
		guard let request = emacsRectangleStringRequested else { return }
		_ = request { [weak self] text in
			guard let self, let text else { return }
			self.applyEmacsRectangleString(text)
		}
	}

	func applyEmacsRectangleString(_ text: String) {
		let ranges = rectangleRanges()
		guard !ranges.isEmpty else { return }
		for range in ranges.reversed() {
			replace(range: range, with: text)
		}
		syncEditorState()
		editorDidChange?(editor)
	}

	func moveEmacsSexp(forward: Bool) {
		guard let destination = emacsSexpDestination(forward: forward) else {
			return
		}
		if let mark = emacsMark {
			editor.setSelection(SelectionSet(primary: Selection(anchor: mark, head: destination)))
		} else {
			editor.setSelection(SelectionSet(primary: Selection(anchor: destination, head: destination)))
		}
		syncEditorState()
	}

	func killEmacsSexp() {
		let start = editor.selections.primary.head
		guard let end = emacsSexpDestination(forward: true), start != end else {
			return
		}
		let range = min(start, end) ..< max(start, end)
		let text = textSlice(range)
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		replace(range: range, with: "")
		syncEditorState()
		editorDidChange?(editor)
	}

	func markEmacsSexp() {
		let start = editor.selections.primary.head
		guard let end = emacsSexpDestination(forward: true) else {
			return
		}
		emacsMark = start
		editor.setSelection(SelectionSet(primary: Selection(anchor: start, head: end)))
		syncEditorState()
	}

	func emacsSexpDestination(forward: Bool) -> Int? {
		let characters = characterOffsets()
		let point = editor.selections.primary.head
		guard !characters.isEmpty else { return 0 }
		if forward {
			guard let index = characters.firstIndex(where: { $0.offset >= point }) else { return editor.rope.length }
			let current = characters[index].character
			if let close = matchingDelimiter(for: current) {
				var depth = 0
				for entry in characters[index...] {
					if entry.character == current { depth += 1 }
					if entry.character == close {
						depth -= 1
						if depth == 0 { return entry.offset + String(entry.character).utf8.count }
					}
				}
			}
			if isTextObjectWordCharacter(current) {
				return characters[index...].first(where: { !isTextObjectWordCharacter($0.character) })?.offset ?? editor.rope.length
			}
			return min(editor.rope.length, currentOffsetAfter(characters[index]))
		}
		guard let index = characters.lastIndex(where: { $0.offset < point }) else { return 0 }
		let current = characters[index].character
		if let open = openingDelimiter(for: current) {
			var depth = 0
			for entry in characters[...index].reversed() {
				if entry.character == current { depth += 1 }
				if entry.character == open {
					depth -= 1
					if depth == 0 { return entry.offset }
				}
			}
		}
		if isTextObjectWordCharacter(current) {
			return characters[...index].reversed().first(where: { !isTextObjectWordCharacter($0.character) }).map { currentOffsetAfter($0) } ?? 0
		}
		return characters[index].offset
	}

	func currentOffsetAfter(_ entry: (offset: Int, character: Character)) -> Int {
		entry.offset + String(entry.character).utf8.count
	}

	func matchingDelimiter(for character: Character) -> Character? {
		switch character {
		case "(": return ")"
		case "[": return "]"
		case "{": return "}"
		default: return nil
		}
	}

	func openingDelimiter(for character: Character) -> Character? {
		switch character {
		case ")": return "("
		case "]": return "["
		case "}": return "{"
		default: return nil
		}
	}

	func addNextSelectionMatch() {
		guard allowsMultipleSelections else {
			return
		}
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
		case "vim.textObject.innerSentence":
			return .innerSentence
		case "vim.textObject.aroundSentence":
			return .aroundSentence
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
		case "vim.textObject.innerTag":
			return .innerTag
		case "vim.textObject.aroundTag":
			return .aroundTag
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
		guard let pendingOperator else {
			return
		}
		guard let range = textObjectRange(textObject) else {
			clearPendingOperator()
			return
		}
		clearPendingOperator()
		applyOperator(pendingOperator, range: range)
	}

	func applyLineOperator(_ op: VimOperator) {
		let range = currentLineRange(count: max(1, pendingOperatorCount * keymapRepeatCount))
		clearPendingOperator()
		applyOperator(op, range: range)
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
			syncEditorState()
			editorDidChange?(editor)
			finishVimChangeRecording()
		case .change:
			writeRegister(text, operation: .delete)
			replace(range: range, with: text.hasSuffix("\n") ? "\n" : "")
			editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			vimEngine.mode = .insert
			syncEditorState()
			editorDidChange?(editor)
		case .yank:
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
			syncEditorState()
			vimEngine.mode = .normal
		case .toggleCase, .lowercase, .uppercase:
			replace(range: range, with: transformCase(text, op: op))
			vimEngine.mode = .normal
			syncEditorState()
			editorDidChange?(editor)
			finishVimChangeRecording()
		case .indentRight, .indentLeft:
			applyIndentOperator(op, range: range)
			vimEngine.mode = .normal
			finishVimChangeRecording()
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
			finishVimChangeRecording()
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
		case .toggleCase, .lowercase, .uppercase:
			for range in selections.reversed() {
				replace(range: range, with: transformCase(editor.rope.slice(range), op: op))
			}
			syncEditorState()
			editorDidChange?(editor)
			finishVimChangeRecording()
		case .indentRight, .indentLeft:
			for range in selections.reversed() {
				applyIndentOperator(op, range: range)
			}
			finishVimChangeRecording()
		}
	}

	func currentLineIncludingNewline() -> Range<Int> {
		currentLineRange(count: 1)
	}

	func currentLineRange(count: Int) -> Range<Int> {
		let line = editor.rope.line(forOffset: editor.selections.primary.head)
		let start = editor.rope.offset(forLine: line)
		let endLine = min(max(line, line + count - 1), editor.rope.lineCount - 1)
		let end = endLine + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: endLine + 1) : editor.rope.length
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
		case 48:
			completePendingExCommand()
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
		if !replayingVimLastChange {
			vimPendingChangePrefixEvents.append(RecordedKey(event))
		}
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

	func stopEmacsMacroRecording() {
		guard recordingMacroRegister == "emacs" else {
			return
		}
		if currentMacroEvents.count >= 2 {
			currentMacroEvents.removeLast(2)
		}
		stopMacroRecording()
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
		finishVimChangeRecording()
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

	func jumpToVimMark(_ mark: Character, linewise: Bool) {
		guard let position = vimEngine.marks[mark] else {
			return
		}
		let offset = min(max(position.offset, 0), editor.rope.length)
		let target = linewise ? firstNonWhitespaceOffset(inLineContaining: offset) : offset
		editor.setSelection(SelectionSet(primary: Selection(anchor: target, head: target)))
		syncEditorState()
	}

	func firstNonWhitespaceOffset(inLineContaining offset: Int) -> Int {
		let clamped = min(max(offset, 0), editor.rope.length)
		let line = editor.rope.line(forOffset: clamped)
		let range = editor.rope.lineRange(line)
		let text = editor.rope.slice(range)
		var current = range.lowerBound
		for character in text where character != "\n" {
			if !character.isWhitespace {
				return current
			}
			current += String(character).utf8.count
		}
		return range.lowerBound
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
			let lineEnd = lineContentEnd(for: line)
			let lower = min(lineStart + lowerColumn, lineEnd)
			let upper = min(lineStart + upperColumn, lineEnd)
			return Selection(anchor: lower, head: upper)
		}
		return SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst()))
	}

	func lineContentEnd(for line: Int) -> Int {
		let range = editor.rope.lineRange(line)
		guard editor.rope.slice(range).hasSuffix("\n") else {
			return range.upperBound
		}
		return max(range.lowerBound, range.upperBound - 1)
	}

	func textObjectRange(_ textObject: TextObject) -> Range<Int>? {
		switch textObject {
		case .innerWord:
			return wordTextObjectRange(includeWhitespace: false)
		case .aroundWord:
			return wordTextObjectRange(includeWhitespace: true)
		case .innerSentence:
			return sentenceTextObjectRange(includeWhitespace: false)
		case .aroundSentence:
			return sentenceTextObjectRange(includeWhitespace: true)
		case .innerPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: false)
		case .aroundPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: true)
		case .innerParagraph:
			return paragraphTextObjectRange(includeBlankLine: false)
		case .aroundParagraph:
			return paragraphTextObjectRange(includeBlankLine: true)
		case .innerTag:
			return tagTextObjectRange(includeDelimiters: false)
		case .aroundTag:
			return tagTextObjectRange(includeDelimiters: true)
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

	func sentenceTextObjectRange(includeWhitespace: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return nil
		}
		let head = editor.selections.primary.head
		let currentIndex = offsets.lastIndex { $0.offset <= head } ?? 0
		let index: Int
		if offsets[currentIndex].character.isWhitespace,
		   let next = offsets[currentIndex...].firstIndex(where: { !$0.character.isWhitespace })
		{
			index = next
		} else {
			index = currentIndex
		}
		var lowerIndex = index
		while lowerIndex > 0, !isSentenceTerminator(offsets, at: lowerIndex - 1) {
			lowerIndex -= 1
		}
		while lowerIndex < offsets.count, offsets[lowerIndex].character.isWhitespace {
			lowerIndex += 1
		}
		guard lowerIndex < offsets.count else {
			return nil
		}
		var upperIndex = index
		while upperIndex < offsets.count, !isSentenceTerminator(offsets, at: upperIndex) {
			upperIndex += 1
		}
		if upperIndex < offsets.count {
			upperIndex += 1
		}
		if includeWhitespace {
			while upperIndex < offsets.count, offsets[upperIndex].character.isWhitespace {
				upperIndex += 1
			}
		}
		let lower = offsets[lowerIndex].offset
		let upper = upperIndex < offsets.count ? offsets[upperIndex].offset : editor.rope.length
		return lower ..< upper
	}

	func isSentenceTerminator(_ offsets: [(offset: Int, character: Character)], at index: Int) -> Bool {
		guard [".", "!", "?"].contains(offsets[index].character) else {
			return false
		}
		return index + 1 == offsets.count || offsets[index + 1].character.isWhitespace
	}

	func pairTextObjectRange(open: Character, close: Character, includeDelimiters: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		let indices: (open: Int, close: Int)?
		if open == close {
			var pendingOpen: Int?
			var pair: (open: Int, close: Int)?
			for index in offsets.indices where pair == nil {
				guard offsets[index].character == open, !isEscapedDelimiter(at: offsets[index].offset) else {
					continue
				}
				if let openIndex = pendingOpen {
					if offsets[openIndex].offset <= head, head <= offsets[index].offset {
						pair = (openIndex, index)
					}
					pendingOpen = nil
				} else {
					pendingOpen = index
				}
			}
			indices = pair
		} else {
			var stack: [Int] = []
			for index in offsets.indices where offsets[index].offset < head {
				if offsets[index].character == open {
					stack.append(index)
				} else if offsets[index].character == close, !stack.isEmpty {
					stack.removeLast()
				}
			}
			guard let openIndex = stack.last else {
				return nil
			}
			var depth = 0
			var closeIndex: Int?
			for index in offsets.indices.dropFirst(openIndex) {
				if offsets[index].character == open {
					depth += 1
				} else if offsets[index].character == close {
					depth -= 1
					if depth == 0 {
						closeIndex = index
						break
					}
				}
			}
			indices = closeIndex.map { (openIndex, $0) }
		}
		guard let indices else {
			return nil
		}
		let openIndex = indices.open
		let closeIndex = indices.close
		let openEnd = offsets[openIndex].offset + String(offsets[openIndex].character).utf8.count
		let closeEnd = offsets[closeIndex].offset + String(offsets[closeIndex].character).utf8.count
		return includeDelimiters ? offsets[openIndex].offset ..< closeEnd : openEnd ..< offsets[closeIndex].offset
	}

	func isEscapedDelimiter(at offset: Int) -> Bool {
		let bytes = Array(editorStorageString(editor).utf8)
		guard bytes.indices.contains(offset) else {
			return false
		}
		var count = 0
		var index = offset
		while index > 0, bytes[index - 1] == 92 {
			count += 1
			index -= 1
		}
		return !count.isMultiple(of: 2)
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

	func tagTextObjectRange(includeDelimiters: Bool) -> Range<Int>? {
		let text = editorStorageString(editor)
		guard !text.isEmpty,
		      let headIndex = String.Index(text.utf8.index(text.utf8.startIndex, offsetBy: min(editor.selections.primary.head, text.utf8.count)), within: text)
		else {
			return nil
		}
		struct OpenTag {
			var name: String
			var start: String.Index
			var end: String.Index
		}
		var stack: [OpenTag] = []
		var best: Range<String.Index>?
		var cursor = text.startIndex
		while let openStart = text[cursor...].firstIndex(of: "<"),
		      let openEnd = text[openStart...].firstIndex(of: ">") {
			let contentStart = text.index(after: openStart)
			let raw = String(text[contentStart ..< openEnd]).trimmingCharacters(in: .whitespacesAndNewlines)
			let closeEnd = text.index(after: openEnd)
			if raw.hasPrefix("!") || raw.hasPrefix("?") {
				cursor = closeEnd
				continue
			}
			if raw.hasPrefix("/") {
				if let name = tagName(in: String(raw.dropFirst())),
				   let matchIndex = stack.lastIndex(where: { $0.name == name }) {
					let opening = stack.remove(at: matchIndex)
					let around = opening.start ..< closeEnd
					if around.lowerBound <= headIndex, headIndex <= around.upperBound {
						let inner = opening.end ..< openStart
						let candidate = includeDelimiters ? around : inner
						if best == nil || utf8Range(candidate, in: text).count < utf8Range(best!, in: text).count {
							best = candidate
						}
					}
				}
			} else if !raw.hasSuffix("/"), let name = tagName(in: raw) {
				stack.append(OpenTag(name: name, start: openStart, end: closeEnd))
			}
			cursor = closeEnd
		}
		return best.map { utf8Range($0, in: text) }
	}

	func tagName(in raw: String) -> String? {
		var name = ""
		for scalar in raw.unicodeScalars.drop(while: { CharacterSet.whitespacesAndNewlines.contains($0) }) {
			if CharacterSet.alphanumerics.contains(scalar) || scalar == "-" || scalar == "_" || scalar == ":" {
				name.unicodeScalars.append(scalar)
			} else {
				break
			}
		}
		return name.isEmpty ? nil : name
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

	func extendSelection(motion: Motion) {
		let selection = editor.selections.primary
		var projected = editor
		projected.setSelection(SelectionSet(primary: Selection(anchor: selection.head, head: selection.head)))
		projected.moveCursor(motion)
		editor.setSelection(SelectionSet(primary: Selection(anchor: selection.anchor, head: projected.selections.primary.head)))
		syncEditorState()
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
		if let count = vimEngine.pendingReplacementCount,
		   let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1,
		   let replacement = key.value.first
		{
			vimEngine.pendingReplacementCount = nil
			replaceVimCharacters(with: replacement, count: count)
			return true
		}
		guard let motion = pendingCharacterMotion, let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1, let value = key.value.first else {
			return false
		}
		pendingCharacterMotion = nil
		moveToCharacter(value, motion: motion, count: keymapRepeatCount)
		lastCharacterMotion = (motion, value)
		return true
	}

	func replaceVimCharacters(with replacement: Character, count: Int) {
		let start = editor.selections.primary.head
		let lineEnd = lineEndOffsetInCurrentLine()
		let offsets = characterOffsets().filter { $0.offset >= start && $0.offset < lineEnd && $0.character != "\n" }
		guard let first = offsets.first else {
			return
		}
		let selected = Array(offsets.prefix(max(1, count)))
		guard let last = selected.last else {
			return
		}
		let end = last.offset + String(last.character).utf8.count
		replace(range: first.offset ..< end, with: String(repeating: String(replacement), count: selected.count))
		editor.setSelection(SelectionSet(primary: Selection(anchor: first.offset, head: first.offset)))
		syncEditorState()
		editorDidChange?(editor)
		finishVimChangeRecording()
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

	func toggleCaseAtCursor() {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		guard let current = offsets.first(where: { $0.offset >= head }) else {
			cancelVimChangeRecording()
			return
		}
		let end = current.offset + String(current.character).utf8.count
		replace(range: current.offset ..< end, with: transformCase(String(current.character), op: .toggleCase))
		syncEditorState()
		editorDidChange?(editor)
		finishVimChangeRecording()
	}

	func transformCase(_ text: String, op: VimOperator) -> String {
		switch op {
		case .lowercase:
			return text.lowercased()
		case .uppercase:
			return text.uppercased()
		case .toggleCase:
			var result = ""
			for character in text {
				let value = String(character)
				let lower = value.lowercased()
				let upper = value.uppercased()
				if lower == upper {
					result += value
				} else if value == upper {
					result += lower
				} else {
					result += upper
				}
			}
			return result
		default:
			return text
		}
	}

	func applyIndentOperator(_ op: VimOperator, range: Range<Int>) {
		let lineRange = lineRangeCovering(range)
		let text = editor.rope.slice(lineRange)
		var result = ""
		var cursor = text.startIndex
		while cursor < text.endIndex {
			let newline = text[cursor...].firstIndex(of: "\n")
			let end = newline ?? text.endIndex
			let line = String(text[cursor ..< end])
			result += op == .indentLeft ? outdentedLine(line) : "\t" + line
			if let newline {
				result.append("\n")
				cursor = text.index(after: newline)
			} else {
				cursor = text.endIndex
			}
		}
		replace(range: lineRange, with: result)
		editor.setSelection(SelectionSet(primary: Selection(anchor: lineRange.lowerBound, head: lineRange.lowerBound)))
		syncEditorState()
		editorDidChange?(editor)
	}

	func outdentedLine(_ line: String) -> String {
		if line.hasPrefix("\t") {
			return String(line.dropFirst())
		}
		var removeCount = 0
		for character in line.prefix(4) where character == " " {
			removeCount += 1
		}
		return String(line.dropFirst(removeCount))
	}

	func lineRangeCovering(_ range: Range<Int>) -> Range<Int> {
		let lowerLine = editor.rope.line(forOffset: range.lowerBound)
		let upperOffset = max(range.lowerBound, range.upperBound - 1)
		let upperLine = editor.rope.line(forOffset: upperOffset)
		let start = editor.rope.offset(forLine: lowerLine)
		let end = upperLine + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: upperLine + 1) : editor.rope.length
		return start ..< end
	}

	func completePendingExCommand() {
		guard let raw = pendingExCommand else {
			return
		}
		let hasColon = raw.hasPrefix(":")
		let prefix = hasColon ? String(raw.dropFirst()) : raw
		guard let match = exCommandCompletionsProvider?().sorted().first(where: { $0.hasPrefix(prefix) }) else {
			return
		}
		pendingExCommand = (hasColon ? ":" : "") + match
	}

	public func loadPersistedVimMarks() {
		guard let root = vimMarksWorkspaceRoot else {
			return
		}
		vimEngine.marks = vimMarkStore.load(workspaceRoot: root)
	}

	func savePersistedVimMarks() {
		guard let root = vimMarksWorkspaceRoot else {
			return
		}
		try? vimMarkStore.save(vimEngine.marks, workspaceRoot: root)
	}

	func recordVimKeymapPrefixEvent(_ event: NSEvent) {
		guard !replayingVimLastChange else {
			return
		}
		if vimCurrentChangeEvents != nil {
			recordVimChangeEvent(RecordedKey(event))
		} else {
			vimPendingChordEvents.append(RecordedKey(event))
		}
	}

	func prepareVimChangeRecording(for commandID: String, event: NSEvent) {
		guard !replayingVimLastChange else {
			return
		}
		let record = RecordedKey(event)
		if vimCurrentChangeEvents != nil {
			vimCurrentChangeEvents?.append(record)
			vimPendingChordEvents = []
			return
		}
		if commandID == "vim.registerPrefix" {
			vimPendingChangePrefixEvents = vimPendingChordEvents + [record]
			vimPendingChordEvents = []
			return
		}
		guard vimCommandStartsChange(commandID) else {
			vimPendingChordEvents = []
			return
		}
		vimCurrentChangeEvents = vimPendingChangePrefixEvents + vimPendingChordEvents + [record]
		vimCurrentChangeCount = max(1, keymapEngine.lastCommandCount)
		vimCurrentChangeRegister = pendingRegister
		vimPendingChangePrefixEvents = []
		vimPendingChordEvents = []
	}

	func vimCommandStartsChange(_ commandID: String) -> Bool {
		switch commandID {
		case "mode.insert", "vim.insert.lineStart", "vim.append.afterCursor", "vim.append.lineEnd", "vim.openLineBelow", "vim.openLineAbove",
		     "vim.operator.delete", "vim.operator.change", "vim.delete.char", "vim.delete.charBackward", "vim.delete.toLineEnd", "vim.change.toLineEnd", "vim.change.line", "vim.substitute.char", "vim.joinLines", "vim.replace.char", "vim.replace.mode",
		     "vim.case.toggle", "vim.case.toggleOperator", "vim.case.lowerOperator", "vim.case.upperOperator",
		     "vim.indent.right", "vim.indent.left", "vim.pasteAfter", "vim.pasteBefore":
			return true
		default:
			return false
		}
	}

	func recordVimChangeEvent(_ event: RecordedKey) {
		guard !replayingVimLastChange, vimCurrentChangeEvents != nil else {
			return
		}
		vimCurrentChangeEvents?.append(event)
	}

	func finishVimChangeRecording() {
		guard !replayingVimLastChange, let events = vimCurrentChangeEvents, !events.isEmpty else {
			return
		}
		vimEngine.lastChange = VimLastChange(events: events, count: vimCurrentChangeCount, register: vimCurrentChangeRegister)
		vimCurrentChangeEvents = nil
		vimCurrentChangeCount = 1
		vimCurrentChangeRegister = nil
	}

	func cancelVimChangeRecording() {
		vimCurrentChangeEvents = nil
		vimCurrentChangeCount = 1
		vimCurrentChangeRegister = nil
	}

	func replayLastChange() {
		guard !replayingVimLastChange, let change = vimEngine.lastChange, !change.events.isEmpty else {
			return
		}
		replayingVimLastChange = true
		defer { replayingVimLastChange = false }
		for event in change.events {
			_ = handleKey(
				characters: event.characters,
				charactersIgnoringModifiers: event.charactersIgnoringModifiers,
				keyCode: event.keyCode,
				modifierFlags: event.modifierFlags.appKitModifierFlags
			)
		}
	}

}

extension RecordedKey {
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
