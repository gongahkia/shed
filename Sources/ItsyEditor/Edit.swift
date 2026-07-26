import Foundation

public struct Edit: Sendable, Equatable {
	public var range: Range<Int>
	public var removed: Data
	public var inserted: Data
	public var selectionBefore: SelectionSet

	public init(
		range: Range<Int>,
		removed: Data,
		inserted: Data,
		selectionBefore: SelectionSet = SelectionSet()
	) {
		self.range = range
		self.removed = removed
		self.inserted = inserted
		self.selectionBefore = selectionBefore
	}

	public init(
		range: Range<Int>,
		oldText: String,
		newText: String,
		selectionBefore: SelectionSet = SelectionSet()
	) {
		self.init(
			range: range,
			removed: Data(oldText.utf8),
			inserted: Data(newText.utf8),
			selectionBefore: selectionBefore
		)
	}

	public var oldText: String {
		String(decoding: removed, as: UTF8.self)
	}

	public var newText: String {
		String(decoding: inserted, as: UTF8.self)
	}

	var delta: Int {
		inserted.count - (range.upperBound - range.lowerBound)
	}

	func mapOffset(_ offset: Int) -> Int {
		if offset <= range.lowerBound {
			return offset
		}
		if offset >= range.upperBound {
			return offset + delta
		}
		return range.lowerBound + inserted.count
	}
}

public struct EditorMutationTransaction: Sendable, Equatable {
	public let edits: [Edit]
	public let selectionBefore: SelectionSet
	public let selectionAfter: SelectionSet

	init(edits: [Edit], selectionBefore: SelectionSet, selectionAfter: SelectionSet) {
		self.edits = edits
		self.selectionBefore = selectionBefore
		self.selectionAfter = selectionAfter
	}
}
