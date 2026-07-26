import Foundation
import ItsyLSP
import Testing

@Test func codeActionResponseDecodesArrayOfActions() throws {
	let json = """
	[
		{ "title": "Replace with `let`", "kind": "quickfix" },
		{ "title": "Refactor: extract function", "kind": "refactor.extract" }
	]
	""".data(using: .utf8)!
	let response = try LSPCodeActionResponse(decoding: json)
	guard case let .actions(actions) = response else {
		Issue.record("expected actions, got \(response)")
		return
	}
	#expect(actions.count == 2)
	#expect(actions[0].kind == .quickFix)
	#expect(actions[1].kind == .refactorExtract)
}

@Test func codeActionResponseDecodesArrayOfCommandsWhenServerSendsLegacyFormat() throws {
	let json = """
	[
		{ "title": "Run", "command": "swift.run", "arguments": [] }
	]
	""".data(using: .utf8)!
	let response = try LSPCodeActionResponse(decoding: json)
	guard case let .commands(commands) = response else {
		Issue.record("expected commands, got \(response)")
		return
	}
	#expect(commands.first?.command == "swift.run")
}

@Test func codeActionResponseReturnsNoneForEmptyArrayOrInvalidPayload() throws {
	let empty = try LSPCodeActionResponse(decoding: "[]".data(using: .utf8)!)
	#expect(empty == .none)
	let garbage = try LSPCodeActionResponse(decoding: "{}".data(using: .utf8)!)
	#expect(garbage == .none)
}

@Test func codeActionResponseDecodesMixedActionAndCommandArray() throws {
	let json = """
	[
		{ "title": "Fix", "kind": "quickfix", "data": { "id": 1 } },
		{ "title": "Run", "command": "swift.run", "arguments": [] }
	]
	""".data(using: .utf8)!
	let response = try LSPCodeActionResponse(decoding: json)
	guard case let .mixed(entries) = response else {
		Issue.record("expected mixed, got \(response)")
		return
	}
	#expect(entries.map(\.title) == ["Fix", "Run"])
	guard case let .action(action) = entries[0] else {
		Issue.record("expected first entry action")
		return
	}
	#expect(action.data == .object(["id": .int(1)]))
	guard case let .command(command) = entries[1] else {
		Issue.record("expected second entry command")
		return
	}
	#expect(command.command == "swift.run")
}

@Test func codeActionResponseQuickFixFilterKeepsUntyped() throws {
	let json = """
	[
		{ "title": "Quick fix", "kind": "quickfix" },
		{ "title": "Refactor", "kind": "refactor.extract" },
		{ "title": "Untyped" }
	]
	""".data(using: .utf8)!
	let response = try LSPCodeActionResponse(decoding: json)
	let filtered = response.filteredQuickFixes()
	#expect(filtered.map(\.title) == ["Quick fix", "Untyped"])
}

@Test func codeActionRoundTripsThroughCodableWithEmbeddedWorkspaceEdit() throws {
	let action = LSPCodeAction(
		title: "Add missing import",
		kind: .quickFix,
		disabled: LSPCodeActionDisabled(reason: "not available"),
		edit: LSPWorkspaceEdit(changes: [
			"file:///a.swift": [
				LSPTextEdit(
					range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
					newText: "import Foundation\n"
				),
			],
		]),
		data: .object(["id": .int(3)])
	)
	let data = try JSONEncoder().encode(action)
	let decoded = try JSONDecoder().decode(LSPCodeAction.self, from: data)
	#expect(decoded == action)
}
