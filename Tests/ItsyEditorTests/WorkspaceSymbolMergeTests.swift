import Foundation
import ItsyEditor
import Testing

@Test func workspaceSymbolMergePrefersLanguageServerAtSameIdentity() {
	let local = WorkspaceSymbol(name: "render", kind: .function, relativePath: "Sources/App.swift", line: 3, column: 1)
	let lsp = WorkspaceSymbol(name: "render", kind: .function, relativePath: "Sources/App.swift", line: 4, column: 7, signature: "func render()")
	let merged = WorkspaceSymbolMerge.preferringLanguageServer([lsp], over: [local])

	#expect(merged == [WorkspaceSymbol(name: "render", kind: .function, relativePath: "Sources/App.swift", line: 4, column: 7, signature: "func render()", source: .languageServer)])
}

@Test func workspaceSymbolMergeRetainsUnmatchedLocalSymbols() {
	let local = WorkspaceSymbol(name: "fallback", kind: .variable, relativePath: "Sources/App.swift", line: 8, column: 1)
	let lsp = WorkspaceSymbol(name: "server", kind: .type, relativePath: "Sources/App.swift", line: 1, column: 1)
	let merged = WorkspaceSymbolMerge.preferringLanguageServer([lsp], over: [local])

	#expect(merged.map(\.name) == ["server", "fallback"])
	#expect(merged.map(\.source) == [.languageServer, .local])
}

@Test func workspaceSymbolMergeKeepsLocalFileSymbolsMissingFromLSP() {
	let local = [
		WorkspaceSymbol(name: "kept", kind: .function, relativePath: "Sources/App.swift", line: 3, column: 1),
		WorkspaceSymbol(name: "replaced", kind: .function, relativePath: "Sources/App.swift", line: 8, column: 1),
	]
	let lsp = [
		WorkspaceSymbol(name: "replaced", kind: .function, relativePath: "Sources/App.swift", line: 9, column: 2),
	]

	let merged = WorkspaceSymbolMerge.preferringLanguageServer(lsp, over: local)

	#expect(merged.map(\.name) == ["replaced", "kept"])
	#expect(merged.map(\.source) == [.languageServer, .local])
}

@Test func workspaceSymbolDecodesPreSourceCacheAsLocal() throws {
	let data = Data(#"{"name":"cached","kind":"function","relativePath":"Sources/App.swift","line":1,"column":1}"#.utf8)
	let symbol = try JSONDecoder().decode(WorkspaceSymbol.self, from: data)

	#expect(symbol.source == .local)
}
