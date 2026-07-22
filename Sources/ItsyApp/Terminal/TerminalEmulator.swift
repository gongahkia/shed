// @file terminal escape parser and screen snapshot state.
import Foundation

struct TerminalRGB: Equatable {
	var red: Int
	var green: Int
	var blue: Int
}

enum TerminalColor: Equatable {
	case ansi(Int)
	case indexed(Int)
	case rgb(TerminalRGB)
}

struct TerminalTextAttributes: Equatable {
	var foreground: TerminalColor?
	var background: TerminalColor?
	var bold = false
	var italic = false
	var underline = false
	var inverse = false
	var hyperlink: String?
}

struct TerminalCell: Equatable {
	var character: Character
	var attributes: TerminalTextAttributes
	var isContinuation = false
}

enum TerminalMouseTrackingMode: Equatable {
	case none
	case normal
	case button
	case any
}

struct TerminalSnapshot {
	var lines: [String]
	var cells: [[TerminalCell]]
	var cursorRow: Int
	var cursorColumn: Int
	var cursorVisible: Bool
	var alternateScreen: Bool
	var bracketedPaste: Bool
	var windowTitle: String?
	var currentDirectory: String?
	var promptMark: String?
	var mouseTrackingMode: TerminalMouseTrackingMode
	var sgrMouseMode: Bool
	var palette: [Int: TerminalRGB]
	var paletteOverrideIndexes: Set<Int>
	var defaultForeground: TerminalRGB?
	var defaultBackground: TerminalRGB?
}

final class ItsyTerminalEmulator {
	private enum State {
		case ground
		case escape
		case csi(String)
		case osc(String)
		case oscEscape(String)
	}

	private(set) var columns: Int
	private(set) var rows: Int
	private var maxScrollbackLines: Int
	private var screen: [[TerminalCell]]
	private var normalScreen: [[TerminalCell]] = []
	private var normalCursor = (row: 0, column: 0)
	private var normalAttributes = TerminalTextAttributes()
	private var history: [[TerminalCell]] = []
	private var cursorRow = 0
	private var cursorColumn = 0
	private var savedCursor = (row: 0, column: 0)
	private var scrollTop = 0
	private var scrollBottom: Int
	private var state = State.ground
	private var currentAttributes = TerminalTextAttributes()
	private var currentHyperlink: String?
	private(set) var alternateScreen = false
	private(set) var cursorVisible = true
	private(set) var bracketedPaste = false
	private(set) var windowTitle: String?
	private(set) var currentDirectory: String?
	private(set) var promptMark: String?
	private(set) var mouseTrackingMode: TerminalMouseTrackingMode = .none
	private(set) var sgrMouseMode = false
	private(set) var palette = ItsyTerminalEmulator.xtermPalette()
	private(set) var paletteOverrideIndexes: Set<Int> = []
	private(set) var defaultForeground: TerminalRGB?
	private(set) var defaultBackground: TerminalRGB?

	init(columns: Int = 80, rows: Int = 24, maxScrollbackLines: Int = 10_000) {
		self.columns = max(1, columns)
		self.rows = max(1, rows)
		self.maxScrollbackLines = max(0, maxScrollbackLines)
		screen = Self.blankScreen(columns: self.columns, rows: self.rows)
		scrollBottom = self.rows - 1
	}

	func resize(columns newColumns: Int, rows newRows: Int) {
		let newColumns = max(1, newColumns)
		let newRows = max(1, newRows)
		if newColumns == columns, newRows == rows {
			return
		}
		columns = newColumns
		rows = newRows
		screen = resizedScreen(screen, columns: newColumns, rows: newRows)
		normalScreen = resizedScreen(normalScreen, columns: newColumns, rows: newRows)
		normalCursor = (min(normalCursor.row, rows - 1), min(normalCursor.column, columns - 1))
		cursorRow = min(cursorRow, rows - 1)
		cursorColumn = min(cursorColumn, columns - 1)
		scrollTop = 0
		scrollBottom = rows - 1
	}

