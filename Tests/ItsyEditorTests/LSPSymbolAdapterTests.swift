import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func documentSymbolAdapterFlattensHierarchicalChildren() {
	let symbols = [
		LSPDocumentSymbol(
			name: "AppShell",
			kind: .class,
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 5, character: 0)),
			selectionRange: LSPRange(start: LSPPosition(line: 0, character: 7), end: LSPPosition(line: 0, character: 15)),
			children: [
				LSPDocumentSymbol(
					name: "renderFrame",
					kind: .method,
					range: LSPRange(start: LSPPosition(line: 1, character: 4), end: LSPPosition(line: 3, character: 5)),
					selectionRange: LSPRange(start: LSPPosition(line: 1, character: 9), end: LSPPosition(line: 1, character: 20))
				),
			]
		),
	]
	let result = LSPSymbolAdapter.workspaceSymbols(from: symbols, relativePath: "Sources/App.swift")
	#expect(result.count == 2)
	#expect(result[0].name == "AppShell")
	#expect(result[0].kind == .type)
	#expect(result[0].line == 1)
	#expect(result[0].column == 8)
	#expect(result[1].name == "renderFrame")
	#expect(result[1].kind == .method)
	#expect(result[1].line == 2)
}

@Test func symbolInformationAdapterMapsUriToRelativePath() {
	let info = [
		LSPSymbolInformation(
			name: "Foo",
			kind: .struct,
			location: LSPLocation(
				uri: "file:///tmp/itsy-symbols/Sources/Foo.swift",
				range: LSPRange(start: LSPPosition(line: 2, character: 6), end: LSPPosition(line: 2, character: 9))
			)
		),
	]
	let root = URL(fileURLWithPath: "/tmp/itsy-symbols")
	let result = LSPSymbolAdapter.workspaceSymbols(from: info, root: root)
	#expect(result.count == 1)
	#expect(result[0].name == "Foo")
	#expect(result[0].kind == .type)
	#expect(result[0].relativePath == "Sources/Foo.swift")
	#expect(result[0].line == 3)
	#expect(result[0].column == 7)
}

@Test func symbolInformationAdapterDropsLocationsOutsideRoot() {
	let info = [
		LSPSymbolInformation(
			name: "Stray",
			kind: .class,
			location: LSPLocation(
				uri: "file:///somewhere/else/Foo.swift",
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 5))
			)
		),
	]
	let result = LSPSymbolAdapter.workspaceSymbols(from: info, root: URL(fileURLWithPath: "/tmp/itsy-symbols"))
	#expect(result.isEmpty)
}

@Test func symbolKindMappingCollapsesLSPKindsIntoWorkspaceCategories() {
	#expect(LSPSymbolAdapter.mapKind(.class) == .type)
	#expect(LSPSymbolAdapter.mapKind(.struct) == .type)
	#expect(LSPSymbolAdapter.mapKind(.enum) == .type)
	#expect(LSPSymbolAdapter.mapKind(.interface) == .type)
	#expect(LSPSymbolAdapter.mapKind(.method) == .method)
	#expect(LSPSymbolAdapter.mapKind(.constructor) == .method)
	#expect(LSPSymbolAdapter.mapKind(.function) == .function)
	#expect(LSPSymbolAdapter.mapKind(.variable) == .variable)
	#expect(LSPSymbolAdapter.mapKind(.constant) == .variable)
	#expect(LSPSymbolAdapter.mapKind(.module) == .variable)
}
