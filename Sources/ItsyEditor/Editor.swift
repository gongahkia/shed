import Foundation
import ItsyConfig

public enum Motion: Sendable, Equatable {
	case charForward
	case charBackward
	case lineDown
	case lineUp
	case wordForward
	case wordBackward
	case wordEnd
	case bigWordForward
	case bigWordBackward
	case bigWordEnd
	case lineStart
	case lineEnd
	case visualLineStart
	case visualLineEnd
	case bufferStart
	case bufferEnd
	case paragraphForward
	case paragraphBackward
	case pageDown
	case pageUp
}

public struct UndoTreeSelection: Sendable, Equatable, Codable {
	public struct Range: Sendable, Equatable, Codable {
		public var anchor: Int
		public var head: Int
		public var affinity: String
	}

	public var primary: Range
	public var secondaries: [Range]

	public init(_ selectionSet: SelectionSet) {
		func snapshot(_ selection: Selection) -> Range {
			Range(
				anchor: selection.anchor,
				head: selection.head,
				affinity: selection.affinity == .upstream ? "upstream" : "downstream"
			)
		}
		primary = snapshot(selectionSet.primary)
		secondaries = selectionSet.secondaries.map(snapshot)
	}

	public var selectionSet: SelectionSet {
		func selection(_ range: Range) -> Selection {
			Selection(
				anchor: range.anchor,
				head: range.head,
				affinity: range.affinity == "upstream" ? .upstream : .downstream
			)
		}
		return SelectionSet(primary: selection(primary), secondaries: secondaries.map(selection))
	}
}

public struct UndoTreeNode: Sendable, Equatable, Codable, Identifiable {
	public var id: Int
	public var parentID: Int?
	public var childIDs: [Int]
	public var summary: String
	public var text: Data
	public var selection: UndoTreeSelection
	public var timestamp: Date

	public init(
		id: Int,
		parentID: Int?,
		childIDs: [Int] = [],
		summary: String,
		text: Data,
		selection: SelectionSet,
		timestamp: Date = Date()
	) {
		self.id = id
		self.parentID = parentID
		self.childIDs = childIDs
		self.summary = summary
		self.text = text
		self.selection = UndoTreeSelection(selection)
		self.timestamp = timestamp
	}
}

public struct UndoTree: Sendable, Equatable, Codable {
	public private(set) var nodes: [Int: UndoTreeNode]
	public private(set) var rootID: Int
	public private(set) var currentID: Int
	private var nextID: Int

	public init(text: Data = Data(), selection: SelectionSet = SelectionSet()) {
		let root = UndoTreeNode(id: 0, parentID: nil, summary: "Original", text: text, selection: selection)
		nodes = [0: root]
		rootID = 0
		currentID = 0
		nextID = 1
	}

	public var currentNode: UndoTreeNode? {
		nodes[currentID]
	}

	public var orderedNodes: [UndoTreeNode] {
		nodes.values.sorted { $0.id < $1.id }
	}

	public func node(id: Int) -> UndoTreeNode? {
		nodes[id]
	}

	@discardableResult
	public mutating func append(summary: String, text: Data, selection: SelectionSet) -> Int {
		let parentID = currentID
		let id = nextID
		nextID += 1
		let node = UndoTreeNode(id: id, parentID: parentID, summary: summary, text: text, selection: selection)
		nodes[id] = node
		if var parent = nodes[parentID] {
			parent.childIDs.append(id)
			nodes[parentID] = parent
		}
		currentID = id
		return id
	}

	public mutating func reset(text: Data, selection: SelectionSet) {
		self = UndoTree(text: text, selection: selection)
	}

	public mutating func moveToParent(of nodeID: Int?) {
		guard let nodeID, let parentID = nodes[nodeID]?.parentID else {
			return
		}
		currentID = parentID
	}

	public mutating func moveToNode(_ nodeID: Int?) {
		guard let nodeID, nodes[nodeID] != nil else {
			return
		}
		currentID = nodeID
	}

	public mutating func jump(to nodeID: Int) -> Bool {
		guard nodes[nodeID] != nil else {
			return false
		}
		currentID = nodeID
		return true
	}