	func reset() {
		screen = Self.blankScreen(columns: columns, rows: rows)
		normalScreen = []
		normalCursor = (0, 0)
		normalAttributes = TerminalTextAttributes()
		history.removeAll(keepingCapacity: true)
		cursorRow = 0
		cursorColumn = 0
		savedCursor = (0, 0)
		scrollTop = 0
		scrollBottom = rows - 1
		state = .ground
		currentAttributes = TerminalTextAttributes()
		currentHyperlink = nil
		alternateScreen = false
		cursorVisible = true
		bracketedPaste = false
		windowTitle = nil
		currentDirectory = nil
		promptMark = nil
		mouseTrackingMode = .none
		sgrMouseMode = false
		palette = Self.xtermPalette()
		paletteOverrideIndexes.removeAll(keepingCapacity: true)
		defaultForeground = nil
		defaultBackground = nil
	}

	func clearScrollback() {
		history.removeAll(keepingCapacity: true)
	}

	func setMaxScrollbackLines(_ lines: Int) {
		maxScrollbackLines = max(0, lines)
		if history.count > maxScrollbackLines {
			history.removeFirst(history.count - maxScrollbackLines)
		}
	}

	func feed(_ data: Data) {
		let string = String(decoding: data, as: UTF8.self)
		for scalar in string.unicodeScalars {
			feed(scalar)
		}
	}

	func snapshot(scrollbackOffset: Int) -> TerminalSnapshot {
		let current = screen
		let visibleCells: [[TerminalCell]]
		if alternateScreen {
			visibleCells = current
		} else {
			let offset = max(0, min(scrollbackOffset, history.count))
			if offset == 0 {
				visibleCells = current
			} else {
				let start = max(0, history.count - offset)
				let end = min(history.count, start + rows)
				var lines = Array(history[start ..< end])
				if lines.count < rows {
					lines += current.prefix(rows - lines.count)
				}
				visibleCells = lines
			}
		}
		return TerminalSnapshot(
			lines: visibleCells.map(Self.string(from:)),
			cells: visibleCells,
			cursorRow: cursorRow,
			cursorColumn: cursorColumn,
			cursorVisible: cursorVisible,
			alternateScreen: alternateScreen,
			bracketedPaste: bracketedPaste,
			windowTitle: windowTitle,
			currentDirectory: currentDirectory,
			promptMark: promptMark,
			mouseTrackingMode: mouseTrackingMode,
			sgrMouseMode: sgrMouseMode,
			palette: palette,
			paletteOverrideIndexes: paletteOverrideIndexes,
			defaultForeground: defaultForeground,
			defaultBackground: defaultBackground
		)
	}

	private func feed(_ scalar: UnicodeScalar) {
		switch state {
		case .ground:
			handleGround(scalar)
		case .escape:
			handleEscape(scalar)
		case let .csi(buffer):
			handleCSI(scalar, buffer: buffer)
		case let .osc(buffer):
			if scalar.value == 0x07 {
				applyOSC(buffer)
				state = .ground
			} else if scalar.value == 0x1B {
				state = .oscEscape(buffer)
			} else if buffer.unicodeScalars.count >= 4096 {
				recordUnsupportedSequence()
				state = .ground
			} else {
				state = .osc(appendingOSC(scalar, to: buffer))
			}
		case let .oscEscape(buffer):
			if scalar == "\\" {
				applyOSC(buffer)
				state = .ground
			} else {
				state = .osc(appendingOSC(scalar, to: buffer))
			}
		}
	}

	private func handleGround(_ scalar: UnicodeScalar) {
		switch scalar.value {
		case 0x07:
			return
		case 0x08:
			cursorColumn = max(0, cursorColumn - 1)
		case 0x09:
			cursorColumn = min(columns - 1, ((cursorColumn / 8) + 1) * 8)
		case 0x0A, 0x0B, 0x0C:
			lineFeed()
		case 0x0D:
			cursorColumn = 0
		case 0x1B:
			state = .escape
		default:
			put(Character(scalar))
		}
	}

	private func handleEscape(_ scalar: UnicodeScalar) {
		switch scalar {
		case "[":
			state = .csi("")
		case "]":
			state = .osc("")
		case "7":
			saveCursor()
			state = .ground
		case "8":
			restoreCursor()
			state = .ground
		case "c":
			reset()
			state = .ground
		case "D":
			lineFeed()
			state = .ground
		case "M":
			reverseLineFeed()
			state = .ground
		case "E":
			cursorColumn = 0
			lineFeed()
			state = .ground
		default:
			recordUnsupportedSequence()
			state = .ground
		}
	}

