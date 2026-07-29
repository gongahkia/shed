import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@MainActor @Test func lspPresentationStateTracksStatusSnapshotsAndRestartTargets() throws {
	let state = LSPPresentationState()
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-lsp-state"))
	let url = URL(fileURLWithPath: "/tmp/itsy-lsp-state/main.swift")

	let running = state.setStatus(key: key, status: "running", client: nil, lastError: nil, url: url)
	#expect(running.health == .ready)
	#expect(state.activeKey == key)
	let crashed = state.setStatus(key: key, status: "crashed", client: nil, lastError: "exit 9", url: url)
	#expect(crashed.health == .crashed)
	#expect(state.restartKey == key)
	#expect(state.restartURL == url)
	let snapshot = try #require(state.snapshot())
	#expect(snapshot.status == "crashed")
	#expect(snapshot.lastError == "exit 9")
}

@MainActor @Test func lspPresentationStateRejectsUnknownOutputAndBoundsStoredOutput() throws {
	let state = LSPPresentationState()
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-lsp-output"))
	#expect(!state.append(.init(kind: .process, text: "missing"), for: key))
	_ = state.setStatus(key: key, status: "starting", client: nil, lastError: nil, url: nil)
	for index in 0 ... 200 {
		#expect(state.append(.init(kind: .process, text: "line \(index)"), for: key))
	}

	let snapshot = try #require(state.snapshot())
	#expect(snapshot.output.count == 200)
	#expect(snapshot.output.first?.text == "line 1")
	#expect(snapshot.output.last?.text == "line 200")
}
