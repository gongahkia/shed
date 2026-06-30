import Foundation

public struct LSPJumpEntry: Equatable, Sendable {
	public let url: URL
	public let line: Int
	public let column: Int

	public init(url: URL, line: Int, column: Int) {
		self.url = url
		self.line = line
		self.column = column
	}
}

public struct LSPJumpHistory: Equatable, Sendable {
	private var entries: [LSPJumpEntry]
	private var cursor: Int

	public init() {
		entries = []
		cursor = -1
	}

	public var current: LSPJumpEntry? {
		guard cursor >= 0, cursor < entries.count else {
			return nil
		}
		return entries[cursor]
	}

	public var canGoBack: Bool {
		cursor > 0
	}

	public var canGoForward: Bool {
		cursor >= 0 && cursor < entries.count - 1
	}

	public var count: Int {
		entries.count
	}

	public mutating func push(_ entry: LSPJumpEntry) {
		if cursor >= 0, cursor < entries.count - 1 {
			entries.removeSubrange((cursor + 1) ..< entries.count)
		}
		entries.append(entry)
		cursor = entries.count - 1
	}

	public mutating func goBack() -> LSPJumpEntry? {
		guard canGoBack else {
			return nil
		}
		cursor -= 1
		return entries[cursor]
	}

	public mutating func goForward() -> LSPJumpEntry? {
		guard canGoForward else {
			return nil
		}
		cursor += 1
		return entries[cursor]
	}

	public mutating func clear() {
		entries.removeAll()
		cursor = -1
	}
}
