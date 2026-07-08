// @file AppKit terminal viewport and input handling.
import AppKit
import Foundation
import ItsyConfig

enum TerminalViewCommand {
	case find
	case findNext
	case findPrevious
}

final class ItsyTerminalView: NSView {
	private let emulator: ItsyTerminalEmulator
	private var font = NSFont.monospacedSystemFont(ofSize: CGFloat(ItsySettings.TerminalSettings.defaultFontSize), weight: .regular)
	private var characterSize = CGSize(width: 7, height: 15)
	private var scrollbackOffset = 0
	private var trackingArea: NSTrackingArea?
	private var searchQuery = ""
	private var searchUsesRegex = false
	private var selectedSearchMatch = 0
	private var theme = AppTheme.palette.terminal
	var onInput: ((Data) -> Void)?
	var onResize: ((Int, Int) -> Void)?
	var onFocus: (() -> Void)?
	var onCommand: ((TerminalViewCommand) -> Bool)?

	init(emulator: ItsyTerminalEmulator = ItsyTerminalEmulator(), frame frameRect: NSRect = .zero) {
		self.emulator = emulator
		super.init(frame: frameRect)
		commonInit()
	}

	required init?(coder: NSCoder) {
		emulator = ItsyTerminalEmulator()
		super.init(coder: coder)
		commonInit()
	}

	override var acceptsFirstResponder: Bool {
		true
	}

	override func becomeFirstResponder() -> Bool {
		onFocus?()
		return true
	}

	override var isFlipped: Bool {
		true
	}

	var terminalSize: (columns: Int, rows: Int) {
		let columns = max(20, Int(bounds.width / max(characterSize.width, 1)))
		let rows = max(5, Int(bounds.height / max(characterSize.height, 1)))
		return (columns, rows)
	}

	func ingest(_ data: Data) {
		emulator.feed(data)
		refreshAfterEmulatorUpdate()
	}

	func refreshAfterEmulatorUpdate() {
		if emulator.alternateScreen {
			scrollbackOffset = 0
		}
		needsDisplay = true
	}

	func reset() {
		emulator.reset()
		scrollbackOffset = 0
		needsDisplay = true
	}

	func clearScrollback() {
		emulator.clearScrollback()
		scrollbackOffset = 0
		needsDisplay = true
	}

	var currentDirectoryURL: URL? {
		guard let raw = emulator.snapshot(scrollbackOffset: 0).currentDirectory,
		      let url = URL(string: raw),
		      url.isFileURL
		else {
			return nil
		}
		return url.standardizedFileURL
	}

	@discardableResult
	func setSearch(query: String, regex: Bool) -> Int {
		let shouldReset = searchQuery != query || searchUsesRegex != regex
		searchQuery = query
		searchUsesRegex = regex
		let count = searchMatches(in: emulator.snapshot(scrollbackOffset: scrollbackOffset)).count
		if shouldReset {
			selectedSearchMatch = 0
		} else if count > 0 {
			selectedSearchMatch = min(selectedSearchMatch, count - 1)
		}
		needsDisplay = true
		return count
	}

	@discardableResult
	func findNextSearchMatch() -> Int {
		let count = searchMatches(in: emulator.snapshot(scrollbackOffset: scrollbackOffset)).count
		guard count > 0 else {
			selectedSearchMatch = 0
			needsDisplay = true
			return 0
		}
		selectedSearchMatch = (selectedSearchMatch + 1) % count
		needsDisplay = true
		return selectedSearchMatch
	}

	@discardableResult
	func findPreviousSearchMatch() -> Int {
		let count = searchMatches(in: emulator.snapshot(scrollbackOffset: scrollbackOffset)).count
		guard count > 0 else {
			selectedSearchMatch = 0
			needsDisplay = true
			return 0
		}
		selectedSearchMatch = (selectedSearchMatch + count - 1) % count
		needsDisplay = true
		return selectedSearchMatch
	}

