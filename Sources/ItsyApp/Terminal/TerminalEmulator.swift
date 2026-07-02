// @file terminal escape parser and screen snapshot state.
import Foundation

struct TerminalSnapshot {
	var lines: [String]
	var cursorRow: Int
	var cursorColumn: Int
	var alternateScreen: Bool
	var bracketedPaste: Bool
}

final class ItsyTerminalEmulator {
	private enum State {
		case ground
		case escape
		case csi(String)
		case osc
		case oscEscape
	}

	private(set) var columns: Int
	private(set) var rows: Int
	private var maxScrollbackLines: Int
	private var screen: [[Character]]
	private var normalScreen: [[Character]] = []
	private var history: [String] = []
	private var cursorRow = 0
	private var cursorColumn = 0
	private var savedCursor = (row: 0, column: 0)
	private var scrollTop = 0
	private var scrollBottom: Int
	private var state = State.ground
	private(set) var alternateScreen = false
	private(set) var bracketedPaste = false

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
		cursorRow = min(cursorRow, rows - 1)
		cursorColumn = min(cursorColumn, columns - 1)
		scrollTop = 0
		scrollBottom = rows - 1
	}

	func reset() {
		screen = Self.blankScreen(columns: columns, rows: rows)
		normalScreen = []
		history.removeAll(keepingCapacity: true)
		cursorRow = 0
		cursorColumn = 0
		savedCursor = (0, 0)
		scrollTop = 0
		scrollBottom = rows - 1
		state = .ground
		alternateScreen = false
		bracketedPaste = false
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
		let current = screen.map(Self.string(from:))
		let visible: [String]
		if alternateScreen {
			visible = current
		} else {
			let offset = max(0, min(scrollbackOffset, history.count))
			if offset == 0 {
				visible = current
			} else {
				let start = max(0, history.count - offset)
				let end = min(history.count, start + rows)
				var lines = Array(history[start ..< end])
				if lines.count < rows {
					lines += current.prefix(rows - lines.count)
				}
				visible = lines
			}
		}
		return TerminalSnapshot(
			lines: visible,
			cursorRow: cursorRow,
			cursorColumn: cursorColumn,
			alternateScreen: alternateScreen,
			bracketedPaste: bracketedPaste
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
		case .osc:
			if scalar.value == 0x07 {
				state = .ground
			} else if scalar.value == 0x1B {
				state = .oscEscape
			}
		case .oscEscape:
			state = scalar == "\\" ? .ground : .osc
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
			state = .osc
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
			state = .ground
		}
	}

	private func handleCSI(_ scalar: UnicodeScalar, buffer: String) {
		if scalar.value >= 0x40, scalar.value <= 0x7E {
			applyCSI(buffer, final: Character(scalar))
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
			return
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
			}
		case "l":
			if privateMode {
				setPrivateModes(params, enabled: false)
			}
		default:
			return
		}
	}

	private func setPrivateModes(_ modes: [Int], enabled: Bool) {
		for mode in modes {
			switch mode {
			case 1049:
				setAlternateScreen(enabled)
			case 2004:
				bracketedPaste = enabled
			default:
				continue
			}
		}
	}

	private func setAlternateScreen(_ enabled: Bool) {
		guard alternateScreen != enabled else {
			return
		}
		if enabled {
			normalScreen = screen
			screen = Self.blankScreen(columns: columns, rows: rows)
			cursorRow = 0
			cursorColumn = 0
		} else {
			screen = normalScreen.isEmpty ? Self.blankScreen(columns: columns, rows: rows) : normalScreen
			normalScreen = []
			cursorRow = min(cursorRow, rows - 1)
			cursorColumn = min(cursorColumn, columns - 1)
		}
		alternateScreen = enabled
		scrollTop = 0
		scrollBottom = rows - 1
	}

	private func put(_ character: Character) {
		if cursorColumn >= columns {
			cursorColumn = 0
			lineFeed()
		}
		screen[cursorRow][cursorColumn] = character
		cursorColumn += 1
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
				appendHistory(Self.string(from: screen[0]))
			}
			screen.remove(at: scrollTop)
			screen.insert(Self.blankLine(columns: columns), at: scrollBottom)
		}
	}

	private func scrollDown(_ count: Int) {
		let count = min(max(1, count), scrollBottom - scrollTop + 1)
		for _ in 0 ..< count {
			screen.remove(at: scrollBottom)
			screen.insert(Self.blankLine(columns: columns), at: scrollTop)
		}
	}

	private func insertLines(_ count: Int) {
		guard cursorRow >= scrollTop, cursorRow <= scrollBottom else {
			return
		}
		let count = min(max(1, count), scrollBottom - cursorRow + 1)
		for _ in 0 ..< count {
			screen.remove(at: scrollBottom)
			screen.insert(Self.blankLine(columns: columns), at: cursorRow)
		}
	}

	private func deleteLines(_ count: Int) {
		guard cursorRow >= scrollTop, cursorRow <= scrollBottom else {
			return
		}
		let count = min(max(1, count), scrollBottom - cursorRow + 1)
		for _ in 0 ..< count {
			screen.remove(at: cursorRow)
			screen.insert(Self.blankLine(columns: columns), at: scrollBottom)
		}
	}

	private func insertCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		var line = screen[cursorRow]
		line.insert(contentsOf: Array(repeating: " ", count: count), at: cursorColumn)
		screen[cursorRow] = Array(line.prefix(columns))
	}

	private func deleteCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		var line = screen[cursorRow]
		line.removeSubrange(cursorColumn ..< cursorColumn + count)
		line.append(contentsOf: Array(repeating: " ", count: count))
		screen[cursorRow] = line
	}

	private func eraseCharacters(_ count: Int) {
		let count = min(max(1, count), columns - cursorColumn)
		for column in cursorColumn ..< cursorColumn + count {
			screen[cursorRow][column] = " "
		}
	}

	private func eraseDisplay(_ mode: Int) {
		switch mode {
		case 0:
			eraseLine(0)
			if cursorRow + 1 < rows {
				for row in cursorRow + 1 ..< rows {
					screen[row] = Self.blankLine(columns: columns)
				}
			}
		case 1:
			if cursorRow > 0 {
				for row in 0 ..< cursorRow {
					screen[row] = Self.blankLine(columns: columns)
				}
			}
			eraseLine(1)
		case 2, 3:
			screen = Self.blankScreen(columns: columns, rows: rows)
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
				screen[cursorRow][column] = " "
			}
		case 1:
			for column in 0 ... cursorColumn {
				screen[cursorRow][column] = " "
			}
		case 2:
			screen[cursorRow] = Self.blankLine(columns: columns)
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

	private func appendHistory(_ line: String) {
		guard maxScrollbackLines > 0 else {
			return
		}
		history.append(line)
		if history.count > maxScrollbackLines {
			history.removeFirst(history.count - maxScrollbackLines)
		}
	}

	private func resizedScreen(_ old: [[Character]], columns: Int, rows: Int) -> [[Character]] {
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

	private static func blankScreen(columns: Int, rows: Int) -> [[Character]] {
		Array(repeating: blankLine(columns: columns), count: rows)
	}

	private static func blankLine(columns: Int) -> [Character] {
		Array(repeating: " ", count: columns)
	}

	private static func string(from line: [Character]) -> String {
		String(line).trimmingCharacters(in: .whitespaces)
	}
}
