import Foundation
import ItsyEditor

@MainActor final class GitDiffOrchestrator {
	enum Mode: Equatable {
		case unified
		case sideBySide
	}

	struct HunkItem: Equatable {
		let fileIndex: Int
		let hunkIndex: Int
		let title: String
		let isStaged: Bool
	}

	enum LineSelectionError: Error, Equatable {
		case unifiedModeRequired
		case noChangedLinesSelected
	}

	enum Rendered {
		case unified(RenderedDiffDocument, path: String?)
		case sideBySide(old: RenderedDiffDocument, oldPath: String?, new: RenderedDiffDocument, newPath: String?)
	}

	struct Load {
		let isStaged: Bool
		let hasTextDiff: Bool
	}

	var mode: Mode = .unified
	private(set) var files: [DiffFile] = []
	private(set) var path: String?
	private(set) var hunkItems: [HunkItem] = []
	private var unifiedLineItems: [DiffSelectionContext] = []

	func load(entry: GitStatusEntry, root: URL, repositoryDomain: GitRepositoryDomain) throws -> Load {
		let files: [DiffFile]
		let isStaged: Bool
		if entry.kind == .untracked {
			let contents = try String(contentsOf: root.appendingPathComponent(entry.path), encoding: .utf8)
			files = [DiffTextRenderer.newFile(path: entry.path, contents: contents)]
			isStaged = false
		} else {
			isStaged = entry.isStaged && !entry.isUnstaged
			files = try repositoryDomain.diffFiles(path: entry.path, at: root, staged: isStaged)
		}
		set(files: files, path: entry.path, isStaged: isStaged)
		return Load(isStaged: isStaged, hasTextDiff: !files.flatMap(\.hunks).isEmpty)
	}

	func set(files: [DiffFile], path: String?, isStaged: Bool) {
		self.files = files
		self.path = path
		unifiedLineItems = []
		hunkItems = files.enumerated().flatMap { fileIndex, file in
			file.hunks.enumerated().map { hunkIndex, hunk in
				HunkItem(
					fileIndex: fileIndex,
					hunkIndex: hunkIndex,
					title: "\(file.newPath ?? file.oldPath ?? "file"):\(hunk.oldStart)->\(hunk.newStart)",
					isStaged: isStaged
				)
			}
		}
	}

	func clear(path: String?) {
		files = []
		self.path = path
		hunkItems = []
		unifiedLineItems = []
	}

	func item(at index: Int) -> HunkItem? {
		guard hunkItems.indices.contains(index) else {
			return nil
		}
		return hunkItems[index]
	}

	func fileAndHunk(for item: HunkItem) -> (file: DiffFile, hunk: DiffHunk)? {
		guard files.indices.contains(item.fileIndex), files[item.fileIndex].hunks.indices.contains(item.hunkIndex) else {
			return nil
		}
		return (files[item.fileIndex], files[item.fileIndex].hunks[item.hunkIndex])
	}

	func render() -> Rendered? {
		guard !files.isEmpty else {
			unifiedLineItems = []
			return nil
		}
		switch mode {
		case .unified:
			let document = DiffTextRenderer.unified(files: files)
			unifiedLineItems = DiffSelectionMapper.contexts(files: files, document: document)
			return .unified(document, path: path)
		case .sideBySide:
			unifiedLineItems = []
			let rendered = DiffTextRenderer.sideBySide(files: files)
			return .sideBySide(old: rendered.old, oldPath: files.first?.oldPath ?? path, new: rendered.new, newPath: files.first?.newPath ?? path)
		}
	}

	func selectedLineIndexes(selection: Range<Int>?, for item: HunkItem) throws -> IndexSet {
		guard mode == .unified else {
			throw LineSelectionError.unifiedModeRequired
		}
		guard let selection else {
			throw LineSelectionError.noChangedLinesSelected
		}
		let indexes = DiffSelectionMapper.lineIndexes(
			selection: selection,
			fileIndex: item.fileIndex,
			hunkIndex: item.hunkIndex,
			contexts: unifiedLineItems
		)
		guard !indexes.isEmpty else {
			throw LineSelectionError.noChangedLinesSelected
		}
		return indexes
	}
}
