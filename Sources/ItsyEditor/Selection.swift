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

	public func validationFailures(against tree: PieceTree) -> [String] {
		let selections = [primary] + secondaries
		var failures: [String] = []
		for (index, selection) in selections.enumerated() {
			appendOffsetFailures(selection.anchor, label: "selection \(index) anchor", tree: tree, to: &failures)
			appendOffsetFailures(selection.head, label: "selection \(index) head", tree: tree, to: &failures)
		}
		var merged = self
		merged.merge()
		let mergedSelections = [merged.primary] + merged.secondaries
		for index in mergedSelections.indices.dropFirst() {
			let previous = mergedSelections[mergedSelections.index(before: index)]
			let current = mergedSelections[index]
			if previous.range.upperBound >= current.range.lowerBound {
				failures.append("selection \(index) overlaps previous selection after merge")
			}
		}
		return failures
	}

	public func validate(_ label: String = "selection", against tree: PieceTree) {
		for failure in validationFailures(against: tree) {
			assertionFailure("\(label): \(failure)")
		}
	}

	private func appendOffsetFailures(_ offset: Int, label: String, tree: PieceTree, to failures: inout [String]) {
		guard (0 ... tree.length).contains(offset) else {
			failures.append("\(label) offset \(offset) out of bounds 0...\(tree.length)")
			return
		}
		if !tree.isGraphemeBoundary(offset) {
			failures.append("\(label) offset \(offset) is not a grapheme boundary")
		}
	}
}