	public func path(to nodeID: Int) -> [Int] {
		guard nodes[nodeID] != nil else {
			return []
		}
		var path: [Int] = []
		var cursor: Int? = nodeID
		while let id = cursor, let node = nodes[id] {
			path.append(id)
			cursor = node.parentID
		}
		return path.reversed()
	}

	public func limited(maxNodeCount: Int, maxTotalTextBytes: Int) -> UndoTree {
		let currentPath = Set(path(to: currentID))
		var keep = Set([rootID]).union(currentPath)
		var keptBytes = keep.reduce(0) { $0 + (nodes[$1]?.text.count ?? 0) }
		for node in orderedNodes.reversed() where !keep.contains(node.id) {
			let nodePath = path(to: node.id)
			let missingIDs = nodePath.filter { !keep.contains($0) }
			let missingBytes = missingIDs.reduce(0) { $0 + (nodes[$1]?.text.count ?? 0) }
			guard keep.count + missingIDs.count <= maxNodeCount,
			      keptBytes + missingBytes <= maxTotalTextBytes
			else {
				continue
			}
			keep.formUnion(missingIDs)
			keptBytes += missingBytes
		}
		var keptNodes = nodes.filter { keep.contains($0.key) }
		for (id, node) in keptNodes {
			var updated = node
			updated.childIDs = node.childIDs.filter { keep.contains($0) }
			keptNodes[id] = updated
		}
		var copy = self
		copy.nodes = keptNodes
		if !keptNodes.keys.contains(copy.currentID) {
			copy.currentID = rootID
		}
		return copy
	}
}

public struct UndoStack: Sendable, Equatable {
	public var maxEditCount: Int
	public var maxTotalRemovedBytes: Int
	public private(set) var edits: [UndoEntry] = []
	public private(set) var tree = UndoTree()
	private var redoEdits: [UndoEntry] = []
	private var entryByNodeID: [Int: UndoEntry] = [:]
	private var entryIndexMayContainStaleEntries = false
	private var activeGroupID: Int?
	private var nextGroupID = 1

	public init(maxEditCount: Int = 10_000, maxTotalRemovedBytes: Int = 64 * 1_024 * 1_024) {
		precondition(maxEditCount > 0, "maxEditCount must be positive")
		precondition(maxTotalRemovedBytes >= 0, "maxTotalRemovedBytes must be non-negative")
		self.maxEditCount = maxEditCount
		self.maxTotalRemovedBytes = maxTotalRemovedBytes
	}

	mutating func record(
		_ edit: Edit,
		reverse: Edit,
		selectionBefore: SelectionSet,
		selectionAfter: SelectionSet,
		snapshotText: Data
	) {
		let nodeID = tree.append(summary: Self.summary(for: edit), text: snapshotText, selection: selectionAfter)
		let entry = UndoEntry(
			edit: edit,
			reverse: reverse,
			selectionAfter: selectionAfter,
			selectionBefore: selectionBefore,
			groupID: activeGroupID,
			treeNodeID: nodeID
		)
		edits.append(entry)
		entryByNodeID[nodeID] = entry
		for redoEntry in redoEdits {
			if let redoNodeID = redoEntry.treeNodeID {
				entryByNodeID[redoNodeID] = nil
			}
		}
		redoEdits.removeAll()
		if entryIndexMayContainStaleEntries {
			pruneEntryIndex()
			entryIndexMayContainStaleEntries = false
		}
		trimUndoHistory()
	}

	mutating func resetTree(text: Data, selection: SelectionSet) {
		tree.reset(text: text, selection: selection)
		entryByNodeID = [:]
		edits = []
		redoEdits = []
		entryIndexMayContainStaleEntries = false
	}

	public mutating func replaceTree(_ tree: UndoTree) {
		self.tree = tree
		edits = Self.entries(for: tree, nodeIDs: Array(tree.path(to: tree.currentID).dropFirst()))
		redoEdits = []
		entryByNodeID = Dictionary(uniqueKeysWithValues: edits.compactMap { entry in
			guard let nodeID = entry.treeNodeID else {
				return nil
			}
			return (nodeID, entry)
		})
		entryIndexMayContainStaleEntries = false
	}