	private func handleCSI(_ scalar: UnicodeScalar, buffer: String) {
		if scalar.value >= 0x40, scalar.value <= 0x7E {
			applyCSI(buffer, final: Character(scalar))
			state = .ground
		} else if buffer.unicodeScalars.count >= 256 {
			recordUnsupportedSequence()
			state = .ground
		} else {
			state = .csi(buffer + String(scalar))
		}
	}

	private func applyCSI(_ buffer: String, final: Character) {
		let privateMode = buffer.hasPrefix("?")
		let body = privateMode ? String(buffer.dropFirst()) : buffer
		let params = body.split(separator: ";", omittingEmptySubsequences: false).map { Int($0) ?? 0 }
		func value(_ index: Int, default defaultValue: Int) -> Int {
			guard index < params.count, params[index] > 0 else {
				return defaultValue
			}
			return params[index]
		}
		switch final {
		case "A":
			cursorRow = max(scrollTop, cursorRow - value(0, default: 1))
		case "B":
			cursorRow = min(scrollBottom, cursorRow + value(0, default: 1))
		case "C":
			cursorColumn = min(columns - 1, cursorColumn + value(0, default: 1))
		case "D":
			cursorColumn = max(0, cursorColumn - value(0, default: 1))
		case "E":
			cursorRow = min(rows - 1, cursorRow + value(0, default: 1))
			cursorColumn = 0
		case "F":
			cursorRow = max(0, cursorRow - value(0, default: 1))
			cursorColumn = 0
		case "G":
			cursorColumn = min(columns - 1, value(0, default: 1) - 1)
		case "H", "f":
			cursorRow = min(rows - 1, value(0, default: 1) - 1)
			cursorColumn = min(columns - 1, value(1, default: 1) - 1)
		case "J":
			eraseDisplay(value(0, default: 0))
		case "K":
			eraseLine(value(0, default: 0))
		case "L":
			insertLines(value(0, default: 1))
		case "M":
			deleteLines(value(0, default: 1))
		case "P":
			deleteCharacters(value(0, default: 1))
		case "S":
			scrollUp(value(0, default: 1))
		case "T":
			scrollDown(value(0, default: 1))
		case "X":
			eraseCharacters(value(0, default: 1))
		case "@":
			insertCharacters(value(0, default: 1))
		case "d":
			cursorRow = min(rows - 1, value(0, default: 1) - 1)
		case "m":
			applySGR(params)
		case "r":
			let top = value(0, default: 1) - 1
			let bottom = value(1, default: rows) - 1
			if top >= 0, bottom > top, bottom < rows {
				scrollTop = top
				scrollBottom = bottom
				cursorRow = scrollTop
				cursorColumn = 0
			}
		case "s":
			saveCursor()
		case "u":
			restoreCursor()
		case "h":
			if privateMode {
				setPrivateModes(params, enabled: true)
			} else {
				recordUnsupportedSequence()
			}
		case "l":
			if privateMode {
				setPrivateModes(params, enabled: false)
			} else {
				recordUnsupportedSequence()
			}
		default:
			recordUnsupportedSequence()
		}
	}

	private func applySGR(_ rawParams: [Int]) {
		let params = rawParams.isEmpty ? [0] : rawParams
		var index = 0
		while index < params.count {
			let code = params[index]
			switch code {
			case 0:
				currentAttributes = TerminalTextAttributes()
			case 1:
				currentAttributes.bold = true
			case 3:
				currentAttributes.italic = true
			case 4:
				currentAttributes.underline = true
			case 7:
				currentAttributes.inverse = true
			case 22:
				currentAttributes.bold = false
			case 23:
				currentAttributes.italic = false
			case 24:
				currentAttributes.underline = false
			case 27:
				currentAttributes.inverse = false
			case 30 ... 37:
				currentAttributes.foreground = .ansi(code - 30)
			case 39:
				currentAttributes.foreground = nil
			case 40 ... 47:
				currentAttributes.background = .ansi(code - 40)
			case 49:
				currentAttributes.background = nil
			case 90 ... 97:
				currentAttributes.foreground = .ansi(code - 90 + 8)
			case 100 ... 107:
				currentAttributes.background = .ansi(code - 100 + 8)
			case 38, 48:
				index += applyExtendedColor(params, at: index)
			default:
				break
			}
			index += 1
		}
	}

