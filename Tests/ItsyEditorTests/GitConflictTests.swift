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
