import PicoEditor
import Testing

@Test func fuzzyMatcherScoresConsecutiveMatchesAboveSparseMatches() throws {
	let consecutive = try #require(FuzzyMatcher.score(candidate: "openDocument", query: "op"))
	let sparse = try #require(FuzzyMatcher.score(candidate: "openDocument", query: "on"))
	#expect(consecutive.score > sparse.score)
}

@Test func fuzzyMatcherScoresWordBoundaryMatchesAboveInlineMatches() throws {
	let boundary = try #require(FuzzyMatcher.score(candidate: "openDocument", query: "od"))
	let inline = try #require(FuzzyMatcher.score(candidate: "modelDocument", query: "od"))
	#expect(boundary.score > inline.score)
}

@Test func fuzzyMatcherRanksCommandCandidates() {
	let ranked = FuzzyMatcher.ranked(["openDocument", "saveDocument"], query: "ods", by: { $0 })
	#expect(ranked.first == "openDocument")
}

@Test func fuzzyMatcherFiltersUnmatchedCandidatesWhenRequested() {
	let ranked = FuzzyMatcher.ranked(["Open File", "Save File", "Close Window"], query: "sf", includeUnmatched: false, by: { $0 })
	#expect(ranked == ["Save File"])
}