	private func applyExtendedColor(_ params: [Int], at index: Int) -> Int {
		guard index + 2 < params.count else {
			return 0
		}
		let targetForeground = params[index] == 38
		switch params[index + 1] {
		case 5:
			let color = TerminalColor.indexed(max(0, min(255, params[index + 2])))
			setColor(color, foreground: targetForeground)
			return 2
		case 2:
			guard index + 4 < params.count else {
				return 0
			}
			let rgb = TerminalRGB(
				red: max(0, min(255, params[index + 2])),
				green: max(0, min(255, params[index + 3])),
				blue: max(0, min(255, params[index + 4]))
			)
			setColor(.rgb(rgb), foreground: targetForeground)
			return 4
		default:
			return 0
		}
	}

	private func setColor(_ color: TerminalColor, foreground: Bool) {
		if foreground {
			currentAttributes.foreground = color
		} else {
			currentAttributes.background = color
		}
	}

	private func currentCellAttributes() -> TerminalTextAttributes {
		var attributes = currentAttributes
		attributes.hyperlink = currentHyperlink
		return attributes
	}

	private func setPrivateModes(_ modes: [Int], enabled: Bool) {
		for mode in modes {
			switch mode {
			case 25:
				cursorVisible = enabled
			case 1000:
				mouseTrackingMode = enabled ? .normal : disabledMouseMode(.normal)
			case 1002:
				mouseTrackingMode = enabled ? .button : disabledMouseMode(.button)
			case 1003:
				mouseTrackingMode = enabled ? .any : disabledMouseMode(.any)
			case 1006:
				sgrMouseMode = enabled
			case 47, 1047, 1049:
				setAlternateScreen(enabled)
			case 1048:
				if enabled {
					saveCursor()
				} else {
					restoreCursor()
				}
			case 2004:
				bracketedPaste = enabled
			default:
				recordUnsupportedSequence()
			}
		}
	}

	private func disabledMouseMode(_ mode: TerminalMouseTrackingMode) -> TerminalMouseTrackingMode {
		mouseTrackingMode == mode ? .none : mouseTrackingMode
	}

	private func setAlternateScreen(_ enabled: Bool) {
		guard alternateScreen != enabled else {
			return
		}
		if enabled {
			normalScreen = screen
			normalCursor = (cursorRow, cursorColumn)
			normalAttributes = currentAttributes
			screen = Self.blankScreen(columns: columns, rows: rows)
			cursorRow = 0
			cursorColumn = 0
		} else {
			screen = normalScreen.isEmpty ? Self.blankScreen(columns: columns, rows: rows) : normalScreen
			normalScreen = []
			cursorRow = min(normalCursor.row, rows - 1)
			cursorColumn = min(normalCursor.column, columns - 1)
			currentAttributes = normalAttributes
		}
		alternateScreen = enabled
		scrollTop = 0
		scrollBottom = rows - 1
	}

	private func applyOSC(_ buffer: String) {
		let parts = buffer.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
		guard let rawCode = parts.first else {
			return
		}
		let code = String(rawCode)
		let payload = parts.count > 1 ? String(parts[1]) : ""
		switch code {
		case "0", "2":
			windowTitle = sanitizedOSCText(payload, maxLength: 512)
		case "4":
			applyPaletteOSC(payload)
		case "7":
			currentDirectory = sanitizedOSCText(payload, maxLength: 2048)
		case "8":
			applyHyperlinkOSC(payload)
		case "10":
			defaultForeground = parseOSCColor(payload)
		case "11":
			defaultBackground = parseOSCColor(payload)
		case "52":
			return
		case "133":
			promptMark = sanitizedOSCText(payload, maxLength: 512)
		default:
			recordUnsupportedSequence()
		}
	}