	public mutating func jumpToTreeNode(_ id: Int) -> Bool {
		guard tree.jump(to: id) else {
			return false
		}
		let path = tree.path(to: id).dropFirst()
		let missingEntries = path.contains { entryByNodeID[$0] == nil }
		if missingEntries {
			let rebuilt = Self.entries(for: tree, nodeIDs: Array(path))
			for entry in rebuilt {
				if let nodeID = entry.treeNodeID {
					entryByNodeID[nodeID] = entry
				}
			}
		}
		edits = path.compactMap { entryByNodeID[$0] }
		redoEdits = []
		entryIndexMayContainStaleEntries = true
		return true
	}

	mutating func beginGroup() {
		guard activeGroupID == nil else {
			return
		}
		activeGroupID = nextGroupID
		nextGroupID += 1
	}

	mutating func endGroup() {
		activeGroupID = nil
	}

	mutating func popUndo() -> [UndoEntry]? {
		guard let entry = edits.popLast() else {
			return nil
		}
		var entries = [entry]
		if let groupID = entry.groupID {
			while edits.last?.groupID == groupID, let groupedEntry = edits.popLast() {
				entries.append(groupedEntry)
			}
		}
		redoEdits.append(contentsOf: entries)
		tree.moveToParent(of: entries.last?.treeNodeID)
		return entries
	}

	mutating func popRedo() -> [UndoEntry]? {
		guard let entry = redoEdits.popLast() else {
			return nil
		}
		var entries = [entry]
		if let groupID = entry.groupID {
			while redoEdits.last?.groupID == groupID, let groupedEntry = redoEdits.popLast() {
				entries.append(groupedEntry)
			}
		}
		edits.append(contentsOf: entries)
		tree.moveToNode(entries.last?.treeNodeID)
		return entries
	}

	private mutating func trimUndoHistory() {
		while edits.count + redoEdits.count > maxEditCount || retainedRemovedBytes > maxTotalRemovedBytes {
			guard !edits.isEmpty else {
				for redoEntry in redoEdits {
					if let redoNodeID = redoEntry.treeNodeID {
						entryByNodeID[redoNodeID] = nil
					}
				}
				redoEdits.removeAll()
				return
			}
			dropOldestUndoGroup()
		}
	}

	private var retainedRemovedBytes: Int {
		retainedRemovedBytes(in: edits) + retainedRemovedBytes(in: redoEdits)
	}

	private func retainedRemovedBytes(in entries: [UndoEntry]) -> Int {
		entries.reduce(0) { $0 + $1.edit.removed.count }
	}

	private mutating func dropOldestUndoGroup() {
		guard let first = edits.first else {
			return
		}
		let groupID = first.groupID
		edits.removeFirst()
		if let nodeID = first.treeNodeID {
			entryByNodeID[nodeID] = nil
		}
		guard let groupID else {
			return
		}
		while edits.first?.groupID == groupID, let entry = edits.first {
			edits.removeFirst()
			if let nodeID = entry.treeNodeID {
				entryByNodeID[nodeID] = nil
			}
		}
	}

	private mutating func pruneEntryIndex() {
		let liveIDs = Set((edits + redoEdits).compactMap(\.treeNodeID))
		entryByNodeID = entryByNodeID.filter { liveIDs.contains($0.key) }
	}

	private static func summary(for edit: Edit) -> String {
		if edit.inserted.isEmpty {
			return "Delete \(edit.removed.count)b"
		}
		if edit.removed.isEmpty {
			return "Insert \(edit.inserted.count)b"
		}
		return "Replace \(edit.removed.count)b -> \(edit.inserted.count)b"
	}

	private static func entries(for tree: UndoTree, nodeIDs: [Int]) -> [UndoEntry] {
		nodeIDs.compactMap { nodeID in
			guard let node = tree.node(id: nodeID),
			      let parentID = node.parentID,
			      let parent = tree.node(id: parentID)
			else {
				return nil
			}
			let edit = edit(from: parent, to: node)
			let reverse = reverseEdit(for: edit, selectionBefore: node.selection.selectionSet)
			return UndoEntry(
				edit: edit,
				reverse: reverse,
				selectionAfter: node.selection.selectionSet,
				selectionBefore: parent.selection.selectionSet,
				groupID: nil,
				treeNodeID: nodeID
			)
		}
	}

