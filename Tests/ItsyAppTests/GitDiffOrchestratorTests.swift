import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@Test @MainActor func gitDiffOrchestratorOwnsDiffRenderingAndLineSelection() throws {
	let orchestrator = GitDiffOrchestrator()
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [
		DiffHunk(oldStart: 2, oldCount: 1, newStart: 2, newCount: 1, lines: [.remove("old"), .add("new")]),
	])
	orchestrator.set(files: [file], path: "file.txt", isStaged: false)
	let item = try #require(orchestrator.item(at: 0))
	#expect(item == .init(fileIndex: 0, hunkIndex: 0, title: "file.txt:2->2", isStaged: false))
	#expect(orchestrator.fileAndHunk(for: item)?.hunk == file.hunks[0])
	guard case let .unified(document, path) = orchestrator.render() else {
		Issue.record("expected a unified diff")
		return
	}
	#expect(path == "file.txt")
	let addition = try #require(document.lines.first { $0.kind == .addition })
	#expect(try orchestrator.selectedLineIndexes(selection: addition.fullRange, for: item) == IndexSet(integer: 1))

	orchestrator.mode = .sideBySide
	#expect(throws: GitDiffOrchestrator.LineSelectionError.unifiedModeRequired) {
		try orchestrator.selectedLineIndexes(selection: addition.fullRange, for: item)
	}
}

@Test @MainActor func gitDiffOrchestratorRejectsEmptyOrMissingSelections() throws {
	let orchestrator = GitDiffOrchestrator()
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [
		DiffHunk(oldStart: 1, oldCount: 1, newStart: 1, newCount: 1, lines: [.remove("old"), .add("new")]),
	])
	orchestrator.set(files: [file], path: "file.txt", isStaged: true)
	let item = try #require(orchestrator.item(at: 0))
	_ = orchestrator.render()
	#expect(throws: GitDiffOrchestrator.LineSelectionError.noChangedLinesSelected) {
		try orchestrator.selectedLineIndexes(selection: nil, for: item)
	}
	#expect(throws: GitDiffOrchestrator.LineSelectionError.noChangedLinesSelected) {
		try orchestrator.selectedLineIndexes(selection: 0 ..< 1, for: item)
	}
}