	private func applyPaletteOSC(_ payload: String) {
		let parts = payload.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
		var index = 0
		while index + 1 < parts.count {
			if let paletteIndex = Int(parts[index]), (0 ... 255).contains(paletteIndex), let color = parseOSCColor(parts[index + 1]) {
				palette[paletteIndex] = color
				paletteOverrideIndexes.insert(paletteIndex)
			}
			index += 2
		}
	}

	private func applyHyperlinkOSC(_ payload: String) {
		let parts = payload.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
		let rawURL = parts.count > 1 ? String(parts[1]) : ""
		let url = sanitizedOSCText(rawURL, maxLength: 2048)
		currentHyperlink = url.isEmpty ? nil : url
	}

	private func parseOSCColor(_ rawValue: String) -> TerminalRGB? {
		let value = sanitizedOSCText(rawValue, maxLength: 64)
		if value == "?" {
			return nil
		}
		if value.hasPrefix("#") {
			return parseHexRGB(String(value.dropFirst()))
		}
		if value.lowercased().hasPrefix("rgb:") {
			let components = value.dropFirst(4).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
			guard components.count == 3 else {
				return nil
			}
			guard
				let red = scaledHexComponent(components[0]),
				let green = scaledHexComponent(components[1]),
				let blue = scaledHexComponent(components[2])
			else {
				return nil
			}
			return TerminalRGB(red: red, green: green, blue: blue)
		}
		return nil
	}

	private func parseHexRGB(_ hex: String) -> TerminalRGB? {
		guard hex.count == 6 else {
			return nil
		}
		let parts = stride(from: 0, to: 6, by: 2).map { offset -> String in
			let start = hex.index(hex.startIndex, offsetBy: offset)
			let end = hex.index(start, offsetBy: 2)
			return String(hex[start ..< end])
		}
		guard
			let red = Int(parts[0], radix: 16),
			let green = Int(parts[1], radix: 16),
			let blue = Int(parts[2], radix: 16)
		else {
			return nil
		}
		return TerminalRGB(red: red, green: green, blue: blue)
	}

	private func scaledHexComponent(_ hex: String) -> Int? {
		guard (1 ... 4).contains(hex.count), let value = Int(hex, radix: 16) else {
			return nil
		}
		let maxValue = (1 << (hex.count * 4)) - 1
		return max(0, min(255, value * 255 / maxValue))
	}

	private func appendingOSC(_ scalar: UnicodeScalar, to buffer: String) -> String {
		return buffer + String(scalar)
	}

	private func sanitizedOSCText(_ value: String, maxLength: Int) -> String {
		let scalars = value.unicodeScalars.filter { scalar in
			scalar.value >= 0x20 && scalar.value != 0x7F
		}.prefix(maxLength)
		return String(String.UnicodeScalarView(scalars))
	}

	private func recordUnsupportedSequence() {
		put("�")
	}

	private func appendToPreviousCell(_ character: Character) {
		var row = cursorRow
		var column = min(cursorColumn - 1, columns - 1)
		if column < 0, row > 0 {
			row -= 1
			column = columns - 1
		}
		while column >= 0, screen[row][column].isContinuation {
			column -= 1
		}
		guard column >= 0 else {
			return
		}
		let existing = screen[row][column]
		guard existing.character != " " else {
			return
		}
		screen[row][column].character = Character(String(existing.character) + String(character))
	}

	private func displayWidth(of character: Character) -> Int {
		guard let scalar = character.unicodeScalars.first else {
			return 0
		}
		let value = scalar.value
		if (0x0300 ... 0x036F).contains(value) || (0x1AB0 ... 0x1AFF).contains(value) ||
			(0x1DC0 ... 0x1DFF).contains(value) || (0x20D0 ... 0x20FF).contains(value) ||
			(0xFE00 ... 0xFE0F).contains(value) || value == 0x200D || (0x1F3FB ... 0x1F3FF).contains(value)
		{
			return 0
		}
		if (0x1100 ... 0x115F).contains(value) || (0x2329 ... 0x232A).contains(value) ||
			(0x2E80 ... 0xA4CF).contains(value) || (0xAC00 ... 0xD7A3).contains(value) ||
			(0xF900 ... 0xFAFF).contains(value) || (0xFE10 ... 0xFE19).contains(value) ||
			(0xFE30 ... 0xFE6F).contains(value) || (0xFF00 ... 0xFF60).contains(value) ||
			(0xFFE0 ... 0xFFE6).contains(value) || (0x1F300 ... 0x1FAFF).contains(value)
		{
			return 2
		}
		return 1
	}

