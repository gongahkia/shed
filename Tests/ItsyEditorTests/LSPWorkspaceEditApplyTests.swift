import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func workspaceEditApplyRewritesEachFileFromDocumentChanges() throws {
	let edit = LSPWorkspaceEdit(
		documentChanges: [
			LSPTextDocumentEdit(
				textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///a.swift", version: 1),
				edits: [
					LSPTextEdit(
						range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
						newText: "AAA"
					),
				]
			),
			LSPTextDocumentEdit(
				textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///b.swift", version: 1),
				edits: [
					LSPTextEdit(
						range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
						newText: "BBB"
					),
				]
			),
		]
	)
	let result = try LSPWorkspaceEditApply.apply(edit, sources: [
		"file:///a.swift": "abc\n",
		"file:///b.swift": "abc\n",
	])
	#expect(result == [
		LSPWorkspaceEditApply.ResolvedFile(uri: "file:///a.swift", updatedText: "AAA\n"),
		LSPWorkspaceEditApply.ResolvedFile(uri: "file:///b.swift", updatedText: "BBB\n"),
	])
}

@Test func workspaceEditApplyFallsBackToChangesMapWhenNoDocumentChanges() throws {
	let edit = LSPWorkspaceEdit(changes: [
		"file:///a.swift": [
			LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "header\n"
			),
		],
	])
	let result = try LSPWorkspaceEditApply.apply(edit, sources: ["file:///a.swift": "body\n"])
	#expect(result == [LSPWorkspaceEditApply.ResolvedFile(uri: "file:///a.swift", updatedText: "header\nbody\n")])
}

@Test func workspaceEditApplyThrowsWhenSourceMissing() {
	let edit = LSPWorkspaceEdit(changes: [
		"file:///missing.swift": [
			LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "x"
			),
		],
	])
	do {
		_ = try LSPWorkspaceEditApply.apply(edit, sources: [:])
		Issue.record("expected sourceMissing")
	} catch let error as LSPWorkspaceEditApplyError {
		#expect(error == .sourceMissing(uri: "file:///missing.swift"))
	} catch {
		Issue.record("unexpected error \(error)")
	}
}

@Test func workspaceEditApplyRollsBackOnFailureByThrowingAtomically() {
	let edit = LSPWorkspaceEdit(
		documentChanges: [
			LSPTextDocumentEdit(
				textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///good.swift", version: 1),
				edits: [
					LSPTextEdit(
						range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
						newText: "x"
					),
				]
			),
			LSPTextDocumentEdit(
				textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///bad.swift", version: 1),
				edits: [
					LSPTextEdit(
						range: LSPRange(start: LSPPosition(line: 5, character: 0), end: LSPPosition(line: 5, character: 0)),
						newText: "x"
					),
				]
			),
		]
	)
	do {
		_ = try LSPWorkspaceEditApply.apply(edit, sources: [
			"file:///good.swift": "abc\n",
			"file:///bad.swift": "abc\n",
		])
		Issue.record("expected editFailed")
	} catch let error as LSPWorkspaceEditApplyError {
		if case let .editFailed(uri, _) = error {
			#expect(uri == "file:///bad.swift")
		} else {
			Issue.record("expected editFailed, got \(error)")
		}
	} catch {
		Issue.record("unexpected error \(error)")
	}
}

@Test func workspaceEditApplyThrowsForEmptyEdit() {
	do {
		_ = try LSPWorkspaceEditApply.apply(LSPWorkspaceEdit(), sources: [:])
		Issue.record("expected empty")
	} catch let error as LSPWorkspaceEditApplyError {
		#expect(error == .empty)
	} catch {
		Issue.record("unexpected error \(error)")
	}
}

@Test func workspaceEditApplyPrefersDocumentChangesWhenBothPresent() throws {
	let edit = LSPWorkspaceEdit(
		changes: [
			"file:///a.swift": [
				LSPTextEdit(
					range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
					newText: "ZZZ"
				),
			],
		],
		documentChanges: [
			LSPTextDocumentEdit(
				textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///a.swift", version: 1),
				edits: [
					LSPTextEdit(
						range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
						newText: "QQQ"
					),
				]
			),
		]
	)
	let result = try LSPWorkspaceEditApply.apply(edit, sources: ["file:///a.swift": "abc\n"])
	#expect(result == [LSPWorkspaceEditApply.ResolvedFile(uri: "file:///a.swift", updatedText: "QQQ\n")])
}

@Test func workspaceEditApplyRejectsStaleVersionedDocumentChanges() {
	let edit = LSPWorkspaceEdit(documentChanges: [
		LSPTextDocumentEdit(
			textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///a.swift", version: 4),
			edits: [LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "stale"
			)]
		),
	])
	#expect(throws: LSPWorkspaceEditApplyError.staleDocumentVersion(uri: "file:///a.swift", expected: 5, received: 4)) {
		try LSPWorkspaceEditApply.apply(edit, sources: ["file:///a.swift": "current"], documentVersions: ["file:///a.swift": 5])
	}
}

@Test func workspaceEditApplyRejectsAnyStaleFileBeforeResolvingMultiFileEdit() {
	let edit = LSPWorkspaceEdit(documentChanges: [
		LSPTextDocumentEdit(
			textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///a.swift", version: 2),
			edits: [LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "A"
			)]
		),
		LSPTextDocumentEdit(
			textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///b.swift", version: 4),
			edits: [LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
				newText: "B"
			)]
		),
	])
	#expect(throws: LSPWorkspaceEditApplyError.staleDocumentVersion(uri: "file:///b.swift", expected: 5, received: 4)) {
		try LSPWorkspaceEditApply.apply(
			edit,
			sources: ["file:///a.swift": "a", "file:///b.swift": "b"],
			documentVersions: ["file:///a.swift": 2, "file:///b.swift": 5]
		)
	}
}
