import ItsyEditor
import Testing

@Test func unifiedDiffParserParsesHunksAndLineKinds() throws {
	let diff = """
	diff --git a/Sources/App.swift b/Sources/App.swift
	index 1111111..2222222 100644
	--- a/Sources/App.swift
	+++ b/Sources/App.swift
	@@ -1,3 +1,4 @@
	 import Foundation
	-let oldName = 1
	+let newName = 2
	+print(newName)
	 finalLine()
	"""

	let files = try UnifiedDiffParser.parse(diff)

	#expect(files == [
		DiffFile(
			oldPath: "Sources/App.swift",
			newPath: "Sources/App.swift",
			indexLine: "index 1111111..2222222 100644",
			hunks: [
				DiffHunk(
					oldStart: 1,
					oldCount: 3,
					newStart: 1,
					newCount: 4,
					lines: [
						.context("import Foundation"),
						.remove("let oldName = 1"),
						.add("let newName = 2"),
						.add("print(newName)"),
						.context("finalLine()"),
					]
				),
			]
		),
	])
}

@Test func unifiedDiffParserHandlesNewFileModeAndDevNull() throws {
	let diff = """
	diff --git a/README.md b/README.md
	new file mode 100644
	index 0000000..3333333
	--- /dev/null
	+++ b/README.md
	@@ -0,0 +1,2 @@
	+# Itsy
	+native editor
	"""

	let file = try #require(try UnifiedDiffParser.parse(diff).first)

	#expect(file.oldPath == nil)
	#expect(file.newPath == "README.md")
	#expect(file.isNewFile)
	#expect(file.newMode == "100644")
	#expect(file.hunks.first?.oldCount == 0)
	#expect(file.hunks.first?.lines == [
		.add("# Itsy"),
		.add("native editor"),
	])
}

@Test func unifiedDiffParserHandlesRenameHeadersAndOmittedCounts() throws {
	let diff = """
	diff --git a/Sources/Old.swift b/Sources/New.swift
	similarity index 92%
	rename from Sources/Old.swift
	rename to Sources/New.swift
	old mode 100644
	new mode 100755
	--- a/Sources/Old.swift
	+++ b/Sources/New.swift
	@@ -7 +7 @@
	-let value = old()
	+let value = new()
	\\ No newline at end of file
	"""

	let file = try #require(try UnifiedDiffParser.parse(diff).first)

	#expect(file.oldPath == "Sources/Old.swift")
	#expect(file.newPath == "Sources/New.swift")
	#expect(file.indexLine == nil)
	#expect(file.oldMode == "100644")
	#expect(file.newMode == "100755")
	#expect(file.hunks == [
		DiffHunk(
			oldStart: 7,
			oldCount: 1,
			newStart: 7,
			newCount: 1,
			lines: [
				.remove("let value = old()"),
				.add("let value = new()"),
			],
			noNewlineLineIndexes: [1]
		),
	])
}

@Test func unifiedDiffParserKeepsIndexHeaderForPatchSynthesis() throws {
	let diff = """
	diff --git a/file.txt b/file.txt
	index 1111111..2222222 100644
	--- a/file.txt
	+++ b/file.txt
	@@ -1 +1 @@
	-old
	+new
	"""

	let file = try #require(try UnifiedDiffParser.parse(diff).first)

	#expect(file.indexLine == "index 1111111..2222222 100644")
}

@Test func unifiedDiffParserMarksBinaryDiffs() throws {
	let diff = """
	diff --git a/image.png b/image.png
	index 1111111..2222222 100644
	Binary files a/image.png and b/image.png differ
	"""

	let file = try #require(try UnifiedDiffParser.parse(diff).first)

	#expect(file.isBinary)
	#expect(file.hunks.isEmpty)
}
