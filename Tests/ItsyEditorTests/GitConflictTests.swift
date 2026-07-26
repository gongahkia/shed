import ItsyEditor
import Testing

@Test func gitConflictParserReadsMarkerRegions() {
	let text = """
	one
	<<<<<<< HEAD
	ours a
	ours b
	=======
	theirs
	>>>>>>> branch
	two

	"""

	let regions = GitConflictParser.parse(text)

	#expect(regions == [
		GitConflictRegion(
			startLine: 1,
			endLine: 7,
			oursLines: ["ours a", "ours b"],
			theirsLines: ["theirs"]
		),
	])
}

@Test func gitConflictParserSkipsDiff3BaseSection() {
	let text = """
	<<<<<<< ours
	ours
	||||||| base
	base
	=======
	theirs
	>>>>>>> theirs
	"""

	let region = GitConflictParser.parse(text).first

	#expect(region == GitConflictRegion(startLine: 0, endLine: 7, oursLines: ["ours"], theirsLines: ["theirs"]))
}

@Test func gitConflictParserResolvesSelectedRegion() {
	let text = """
	one
	<<<<<<< HEAD
	ours
	=======
	theirs
	>>>>>>> branch
	two
	"""

	#expect(GitConflictParser.resolvedText(text, regionIndex: 0, resolution: .ours) == """
	one
	ours
	two
	""")
	#expect(GitConflictParser.resolvedText(text, regionIndex: 0, resolution: .theirs) == """
	one
	theirs
	two
	""")
	#expect(GitConflictParser.resolvedText(text, regionIndex: 0, resolution: .both) == """
	one
	ours
	theirs
	two
	""")
}

@Test func gitConflictResolutionDocumentRequiresMarkerFreeTextBeforeStaging() throws {
	var document = GitConflictResolutionDocument(text: """
	one
	<<<<<<< ours
	ours
	=======
	theirs
	>>>>>>> theirs
	two
	""")

	#expect(document.hasUnresolvedMarkers)
	#expect(document.regions.count == 1)
	#expect(throws: GitConflictResolutionDocumentError.unresolvedMarkers) {
		try document.textForStaging()
	}
	document.resolve(regionIndex: 0, with: .both)
	#expect(!document.hasUnresolvedMarkers)
	#expect(try document.textForStaging() == """
	one
	ours
	theirs
	two
	""")
}

@Test func gitConflictResolutionDocumentRejectsMalformedMarkersBeforeStaging() {
	let document = GitConflictResolutionDocument(text: "<<<<<<< ours\nmanual edit\n")

	#expect(document.regions.isEmpty)
	#expect(document.hasUnresolvedMarkers)
	#expect(throws: GitConflictResolutionDocumentError.unresolvedMarkers) {
		try document.textForStaging()
	}
}
