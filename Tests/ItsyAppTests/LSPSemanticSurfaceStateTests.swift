import AppKit
@testable import ItsyApp
import ItsyLSP
import Testing

@Test func semanticTokenCacheRejectsStaleRefreshesAndInvalidatesOnlyTargetURI() {
	let firstURI = "file:///tmp/first.swift"
	let secondURI = "file:///tmp/second.swift"
	let firstState = LSPSemanticTokenState(resultId: "first", data: [0, 0, 5, 0, 0])
	let secondState = LSPSemanticTokenState(resultId: "second", data: [1, 0, 4, 1, 0])
	var cache = LSPSemanticTokenCache()

	let acceptedFirst = cache.replace(firstState, for: firstURI, generation: 1, currentGeneration: 1)
	let acceptedSecond = cache.replace(secondState, for: secondURI, generation: 2, currentGeneration: 2)
	let acceptedStale = cache.replace(secondState, for: firstURI, generation: 1, currentGeneration: 2)
	#expect(acceptedFirst)
	#expect(acceptedSecond)
	#expect(!acceptedStale)
	#expect(cache.state(for: firstURI) == firstState)

	cache.invalidate(firstURI)

	#expect(cache.state(for: firstURI) == nil)
	#expect(cache.state(for: secondURI) == secondState)
}

@MainActor @Test func editorDecorationPipelinePrunesStaleFoldsAndRejectsEmptyFoldCommands() {
	_ = NSApplication.shared
	let pipeline = EditorDecorationPipeline()
	let document = ItsyDocument()
	let uri = "file:///tmp/decoration.swift"
	let fold = LSPFoldingRange(startLine: 2, endLine: 5)
	pipeline.apply(
		uri: uri,
		content: "",
		document: document,
		semanticSpans: [],
		inlayHints: [],
		foldingRanges: [fold],
		documentHighlights: []
	)
	#expect(pipeline.toggleFold(startLine: 2, uri: uri, document: document))
	#expect(pipeline.collapsedStarts(for: uri) == Set([2]))

	pipeline.apply(
		uri: uri,
		content: "",
		document: document,
		semanticSpans: [],
		inlayHints: [],
		foldingRanges: [],
		documentHighlights: []
	)
	#expect(pipeline.collapsedStarts(for: uri).isEmpty)
	#expect(!pipeline.setAllFolds(collapsed: true, uri: uri, document: document))
}
