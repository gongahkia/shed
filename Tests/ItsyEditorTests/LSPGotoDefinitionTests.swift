import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func lspDefinitionResultDecodesSingleLocation() throws {
	let json = """
	{ "uri": "file:///tmp/a.swift", "range": { "start": { "line": 1, "character": 2 }, "end": { "line": 1, "character": 5 } } }
	""".data(using: .utf8)!
	let result = try LSPDefinitionResult(decoding: json)
	guard case let .single(location) = result else {
		Issue.record("expected single, got \(result)")
		return
	}
	#expect(location.uri == "file:///tmp/a.swift")
	#expect(location.range.start.line == 1)
}

@Test func lspDefinitionResultDecodesArrayOfLocations() throws {
	let json = """
	[
		{ "uri": "file:///a", "range": { "start": { "line": 0, "character": 0 }, "end": { "line": 0, "character": 1 } } },
		{ "uri": "file:///b", "range": { "start": { "line": 1, "character": 0 }, "end": { "line": 1, "character": 1 } } }
	]
	""".data(using: .utf8)!
	let result = try LSPDefinitionResult(decoding: json)
	guard case let .multiple(locations) = result else {
		Issue.record("expected multiple, got \(result)")
		return
	}
	#expect(locations.count == 2)
	#expect(locations[1].uri == "file:///b")
}

@Test func lspDefinitionResultDecodesArrayOfLocationLinksAndFlattensToLocations() throws {
	let json = """
	[
		{
			"originSelectionRange": { "start": { "line": 0, "character": 0 }, "end": { "line": 0, "character": 1 } },
			"targetUri": "file:///x",
			"targetRange": { "start": { "line": 2, "character": 0 }, "end": { "line": 2, "character": 8 } },
			"targetSelectionRange": { "start": { "line": 2, "character": 3 }, "end": { "line": 2, "character": 6 } }
		}
	]
	""".data(using: .utf8)!
	let result = try LSPDefinitionResult(decoding: json)
	guard case let .linked(links) = result else {
		Issue.record("expected linked, got \(result)")
		return
	}
	#expect(links.first?.targetUri == "file:///x")
	let flat = result.locations
	#expect(flat.count == 1)
	#expect(flat[0].uri == "file:///x")
	#expect(flat[0].range.start.character == 3)
}

@Test func lspDefinitionResultReturnsNoneForEmptyArray() throws {
	let json = "[]".data(using: .utf8)!
	let result = try LSPDefinitionResult(decoding: json)
	#expect(result == .none)
	#expect(result.locations.isEmpty)
}

@Test func lspJumpHistoryPushAdvancesCursorAndTruncatesForwardBranch() {
	var history = LSPJumpHistory()
	#expect(history.canGoBack == false)
	#expect(history.canGoForward == false)
	let a = LSPJumpEntry(url: URL(fileURLWithPath: "/a"), line: 1, column: 1)
	let b = LSPJumpEntry(url: URL(fileURLWithPath: "/b"), line: 2, column: 1)
	let c = LSPJumpEntry(url: URL(fileURLWithPath: "/c"), line: 3, column: 1)
	history.push(a)
	history.push(b)
	history.push(c)
	#expect(history.count == 3)
	#expect(history.current == c)
	_ = history.goBack()
	_ = history.goBack()
	let d = LSPJumpEntry(url: URL(fileURLWithPath: "/d"), line: 4, column: 1)
	history.push(d)
	#expect(history.count == 2)
	#expect(history.current == d)
	#expect(history.canGoForward == false)
}

@Test func lspJumpHistoryGoBackAndForwardAreInverse() {
	var history = LSPJumpHistory()
	let a = LSPJumpEntry(url: URL(fileURLWithPath: "/a"), line: 1, column: 1)
	let b = LSPJumpEntry(url: URL(fileURLWithPath: "/b"), line: 2, column: 1)
	history.push(a)
	history.push(b)
	#expect(history.goBack() == a)
	#expect(history.canGoBack == false)
	#expect(history.goForward() == b)
	#expect(history.canGoForward == false)
}

@Test func lspJumpHistoryClearResetsToEmpty() {
	var history = LSPJumpHistory()
	history.push(LSPJumpEntry(url: URL(fileURLWithPath: "/a"), line: 1, column: 1))
	history.clear()
	#expect(history.count == 0)
	#expect(history.current == nil)
	#expect(history.canGoBack == false)
}