	private func put(_ character: Character) {
		let width = displayWidth(of: character)
		if width == 0 {
			appendToPreviousCell(character)
			return
		}
		if cursorColumn >= columns || cursorColumn + width > columns {
			cursorColumn = 0
			lineFeed()
		}
		screen[cursorRow][cursorColumn] = TerminalCell(character: character, attributes: currentCellAttributes())
		if width == 2, cursorColumn + 1 < columns {
			screen[cursorRow][cursorColumn + 1] = TerminalCell(character: " ", attributes: currentCellAttributes(), isContinuation: true)
		}
		cursorColumn += width
		if cursorColumn >= columns {
			cursorColumn = columns
		}
	}

	private func lineFeed() {
		if cursorRow == scrollBottom {
			scrollUp(1)
		} else {
			cursorRow = min(rows - 1, cursorRow + 1)
		}
	}

	private func reverseLineFeed() {
		if cursorRow == scrollTop {
			scrollDown(1)
		} else {
			cursorRow = max(0, cursorRow - 1)
		}
	}

	private func scrollUp(_ count: Int) {
		let count = min(max(1, count), scrollBottom - scrollTop + 1)
		for _ in 0 ..< count {
			if !alternateScreen, scrollTop == 0, scrollBottom == rows - 1 {
				appendHistory(screen[0])
			}
			screen.remove(at: scrollTop)
			screen.insert(Self.blankLine(columns: columns, attributes: currentCellAttributes()), at: scrollBottom)
		}
	}

	private func scrollDown(_ count: Int) {
		let count = min(max(1, count), scrollBottom - scrollTop + 1)
		for _ in 0 ..< count {
			screen.remove(at: scrollBottom)
			screen.insert(Self.blankLine(columns: columns, attributes: currentCellAttributes()), at: scrollTop)
		}
	}

	private func insertLines(_ count: Int) {
		guard cursorRow >= scrollTop, cursorRow <= scrollBottom else {
			return
		}
		let count = min(max(1, count), scrollBottom - cursorRow + 1)
		for _ in 0 ..< count {
			screen.remove(at: scrollBottom)
			screen.insert(Self.blankLine(columns: columns, attributes: currentCellAttributes()), at: cursorRow)
		}
	}

	private func deleteLines(_ count: Int) {
		guard cursorRow >= scrollTop, cursorRow <= scrollBottom else {
			return
		}
		let count = min(max(1, count), scrollBottom - cursorRow + 1)
		for _ in 0 ..< count {
			screen.remove(at: cursorRow)
			screen.insert(Self.blankLine(columns: columns, attributes: currentCellAttributes()), at: scrollBottom)
		}
	}