	private static func edit(from parent: UndoTreeNode, to node: UndoTreeNode) -> Edit {
		let oldBytes = Array(parent.text)
		let newBytes = Array(node.text)
		var prefix = 0
		while prefix < oldBytes.count, prefix < newBytes.count, oldBytes[prefix] == newBytes[prefix] {
			prefix += 1
		}
		var suffix = 0
		while suffix < oldBytes.count - prefix,
		      suffix < newBytes.count - prefix,
		      oldBytes[oldBytes.count - suffix - 1] == newBytes[newBytes.count - suffix - 1]
		{
			suffix += 1
		}
		let oldUpper = oldBytes.count - suffix
		let newUpper = newBytes.count - suffix
		return Edit(
			range: prefix ..< oldUpper,
			removed: Data(oldBytes[prefix ..< oldUpper]),
			inserted: Data(newBytes[prefix ..< newUpper]),
			selectionBefore: parent.selection.selectionSet
		)
	}

	private static func reverseEdit(for edit: Edit, selectionBefore: SelectionSet) -> Edit {
		Edit(
			range: edit.range.lowerBound ..< edit.range.lowerBound + edit.inserted.count,
			removed: edit.inserted,
			inserted: edit.removed,
			selectionBefore: selectionBefore
		)
	}
}

public struct UndoEntry: Sendable, Equatable {
	public var edit: Edit
	public var reverse: Edit
	public var selectionAfter: SelectionSet
	public var selectionBefore: SelectionSet
	public var groupID: Int?
	public var treeNodeID: Int?
}

public enum EditorStorageKind: String, Sendable, Equatable {
	case rope
	case pieceTree = "piecetree"

	init(_ setting: ItsySettings.EditorStorage) {
		switch setting {
		case .rope:
			self = .rope
		case .pieceTree:
			self = .pieceTree
		}
	}
}

public enum EditorTextStorage: Sendable {
	case rope(Rope)
	case pieceTree(PieceTree)

	public var kind: EditorStorageKind {
		switch self {
		case .rope:
			return .rope
		case .pieceTree:
			return .pieceTree
		}
	}

	public var length: Int {
		switch self {
		case let .rope(rope):
			return rope.length
		case let .pieceTree(pieceTree):
			return pieceTree.length
		}
	}

	public var lineCount: Int {
		switch self {
		case let .rope(rope):
			return rope.lineCount
		case let .pieceTree(pieceTree):
			return pieceTree.lineCount
		}
	}

	public var graphemeCount: Int {
		switch self {
		case let .rope(rope):
			return rope.graphemeCount
		case let .pieceTree(pieceTree):
			return pieceTree.graphemeCount
		}
	}

	public func substring(_ range: Range<Int>) -> String {
		switch self {
		case let .rope(rope):
			return rope.slice(range)
		case let .pieceTree(pieceTree):
			return pieceTree.substring(range)
		}
	}

	public func line(forOffset offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.line(forOffset: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.line(forOffset: offset)
		}
	}

