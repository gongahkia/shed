import ItsyEditor
import Testing

@Test func workspaceEditPreviewRequiresConfirmationAndRollsBackMultiFileApply() throws {
	let resolved = [
		LSPWorkspaceEditApply.ResolvedFile(uri: "file:///a.swift", updatedText: "A"),
		LSPWorkspaceEditApply.ResolvedFile(uri: "file:///b.swift", updatedText: "B"),
	]
	let preview = try LSPWorkspaceEditPreview(resolved: resolved, sources: [
		"file:///a.swift": "a",
		"file:///b.swift": "b",
	])
	#expect(preview.requiresConfirmation)
	var state = ["file:///a.swift": "a", "file:///b.swift": "b"]
	do {
		try LSPWorkspaceEditTransaction.commit(
			preview.files,
			apply: { file in
				if file.uri == "file:///b.swift" {
					throw LSPWorkspaceEditApplyError.editFailed(uri: file.uri, underlying: "injected")
				}
				state[file.uri] = file.updatedText
			},
			rollback: { file in
				state[file.uri] = file.originalText
			}
		)
		Issue.record("expected injected failure")
	} catch {}
	#expect(state == ["file:///a.swift": "a", "file:///b.swift": "b"])
}