	func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings) {
		let settings = ItsySettings(terminal: settings).normalized().terminal
		font = NSFont.monospacedSystemFont(ofSize: CGFloat(settings.fontSize), weight: .regular)
		emulator.setMaxScrollbackLines(settings.scrollbackLines)
		measureCharacterSize()
		syncSize()
	}

	func applyTerminalTheme(_ theme: TerminalThemePalette) {
		self.theme = theme
		layer?.backgroundColor = theme.background.cgColor
		needsDisplay = true
	}

	override func setFrameSize(_ newSize: NSSize) {
		super.setFrameSize(newSize)
		syncSize()
	}

	override func viewDidMoveToWindow() {
		super.viewDidMoveToWindow()
		window?.makeFirstResponder(self)
		syncSize()
	}

	override func draw(_ dirtyRect: NSRect) {
		let snapshot = emulator.snapshot(scrollbackOffset: scrollbackOffset)
		let defaultBackground = color(for: snapshot.defaultBackground) ?? theme.background
		defaultBackground.setFill()
		dirtyRect.fill()
		for (row, cells) in snapshot.cells.enumerated() {
			drawBackgrounds(cells, row: row, snapshot: snapshot)
		}
		drawSearchHighlights(snapshot)
		for (row, cells) in snapshot.cells.enumerated() {
			attributedLine(cells, snapshot: snapshot).draw(at: NSPoint(x: 4, y: CGFloat(row) * characterSize.height + 2))
		}
		drawCursor(snapshot)
	}

	override func keyDown(with event: NSEvent) {
		if handleCommandShortcut(event) {
			return
		}
		guard let data = encodedInput(for: event) else {
			super.keyDown(with: event)
			return
		}
		scrollbackOffset = 0
		onInput?(data)
	}

	private func handleCommandShortcut(_ event: NSEvent) -> Bool {
		let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
		guard flags.contains(.command) else {
			return false
		}
		switch event.keyCode {
		case 3:
			return onCommand?(.find) ?? false
		case 5:
			return onCommand?(flags.contains(.shift) ? .findPrevious : .findNext) ?? false
		default:
			return false
		}
	}

	override func scrollWheel(with event: NSEvent) {
		if sendMouseWheel(event) {
			return
		}
		guard !emulator.alternateScreen else {
			return
		}
		let lineDelta = Int((event.scrollingDeltaY / max(characterSize.height, 1)).rounded(.toNearestOrAwayFromZero))
		scrollbackOffset = max(0, scrollbackOffset + lineDelta)
		needsDisplay = true
	}

	override func updateTrackingAreas() {
		super.updateTrackingAreas()
		if let trackingArea {
			removeTrackingArea(trackingArea)
		}
		let area = NSTrackingArea(rect: .zero, options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved], owner: self, userInfo: nil)
		addTrackingArea(area)
		trackingArea = area
	}

	override func mouseDown(with event: NSEvent) {
		if !sendMouse(event, button: 0, pressed: true) {
			super.mouseDown(with: event)
		}
	}

	override func mouseUp(with event: NSEvent) {
		if !sendMouse(event, button: 0, pressed: false) {
			super.mouseUp(with: event)
		}
	}

	override func rightMouseDown(with event: NSEvent) {
		if !sendMouse(event, button: 2, pressed: true) {
			super.rightMouseDown(with: event)
		}
	}

	override func rightMouseUp(with event: NSEvent) {
		if !sendMouse(event, button: 2, pressed: false) {
			super.rightMouseUp(with: event)
		}
	}

	override func otherMouseDown(with event: NSEvent) {
		if !sendMouse(event, button: 1, pressed: true) {
			super.otherMouseDown(with: event)
		}
	}

	override func otherMouseUp(with event: NSEvent) {
		if !sendMouse(event, button: 1, pressed: false) {
			super.otherMouseUp(with: event)
		}
	}

	override func mouseDragged(with event: NSEvent) {
		if !sendMouseMotion(event, button: 0) {
			super.mouseDragged(with: event)
		}
	}

	override func rightMouseDragged(with event: NSEvent) {
		if !sendMouseMotion(event, button: 2) {
			super.rightMouseDragged(with: event)
		}
	}

	override func otherMouseDragged(with event: NSEvent) {
		if !sendMouseMotion(event, button: 1) {
			super.otherMouseDragged(with: event)
		}
	}

	override func mouseMoved(with event: NSEvent) {
		if !sendMouseMotion(event, button: 3) {
			super.mouseMoved(with: event)
		}
	}

	@objc func paste(_ sender: Any?) {
		guard let text = NSPasteboard.general.string(forType: .string) else {
			return
		}
		if emulator.bracketedPaste {
			onInput?(Data("\u{1B}[200~\(text)\u{1B}[201~".utf8))
		} else {
			onInput?(Data(text.utf8))
		}
	}

	private func commonInit() {
		wantsLayer = true
		layer?.backgroundColor = theme.background.cgColor
		measureCharacterSize()
	}

	private func measureCharacterSize() {
		let sample = ("M" as NSString).size(withAttributes: [.font: font])
		characterSize = CGSize(width: ceil(sample.width), height: ceil(font.ascender - font.descender + font.leading + 2))
	}

	private func syncSize() {
		let size = terminalSize
		emulator.resize(columns: size.columns, rows: size.rows)
		onResize?(size.columns, size.rows)
		needsDisplay = true
	}

	private func drawCursor(_ snapshot: TerminalSnapshot) {
		guard window?.firstResponder === self, scrollbackOffset == 0 else {
			return
		}
		let x = 4 + CGFloat(min(snapshot.cursorColumn, emulator.columns - 1)) * characterSize.width
		let y = 2 + CGFloat(min(snapshot.cursorRow, emulator.rows - 1)) * characterSize.height
		let rect = NSRect(x: x, y: y, width: max(2, characterSize.width), height: characterSize.height)
		theme.cursor.withAlphaComponent(0.24).setFill()
		rect.fill()
	}

	private struct SearchMatch {
		var row: Int
		var column: Int
		var length: Int
	}

	private func drawSearchHighlights(_ snapshot: TerminalSnapshot) {
		let matches = searchMatches(in: snapshot)
		guard !matches.isEmpty else {
			return
		}
		selectedSearchMatch = min(selectedSearchMatch, matches.count - 1)
		for (index, match) in matches.enumerated() {
			let rect = NSRect(
				x: 4 + CGFloat(match.column) * characterSize.width,
				y: CGFloat(match.row) * characterSize.height + 2,
				width: CGFloat(max(1, match.length)) * characterSize.width,
				height: characterSize.height
			)
			(index == selectedSearchMatch ? NSColor.systemOrange : NSColor.systemYellow).withAlphaComponent(0.38).setFill()
			rect.fill()
		}
	}

	private func searchMatches(in snapshot: TerminalSnapshot) -> [SearchMatch] {
		guard !searchQuery.isEmpty else {
			return []
		}
		if searchUsesRegex {
			return regexSearchMatches(in: snapshot)
		}
		return literalSearchMatches(in: snapshot)
	}

	private func literalSearchMatches(in snapshot: TerminalSnapshot) -> [SearchMatch] {
		var matches: [SearchMatch] = []
		for (row, line) in snapshot.lines.enumerated() {
			var start = line.startIndex
			while let range = line.range(of: searchQuery, options: [.caseInsensitive], range: start ..< line.endIndex) {
				matches.append(SearchMatch(
					row: row,
					column: line.distance(from: line.startIndex, to: range.lowerBound),
					length: line.distance(from: range.lowerBound, to: range.upperBound)
				))
				start = range.upperBound
			}
		}
		return matches
	}

	private func regexSearchMatches(in snapshot: TerminalSnapshot) -> [SearchMatch] {
		guard let expression = try? NSRegularExpression(pattern: searchQuery, options: [.caseInsensitive]) else {
			return []
		}
		var matches: [SearchMatch] = []
		for (row, line) in snapshot.lines.enumerated() {
			let nsRange = NSRange(line.startIndex ..< line.endIndex, in: line)
			for match in expression.matches(in: line, range: nsRange) {
				guard let range = Range(match.range, in: line), !range.isEmpty else {
					continue
				}
				matches.append(SearchMatch(
					row: row,
					column: line.distance(from: line.startIndex, to: range.lowerBound),
					length: line.distance(from: range.lowerBound, to: range.upperBound)
				))
			}
		}
		return matches
	}

	private func drawBackgrounds(_ cells: [TerminalCell], row: Int, snapshot: TerminalSnapshot) {
		for (column, cell) in cells.enumerated() {
			guard let background = resolvedColors(for: cell.attributes, snapshot: snapshot).background else {
				continue
			}
			background.setFill()
			NSRect(
				x: 4 + CGFloat(column) * characterSize.width,
				y: CGFloat(row) * characterSize.height + 2,
				width: characterSize.width,
				height: characterSize.height
			).fill()
		}
	}

	private func attributedLine(_ cells: [TerminalCell], snapshot: TerminalSnapshot) -> NSAttributedString {
		let result = NSMutableAttributedString()
		for cell in cells {
			var attributes: [NSAttributedString.Key: Any] = [
				.font: font(for: cell.attributes),
				.foregroundColor: resolvedColors(for: cell.attributes, snapshot: snapshot).foreground,
			]
			if cell.attributes.underline || cell.attributes.hyperlink != nil {
				attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
			}
			result.append(NSAttributedString(string: String(cell.character), attributes: attributes))
		}
		return result
	}

	private func font(for attributes: TerminalTextAttributes) -> NSFont {
		var traits: NSFontTraitMask = []
		if attributes.bold {
			traits.insert(.boldFontMask)
		}
		if attributes.italic {
			traits.insert(.italicFontMask)
		}
		return NSFontManager.shared.convert(font, toHaveTrait: traits)
	}

	private func resolvedColors(for attributes: TerminalTextAttributes, snapshot: TerminalSnapshot) -> (foreground: NSColor, background: NSColor?) {
		var foreground = color(for: attributes.foreground, snapshot: snapshot) ?? color(for: snapshot.defaultForeground) ?? theme.foreground
		var background = color(for: attributes.background, snapshot: snapshot) ?? color(for: snapshot.defaultBackground)
		if attributes.inverse {
			let originalForeground = foreground
			foreground = background ?? theme.background
			background = originalForeground
		}
		return (foreground, background)
	}

	private func color(for terminalColor: TerminalColor?, snapshot: TerminalSnapshot) -> NSColor? {
		guard let terminalColor else {
			return nil
		}
		switch terminalColor {
		case let .ansi(index), let .indexed(index):
			if (0 ... 15).contains(index), !snapshot.paletteOverrideIndexes.contains(index), let themed = theme.ansi[index] {
				return themed
			}
			return color(for: snapshot.palette[index])
		case let .rgb(rgb):
			return color(for: rgb)
		}
	}

	private func color(for rgb: TerminalRGB?) -> NSColor? {
		guard let rgb else {
			return nil
		}
		return NSColor(
			srgbRed: CGFloat(max(0, min(255, rgb.red))) / 255,
			green: CGFloat(max(0, min(255, rgb.green))) / 255,
			blue: CGFloat(max(0, min(255, rgb.blue))) / 255,
			alpha: 1
		)
	}

	func encodedMouseInput(button: Int, row: Int, column: Int, pressed: Bool) -> Data? {
		encodedMouseInput(button: button, row: row, column: column, pressed: pressed, snapshot: emulator.snapshot(scrollbackOffset: 0))
	}

	private func encodedMouseInput(button: Int, row: Int, column: Int, pressed: Bool, snapshot: TerminalSnapshot) -> Data? {
		guard snapshot.mouseTrackingMode != .none else {
			return nil
		}
		let x = max(0, min(emulator.columns - 1, column)) + 1
		let y = max(0, min(emulator.rows - 1, row)) + 1
		if snapshot.sgrMouseMode {
			return Data("\u{1B}[<\(button);\(x);\(y)\(pressed ? "M" : "m")".utf8)
		}
		let legacyButton = pressed ? button : 3
		guard legacyButton <= 223, x <= 223, y <= 223 else {
			return nil
		}
		return Data([0x1B, 0x5B, 0x4D, UInt8(legacyButton + 32), UInt8(x + 32), UInt8(y + 32)])
	}

	@discardableResult
	private func sendMouse(_ event: NSEvent, button: Int, pressed: Bool) -> Bool {
		let snapshot = emulator.snapshot(scrollbackOffset: 0)
		guard snapshot.mouseTrackingMode != .none else {
			return false
		}
		let point = terminalCellLocation(for: event)
		let code = mouseButtonCode(button: button, event: event)
		guard let data = encodedMouseInput(button: code, row: point.row, column: point.column, pressed: pressed, snapshot: snapshot) else {
			return false
		}
		onInput?(data)
		return true
	}

	@discardableResult
	private func sendMouseMotion(_ event: NSEvent, button: Int) -> Bool {
		let snapshot = emulator.snapshot(scrollbackOffset: 0)
		guard snapshot.mouseTrackingMode == .button || snapshot.mouseTrackingMode == .any else {
			return false
		}
		if button == 3, snapshot.mouseTrackingMode != .any {
			return false
		}
		let point = terminalCellLocation(for: event)
		let code = mouseButtonCode(button: button, event: event) + 32
		guard let data = encodedMouseInput(button: code, row: point.row, column: point.column, pressed: true, snapshot: snapshot) else {
			return false
		}
		onInput?(data)
		return true
	}

	@discardableResult
	private func sendMouseWheel(_ event: NSEvent) -> Bool {
		let snapshot = emulator.snapshot(scrollbackOffset: 0)
		guard snapshot.mouseTrackingMode != .none, event.scrollingDeltaY != 0 else {
			return false
		}
		let point = terminalCellLocation(for: event)
		let base = event.scrollingDeltaY > 0 ? 64 : 65
		let code = base + mouseModifierCode(event)
		guard let data = encodedMouseInput(button: code, row: point.row, column: point.column, pressed: true, snapshot: snapshot) else {
			return false
		}
		onInput?(data)
		return true
	}

	private func terminalCellLocation(for event: NSEvent) -> (row: Int, column: Int) {
		let point = convert(event.locationInWindow, from: nil)
		let column = Int(((point.x - 4) / max(characterSize.width, 1)).rounded(.down))
		let row = Int(((point.y - 2) / max(characterSize.height, 1)).rounded(.down))
		return (
			row: max(0, min(emulator.rows - 1, row)),
			column: max(0, min(emulator.columns - 1, column))
		)
	}

	private func mouseButtonCode(button: Int, event: NSEvent) -> Int {
		max(0, min(3, button)) + mouseModifierCode(event)
	}

	private func mouseModifierCode(_ event: NSEvent) -> Int {
		let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
		var code = 0
		if flags.contains(.shift) {
			code += 4
		}
		if flags.contains(.option) {
			code += 8
		}
		if flags.contains(.control) {
			code += 16
		}
		return code
	}

	private func encodedInput(for event: NSEvent) -> Data? {
		let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
		if flags.contains(.command) {
			return nil
		}
		if flags.contains(.control), let scalar = event.charactersIgnoringModifiers?.unicodeScalars.first {
			switch scalar.value {
			case 64 ... 95:
				return Data([UInt8(scalar.value - 64)])
			case 97 ... 122:
				return Data([UInt8(scalar.value - 96)])
			default:
				break
			}
		}
		if let special = encodedSpecialKey(event) {
			return special
		}
		guard let characters = event.characters, !characters.isEmpty else {
			return nil
		}
		if flags.contains(.option) {
			return Data("\u{1B}\(characters)".utf8)
		}
		return Data(characters.utf8)
	}

	private func encodedSpecialKey(_ event: NSEvent) -> Data? {
		switch event.keyCode {
		case 36:
			return Data([13])
		case 48:
			return Data([9])
		case 51:
			return Data([127])
		case 53:
			return Data([27])
		case 117:
			return Data("\u{1B}[3~".utf8)
		case 115:
			return Data("\u{1B}[1~".utf8)
		case 119:
			return Data("\u{1B}[4~".utf8)
		case 116:
			return Data("\u{1B}[5~".utf8)
		case 121:
			return Data("\u{1B}[6~".utf8)
		case 123:
			return Data("\u{1B}[D".utf8)
		case 124:
			return Data("\u{1B}[C".utf8)
		case 125:
			return Data("\u{1B}[B".utf8)
		case 126:
			return Data("\u{1B}[A".utf8)
		case 122:
			return Data("\u{1B}OP".utf8)
		case 120:
			return Data("\u{1B}OQ".utf8)
		case 99:
			return Data("\u{1B}OR".utf8)
		case 118:
			return Data("\u{1B}OS".utf8)
		case 96:
			return Data("\u{1B}[15~".utf8)
		case 97:
			return Data("\u{1B}[17~".utf8)
		case 98:
			return Data("\u{1B}[18~".utf8)
		case 100:
			return Data("\u{1B}[19~".utf8)
		case 101:
			return Data("\u{1B}[20~".utf8)
		case 109:
			return Data("\u{1B}[21~".utf8)
		case 103:
			return Data("\u{1B}[23~".utf8)
		case 111:
			return Data("\u{1B}[24~".utf8)
		default:
			return nil
		}
	}
}