	public func offset(forLine line: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.offset(forLine: line)
		case let .pieceTree(pieceTree):
			return pieceTree.offset(forLine: line)
		}
	}

	public func lineRange(_ line: Int) -> Range<Int> {
		switch self {
		case let .rope(rope):
			return rope.lineRange(line)
		case let .pieceTree(pieceTree):
			return pieceTree.lineRange(line)
		}
	}

	public func graphemeIndex(forOffset offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.graphemeIndex(forOffset: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.graphemeIndex(forOffset: offset)
		}
	}

	public func isGraphemeBoundary(_ offset: Int) -> Bool {
		switch self {
		case let .rope(rope):
			return rope.isGraphemeBoundary(offset)
		case let .pieceTree(pieceTree):
			return pieceTree.isGraphemeBoundary(offset)
		}
	}

	public func previousGraphemeBoundary(before offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.previousGraphemeBoundary(before: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.previousGraphemeBoundary(before: offset)
		}
	}

	public func nextGraphemeBoundary(after offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.nextGraphemeBoundary(after: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.nextGraphemeBoundary(after: offset)
		}
	}

	public mutating func insert(_ string: String, at offset: Int) {
		switch self {
		case var .rope(rope):
			rope.insert(string, at: offset)
			self = .rope(rope)
		case var .pieceTree(pieceTree):
			pieceTree.insert(string, at: offset)
			self = .pieceTree(pieceTree)
		}
	}

	public mutating func remove(_ range: Range<Int>) {
		switch self {
		case var .rope(rope):
			rope.remove(range)
			self = .rope(rope)
		case var .pieceTree(pieceTree):
			pieceTree.remove(range)
			self = .pieceTree(pieceTree)
		}
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with string: String) -> Edit {
		replace(range, with: Data(string.utf8))
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with data: Data) -> Edit {
		switch self {
		case var .rope(rope):
			let oldText = rope.slice(range)
			if !range.isEmpty {
				rope.remove(range)
			}
			if !data.isEmpty {
				rope.insert(String(decoding: data, as: UTF8.self), at: range.lowerBound)
			}
			self = .rope(rope)
			return Edit(
				range: range.lowerBound ..< range.lowerBound + data.count,
				removed: data,
				inserted: Data(oldText.utf8)
			)
		case var .pieceTree(pieceTree):
			let reverse = pieceTree.replace(range, with: data)
			self = .pieceTree(pieceTree)
			return reverse
		}
	}
}

public struct Editor: Sendable {
	public private(set) var retainsUndoTreeSnapshots: Bool
	public var rope: Rope {
		get {
			switch textStorage {
			case let .rope(rope):
				return rope
			case let .pieceTree(pieceTree):
				return Rope(pieceTree.substring(0 ..< pieceTree.length))
			}
		}
		set {
			textStorage = .rope(newValue)
			setNormalizedSelections(selections)
		}
	}
	public private(set) var textStorage: EditorTextStorage
	public private(set) var selections: SelectionSet
	public var history: UndoStack
	public private(set) var lastEditBatch: [Edit] = []
	public var pageLineCount = 40

	public init(text: String = "") {
		self.init(text: text, storage: Self.configuredStorage)
	}

	public init(text: String, storage: EditorStorageKind) {
		retainsUndoTreeSnapshots = true
		switch storage {
		case .rope:
			textStorage = .rope(Rope(text))
		case .pieceTree:
			textStorage = .pieceTree(PieceTree(text))
		}
		selections = SelectionSet()
		history = UndoStack()
		history.resetTree(text: Data(text.utf8), selection: selections)
	}

	public init(pieceTree: PieceTree, retainsUndoTreeSnapshots: Bool = true) {
		self.retainsUndoTreeSnapshots = retainsUndoTreeSnapshots
		textStorage = .pieceTree(pieceTree)
		selections = SelectionSet()
		history = UndoStack()
		history.resetTree(text: retainsUndoTreeSnapshots ? fullTextData : Data(), selection: selections)
	}

	public static func resolveStorage(environment: [String: String], settings: ItsySettings) -> EditorStorageKind {
		if let rawStorage = environment["ITSY_EDITOR_STORAGE"]?.lowercased(), let storage = EditorStorageKind(rawValue: rawStorage) {
			return storage
		}
		return EditorStorageKind(settings.editor.experimental.storage)
	}

	private static let configuredStorage = resolveStorage(
		environment: ProcessInfo.processInfo.environment,
		settings: ItsySettingsStore().load().settings
	)

	public mutating func beginUndoGroup() {
		history.beginGroup()
	}

	public mutating func endUndoGroup() {
		history.endGroup()
	}

	@discardableResult
	public mutating func insert(_ string: String) -> EditorMutationTransaction? {
		replaceSelections(with: string)
	}

	@discardableResult
	public mutating func deleteBackward() -> EditorMutationTransaction? {
		let ranges = selectionsForEdit().map { selection -> Range<Int> in
			if !selection.isCaret {
				return selection.range
			}
			return previousCharacterRange(before: selection.head)
		}
		return commitTextMutation(ranges: ranges, with: "")
	}