	private func insertCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		var line = screen[cursorRow]
		line.insert(contentsOf: Self.blankLine(columns: count, attributes: currentCellAttributes()), at: cursorColumn)
		screen[cursorRow] = Array(line.prefix(columns))
	}

	private func deleteCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		var line = screen[cursorRow]
		line.removeSubrange(cursorColumn ..< cursorColumn + count)
		line.append(contentsOf: Self.blankLine(columns: count, attributes: currentCellAttributes()))
		screen[cursorRow] = line
	}

	private func eraseCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		for column in cursorColumn ..< cursorColumn + count {
			screen[cursorRow][column] = Self.blankCell(attributes: currentCellAttributes())
		}
	}

	private func eraseDisplay(_ mode: Int) {
		switch mode {
		case 0:
			eraseLine(0)
			if cursorRow + 1 < rows {
				for row in cursorRow + 1 ..< rows {
					screen[row] = Self.blankLine(columns: columns, attributes: currentCellAttributes())
				}
			}
		case 1:
			if cursorRow > 0 {
				for row in 0 ..< cursorRow {
					screen[row] = Self.blankLine(columns: columns, attributes: currentCellAttributes())
				}
			}
			eraseLine(1)
		case 2, 3:
			screen = Self.blankScreen(columns: columns, rows: rows, attributes: currentCellAttributes())
			if mode == 3 {
				clearScrollback()
			}
		default:
			return
		}
	}

	private func eraseLine(_ mode: Int) {
		switch mode {
		case 0:
			for column in cursorColumn ..< columns {
				screen[cursorRow][column] = Self.blankCell(attributes: currentCellAttributes())
			}
		case 1:
			for column in 0 ... cursorColumn {
				screen[cursorRow][column] = Self.blankCell(attributes: currentCellAttributes())
			}
		case 2:
			screen[cursorRow] = Self.blankLine(columns: columns, attributes: currentCellAttributes())
		default:
			return
		}
	}

	private func saveCursor() {
		savedCursor = (cursorRow, cursorColumn)
	}

	private func restoreCursor() {
		cursorRow = min(rows - 1, max(0, savedCursor.row))
		cursorColumn = min(columns - 1, max(0, savedCursor.column))
	}

	private func appendHistory(_ line: [TerminalCell]) {
		guard maxScrollbackLines > 0 else {
			return
		}
		history.append(line)
		if history.count > maxScrollbackLines {
			history.removeFirst(history.count - maxScrollbackLines)
		}
	}

	private func resizedScreen(_ old: [[TerminalCell]], columns: Int, rows: Int) -> [[TerminalCell]] {
		var next = Self.blankScreen(columns: columns, rows: rows)
		let copyRows = min(old.count, rows)
		for row in 0 ..< copyRows {
			let copyColumns = min(old[row].count, columns)
			for column in 0 ..< copyColumns {
				next[row][column] = old[row][column]
			}
		}
		return next
	}

	private static func blankScreen(columns: Int, rows: Int, attributes: TerminalTextAttributes = TerminalTextAttributes()) -> [[TerminalCell]] {
		Array(repeating: blankLine(columns: columns, attributes: attributes), count: rows)
	}

	private static func blankLine(columns: Int, attributes: TerminalTextAttributes = TerminalTextAttributes()) -> [TerminalCell] {
		Array(repeating: blankCell(attributes: attributes), count: columns)
	}

	private static func blankCell(attributes: TerminalTextAttributes = TerminalTextAttributes()) -> TerminalCell {
		TerminalCell(character: " ", attributes: attributes)
	}

	private static func string(from line: [TerminalCell]) -> String {
		var end = line.count
		while end > 0, (line[end - 1].character == " " || line[end - 1].isContinuation) {
			end -= 1
		}
		return String(line.prefix(end).filter { !$0.isContinuation }.map(\.character))
	}

	private static func xtermPalette() -> [Int: TerminalRGB] {
		let base = [
			TerminalRGB(red: 0, green: 0, blue: 0),
			TerminalRGB(red: 205, green: 0, blue: 0),
			TerminalRGB(red: 0, green: 205, blue: 0),
			TerminalRGB(red: 205, green: 205, blue: 0),
			TerminalRGB(red: 0, green: 0, blue: 238),
			TerminalRGB(red: 205, green: 0, blue: 205),
			TerminalRGB(red: 0, green: 205, blue: 205),
			TerminalRGB(red: 229, green: 229, blue: 229),
			TerminalRGB(red: 127, green: 127, blue: 127),
			TerminalRGB(red: 255, green: 0, blue: 0),
			TerminalRGB(red: 0, green: 255, blue: 0),
			TerminalRGB(red: 255, green: 255, blue: 0),
			TerminalRGB(red: 92, green: 92, blue: 255),
			TerminalRGB(red: 255, green: 0, blue: 255),
			TerminalRGB(red: 0, green: 255, blue: 255),
			TerminalRGB(red: 255, green: 255, blue: 255),
		]
		var palette: [Int: TerminalRGB] = [:]
		for (index, color) in base.enumerated() {
			palette[index] = color
		}
		let levels = [0, 95, 135, 175, 215, 255]
		var colorIndex = 16
		for red in levels {
			for green in levels {
				for blue in levels {
					palette[colorIndex] = TerminalRGB(red: red, green: green, blue: blue)
					colorIndex += 1
				}
			}
		}
		for index in 232 ... 255 {
			let level = 8 + (index - 232) * 10
			palette[index] = TerminalRGB(red: level, green: level, blue: level)
		}
		return palette
	}
}
