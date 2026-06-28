public enum Affinity: Sendable, Equatable {
	case upstream
	case downstream
}

public struct Selection: Sendable, Equatable {
	public var anchor: Int
	public var head: Int
	public var affinity: Affinity

	public init(anchor: Int, head: Int, affinity: Affinity = .downstream) {
		self.anchor = anchor
		self.head = head
		self.affinity = affinity
	}

	public var range: Range<Int> {
		min(anchor, head) ..< max(anchor, head)
	}

	public var isCaret: Bool {
		anchor == head
	}

	func mapped(through edit: Edit) -> Selection {
		Selection(
			anchor: edit.mapOffset(anchor),
			head: edit.mapOffset(head),
			affinity: affinity
		)
	}
}

public struct SelectionSet: Sendable, Equatable {
	public var primary: Selection
	public var secondaries: [Selection]

	public init(primary: Selection = Selection(anchor: 0, head: 0), secondaries: [Selection] = []) {
		self.primary = primary
		self.secondaries = secondaries
	}

	public mutating func merge() {
		let selections = ([primary] + secondaries).sorted { lhs, rhs in
			if lhs.range.lowerBound == rhs.range.lowerBound {
				return lhs.range.upperBound < rhs.range.upperBound
			}
			return lhs.range.lowerBound < rhs.range.lowerBound
		}
		guard var current = selections.first else {
			return
		}
		var merged: [Selection] = []
		for selection in selections.dropFirst() {
			if selection.range.lowerBound <= current.range.upperBound {
				current = Selection(
					anchor: current.range.lowerBound,
					head: max(current.range.upperBound, selection.range.upperBound),
					affinity: current.affinity
				)
			} else {
				merged.append(current)
				current = selection
			}
		}
		merged.append(current)
		primary = merged[0]
		secondaries = Array(merged.dropFirst())
	}

	public func map(through edit: Edit) -> SelectionSet {
		var mapped = SelectionSet(
			primary: primary.mapped(through: edit),
			secondaries: secondaries.map { $0.mapped(through: edit) }
		)
		mapped.merge()
		return mapped
	}
}

public struct Edit: Sendable, Equatable {
	public var range: Range<Int>
	public var oldText: String
	public var newText: String
	public var selectionBefore: SelectionSet

	public init(range: Range<Int>, oldText: String, newText: String, selectionBefore: SelectionSet = SelectionSet()) {
		self.range = range
		self.oldText = oldText
		self.newText = newText
		self.selectionBefore = selectionBefore
	}

	var delta: Int {
		newText.utf8.count - (range.upperBound - range.lowerBound)
	}

	func mapOffset(_ offset: Int) -> Int {
		if offset <= range.lowerBound {
			return offset
		}
		if offset >= range.upperBound {
			return offset + delta
		}
		return range.lowerBound + newText.utf8.count
	}
}