	@discardableResult
	public mutating func deleteForward() -> EditorMutationTransaction? {
		let ranges = selectionsForEdit().map { selection -> Range<Int> in
			if !selection.isCaret {
				return selection.range
			}
			return nextCharacterRange(after: selection.head)
		}
		return commitTextMutation(ranges: ranges, with: "")
	}

	public mutating func moveCursor(_ motion: Motion) {
		lastEditBatch = []
		let moved = selectionsForEdit().map { selection -> Selection in
			let offset: Int
			switch motion {
			case .charForward:
				offset = nextCharacterRange(after: selection.head).upperBound
			case .charBackward:
				offset = previousCharacterRange(before: selection.head).lowerBound
			case .lineDown:
				offset = verticalLineOffset(from: selection.head, delta: 1)
			case .lineUp:
				offset = verticalLineOffset(from: selection.head, delta: -1)
			case .wordForward:
				offset = wordForward(from: selection.head)
			case .wordBackward:
				offset = wordBackward(from: selection.head)
			case .wordEnd:
				offset = wordEnd(from: selection.head, isWordCharacter: isAlphaNumeric)
			case .bigWordForward:
				offset = wordForward(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .bigWordBackward:
				offset = wordBackward(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .bigWordEnd:
				offset = wordEnd(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .lineStart, .visualLineStart:
				offset = textStorage.offset(forLine: textStorage.line(forOffset: selection.head))
			case .lineEnd, .visualLineEnd:
				offset = textStorage.lineRange(textStorage.line(forOffset: selection.head)).upperBound
			case .bufferStart:
				offset = 0
			case .bufferEnd:
				offset = textStorage.length
			case .paragraphForward:
				offset = paragraphForward(from: selection.head)
			case .paragraphBackward:
				offset = paragraphBackward(from: selection.head)
			case .pageDown:
				let line = min(textStorage.line(forOffset: selection.head) + pageLineCount, max(0, textStorage.lineCount - 1))
				offset = textStorage.offset(forLine: line)
			case .pageUp:
				let line = max(textStorage.line(forOffset: selection.head) - pageLineCount, 0)
				offset = textStorage.offset(forLine: line)
			}
			return Selection(anchor: offset, head: offset, affinity: selection.affinity)
		}
		setNormalizedSelections(SelectionSet(primary: moved[0], secondaries: Array(moved.dropFirst())))
	}

	public mutating func setSelection(_ selectionSet: SelectionSet) {
		lastEditBatch = []
		setNormalizedSelections(selectionSet)
	}

	public mutating func undo() {
		lastEditBatch = []
		guard let entries = history.popUndo() else {
			return
		}
		for entry in entries {
			apply(entry.reverse)
		}
		setNormalizedSelections(entries.last?.selectionBefore ?? selections)
	}

	public mutating func redo() {
		lastEditBatch = []
		guard let entries = history.popRedo() else {
			return
		}
		for entry in entries {
			apply(entry.edit)
		}
		setNormalizedSelections(entries.last?.selectionAfter ?? selections)
	}

	public mutating func restoreUndoTreeNode(id: Int) -> Bool {
		guard retainsUndoTreeSnapshots,
		      let node = history.tree.node(id: id),
		      history.jumpToTreeNode(id)
		else {
			return false
		}
		let text = String(decoding: node.text, as: UTF8.self)
		switch textStorage.kind {
		case .rope:
			textStorage = .rope(Rope(text))
		case .pieceTree:
			textStorage = .pieceTree(PieceTree(text))
		}
		setNormalizedSelections(node.selection.selectionSet)
		lastEditBatch = []
		return true
	}

	private mutating func replaceSelections(with string: String) -> EditorMutationTransaction? {
		let ranges = selectionsForEdit().map(\.range)
		return commitTextMutation(ranges: ranges, with: string)
	}

	private mutating func commitTextMutation(ranges: [Range<Int>], with string: String) -> EditorMutationTransaction? {
		let mergedRanges = merge(ranges.filter { !$0.isEmpty || !string.isEmpty })
		guard !mergedRanges.isEmpty else {
			lastEditBatch = []
			return nil
		}
		let selectionBefore = selections
		let historyRange = mergedRanges[0].lowerBound ..< mergedRanges[mergedRanges.count - 1].upperBound
		let historyBefore = Data(textStorage.substring(historyRange).utf8)
		var recordedEdits: [Edit] = []
		for range in mergedRanges.sorted(by: { $0.lowerBound > $1.lowerBound }) {
			let reverse = textStorage.replace(range, with: string)
			let edit = Edit(range: range, removed: reverse.inserted, inserted: reverse.removed, selectionBefore: selectionBefore)
			recordedEdits.append(edit)
		}
		let carets = replacementCarets(for: mergedRanges, insertedLength: string.utf8.count)
		setNormalizedSelections(SelectionSet(primary: carets[0], secondaries: Array(carets.dropFirst())))
		lastEditBatch = Array(recordedEdits.reversed())
		let historyDelta = mergedRanges.reduce(0) { $0 + string.utf8.count - $1.count }
		let historyAfterRange = historyRange.lowerBound ..< historyRange.upperBound + historyDelta
		let historyAfter = Data(textStorage.substring(historyAfterRange).utf8)
		let historyEdit = Edit(range: historyRange, removed: historyBefore, inserted: historyAfter, selectionBefore: selectionBefore)
		let reverse = Edit(range: historyAfterRange, removed: historyAfter, inserted: historyBefore, selectionBefore: selections)
		history.record(
			historyEdit,
			reverse: reverse,
			selectionBefore: selectionBefore,
			selectionAfter: selections,
			snapshotText: retainsUndoTreeSnapshots ? fullTextData : Data()
		)
		return EditorMutationTransaction(
			edits: lastEditBatch,
			selectionBefore: selectionBefore,
			selectionAfter: selections
		)
	}

	private func replacementCarets(for ranges: [Range<Int>], insertedLength: Int) -> [Selection] {
		var shift = 0
		return ranges.sorted { $0.lowerBound < $1.lowerBound }.map { range in
			let caret = range.lowerBound + shift + insertedLength
			shift += insertedLength - range.count
			return Selection(anchor: caret, head: caret)
		}
	}

	private func selectionsForEdit() -> [Selection] {
		var set = selections
		set.merge()
		return [set.primary] + set.secondaries
	}

	private var fullTextData: Data {
		Data(textStorage.substring(0 ..< textStorage.length).utf8)
	}

	private mutating func setNormalizedSelections(_ selectionSet: SelectionSet) {
		selections = normalized(selectionSet)
	}

	private func normalized(_ selectionSet: SelectionSet) -> SelectionSet {
		func normalize(_ offset: Int, affinity: Affinity) -> Int {
			let clamped = min(max(offset, 0), textStorage.length)
			guard !textStorage.isGraphemeBoundary(clamped) else {
				return clamped
			}
			switch affinity {
			case .upstream:
				return textStorage.previousGraphemeBoundary(before: clamped)
			case .downstream:
				return textStorage.nextGraphemeBoundary(after: clamped)
			}
		}
		func normalize(_ selection: Selection) -> Selection {
			Selection(
				anchor: normalize(selection.anchor, affinity: selection.affinity),
				head: normalize(selection.head, affinity: selection.affinity),
				affinity: selection.affinity
			)
		}
		var result = SelectionSet(primary: normalize(selectionSet.primary), secondaries: selectionSet.secondaries.map(normalize))
		result.merge()
		assert(([result.primary] + result.secondaries).allSatisfy { textStorage.isGraphemeBoundary($0.anchor) && textStorage.isGraphemeBoundary($0.head) }, "selection normalization produced a non-grapheme offset")
		return result
	}

	private func previousCharacterRange(before offset: Int) -> Range<Int> {
		guard offset > 0 else {
			return 0 ..< 0
		}
		return textStorage.previousGraphemeBoundary(before: offset) ..< offset
	}

	private func nextCharacterRange(after offset: Int) -> Range<Int> {
		guard offset < textStorage.length else {
			return textStorage.length ..< textStorage.length
		}
		return offset ..< textStorage.nextGraphemeBoundary(after: offset)
	}

	private func verticalLineOffset(from offset: Int, delta: Int) -> Int {
		let line = textStorage.line(forOffset: offset)
		let lineRange = textStorage.lineRange(line)
		let column = offset - lineRange.lowerBound
		let targetLine = min(max(line + delta, 0), max(0, textStorage.lineCount - 1))
		let targetRange = textStorage.lineRange(targetLine)
		return min(targetRange.lowerBound + column, targetRange.upperBound)
	}

	private func wordForward(from offset: Int) -> Int {
		wordForward(from: offset, isWordCharacter: isAlphaNumeric)
	}

	private func wordForward(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard let index = chars.firstIndex(where: { $0.offset >= offset }), index < chars.count else {
			return textStorage.length
		}
		let current = chars[index].character
		if isWordCharacter(current) {
			var cursor = index
			while cursor < chars.count, isWordCharacter(chars[cursor].character) {
				cursor += 1
			}
			while cursor < chars.count, chars[cursor].character.isWhitespace {
				cursor += 1
			}
			return cursor < chars.count ? chars[cursor].offset : textStorage.length
		}
		return index + 1 < chars.count ? chars[index + 1].offset : textStorage.length
	}

	private func wordBackward(from offset: Int) -> Int {
		wordBackward(from: offset, isWordCharacter: isAlphaNumeric)
	}

	private func wordBackward(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard !chars.isEmpty, offset > 0 else {
			return 0
		}
		var index = chars.lastIndex(where: { $0.offset < offset }) ?? 0
		while index > 0, chars[index].character.isWhitespace {
			index -= 1
		}
		while index > 0, isWordCharacter(chars[index - 1].character) == isWordCharacter(chars[index].character), !chars[index - 1].character.isWhitespace {
			index -= 1
		}
		return chars[index].offset
	}

	private func wordEnd(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard let index = chars.firstIndex(where: { $0.offset >= offset }), index < chars.count else {
			return textStorage.length
		}
		var cursor = index
		if cursor < chars.count, chars[cursor].character.isWhitespace {
			while cursor < chars.count, chars[cursor].character.isWhitespace {
				cursor += 1
			}
		}
		guard cursor < chars.count else {
			return textStorage.length
		}
		let wordState = isWordCharacter(chars[cursor].character)
		while cursor + 1 < chars.count, isWordCharacter(chars[cursor + 1].character) == wordState, !chars[cursor + 1].character.isWhitespace {
			cursor += 1
		}
		return chars[cursor].offset
	}

	private func paragraphForward(from offset: Int) -> Int {
		let currentLine = textStorage.line(forOffset: offset)
		guard currentLine + 1 < textStorage.lineCount else {
			return textStorage.length
		}
		for line in (currentLine + 1) ..< textStorage.lineCount {
			if textStorage.substring(textStorage.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				return textStorage.offset(forLine: min(line + 1, textStorage.lineCount - 1))
			}
		}
		return textStorage.length
	}

	private func paragraphBackward(from offset: Int) -> Int {
		let currentLine = textStorage.line(forOffset: offset)
		guard currentLine > 0 else {
			return 0
		}
		for line in stride(from: currentLine - 1, through: 0, by: -1) {
			if textStorage.substring(textStorage.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				return textStorage.offset(forLine: min(line + 1, textStorage.lineCount - 1))
			}
		}
		return 0
	}

	private func characterOffsets() -> [(offset: Int, character: Character)] {
		var offset = 0
		return textStorage.substring(0 ..< textStorage.length).map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

	private mutating func apply(_ edit: Edit) {
		textStorage.replace(edit.range, with: edit.inserted)
	}
}

private func merge(_ ranges: [Range<Int>]) -> [Range<Int>] {
	let sorted = ranges.sorted { lhs, rhs in
		if lhs.lowerBound == rhs.lowerBound {
			return lhs.upperBound < rhs.upperBound
		}
		return lhs.lowerBound < rhs.lowerBound
	}
	guard var current = sorted.first else {
		return []
	}
	var merged: [Range<Int>] = []
	for range in sorted.dropFirst() {
		if range.lowerBound <= current.upperBound {
			current = current.lowerBound ..< max(current.upperBound, range.upperBound)
		} else {
			merged.append(current)
			current = range
		}
	}
	merged.append(current)
	return merged
}

private func isAlphaNumeric(_ character: Character) -> Bool {
	!character.unicodeScalars.isEmpty && character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
}
