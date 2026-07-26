@testable import ItsyApp
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
