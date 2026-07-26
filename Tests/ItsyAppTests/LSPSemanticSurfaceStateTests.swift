import AppKit
@testable import ItsyApp
import ItsyEditor
import ItsyLSP
@testable import ItsyRender
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
	pipeline.invalidate(uri: uri, document: document)
	#expect(pipeline.collapsedStarts(for: uri).isEmpty)
	#expect(!pipeline.setAllFolds(collapsed: true, uri: uri, document: document))

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

@MainActor @Test func documentRefreshesLSPHighlightsWhenSelectionChanges() {
	_ = NSApplication.shared
	let document = ItsyDocument()
	document.editor = Editor(text: "x")
	let view = MetalTextView(frame: .zero)
	document.attach(view)
	var refreshCount = 0
	document.lspDocumentHighlightRefreshRequested = {
		refreshCount += 1
	}

	view.selectUTF8Range(0 ..< 0)
	view.selectUTF8Range(1 ..< 1)

	#expect(refreshCount == 1)
	document.detach(view)
}

@MainActor @Test func documentInvalidatesLSPStateOnlyAfterEdits() {
	_ = NSApplication.shared
	let document = ItsyDocument()
	let view = MetalTextView(frame: .zero)
	document.attach(view)
	var invalidationCount = 0
	var refreshCount = 0
	document.lspSurfaceInvalidationRequested = {
		invalidationCount += 1
	}
	document.lspSurfaceRefreshRequested = {
		refreshCount += 1
	}

	view.replaceUTF8Range(0 ..< 0, with: "x")

	#expect(invalidationCount == 1)
	#expect(refreshCount == 1)
	document.detach(view)
}

@MainActor @Test func documentRefreshesLSPStateWithoutInvalidatingOnViewportChanges() {
	_ = NSApplication.shared
	let document = ItsyDocument()
	document.editor = Editor(text: (0 ..< 20).map(String.init).joined(separator: "\n"))
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 400, height: 100))
	view.lineHeight = 20
	document.attach(view)
	var invalidationCount = 0
	var refreshCount = 0
	document.lspSurfaceInvalidationRequested = {
		invalidationCount += 1
	}
	document.lspSurfaceRefreshRequested = {
		refreshCount += 1
	}

	view.scroll(deltaX: 0, deltaY: -60)

	#expect(invalidationCount == 0)
	#expect(refreshCount == 1)
	document.detach(view)
}

@MainActor @Test func documentHighlightInvalidationPreservesInlayHints() {
	_ = NSApplication.shared
	let pipeline = EditorDecorationPipeline()
	let document = ItsyDocument()
	document.editor = Editor(text: "x")
	let view = MetalTextView(frame: .zero)
	document.attach(view)
	document.setLSPSemanticSurface(
		inlayHints: [.init(offset: 0, label: ": Int")],
		highlights: [0 ..< 1]
	)

	pipeline.invalidateDocumentHighlights(for: document)

	#expect(view.inlayHintAnnotations.count == 1)
	#expect(view.documentHighlightRanges.isEmpty)
	document.detach(view)
}
