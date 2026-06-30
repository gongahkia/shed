import ItsyEditor
import Testing

@Test func diffTextRendererBuildsUnifiedTextWithLineMetadata() throws {
	let file = DiffFile(
		oldPath: "Sources/App.swift",
		newPath: "Sources/App.swift",
		hunks: [
			DiffHunk(
				oldStart: 1,
				oldCount: 2,
				newStart: 1,
				newCount: 2,
				lines: [
					.context("let a = 1"),
					.remove("let b = old"),
					.add("let b = new"),
				]
			),
		]
	)

	let document = DiffTextRenderer.unified(files: [file])

	#expect(document.text.split(separator: "\n", omittingEmptySubsequences: false).prefix(7).map(String.init) == [
		"diff --git a/Sources/App.swift b/Sources/App.swift",
		"--- a/Sources/App.swift",
		"+++ b/Sources/App.swift",
		"@@ -1,2 +1,2 @@",
		" let a = 1",
		"-let b = old",
		"+let b = new",
	])
	#expect(document.lines[4].kind == .context)
	#expect(document.lines[5].kind == .removal)
	#expect(document.lines[6].content == "let b = new")
	#expect(document.lines[6].contentRange?.lowerBound == document.lines[6].fullRange.lowerBound + 1)
}

@Test func diffTextRendererAlignsSideBySideRemoveAddGroups() throws {
	let file = DiffFile(
		oldPath: "file.txt",
		newPath: "file.txt",
		hunks: [
			DiffHunk(
				oldStart: 9,
				oldCount: 3,
				newStart: 9,
				newCount: 3,
				lines: [
					.context("same"),
					.remove("old one"),
					.remove("old two"),
					.add("new one"),
					.context("tail"),
				]
			),
		]
	)

	let rendered = DiffTextRenderer.sideBySide(files: [file])
	let oldLines = rendered.old.text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
	let newLines = rendered.new.text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)

	#expect(oldLines[2] == " 9   same")
	#expect(newLines[2] == " 9   same")
	#expect(oldLines[3] == "10 - old one")
	#expect(newLines[3] == "10 + new one")
	#expect(oldLines[4] == "11 - old two")
	#expect(newLines[4] == "     ")
	#expect(oldLines[5] == "12   tail")
	#expect(newLines[5] == "11   tail")
	#expect(rendered.old.lines.count == rendered.new.lines.count)
}

@Test func diffTextRendererBuildsSyntheticNewFile() throws {
	let file = DiffTextRenderer.newFile(path: "new.txt", contents: "one\ntwo\n")

	#expect(file.isNewFile)
	#expect(file.oldPath == nil)
	#expect(file.newPath == "new.txt")
	#expect(file.hunks == [
		DiffHunk(oldStart: 0, oldCount: 0, newStart: 1, newCount: 2, lines: [
			.add("one"),
			.add("two"),
		]),
	])
}

@Test func diffPatchBuilderBuildsSingleHunkPatchWithFileHeaders() {
	let hunk = DiffHunk(oldStart: 7, oldCount: 2, newStart: 7, newCount: 2, lines: [
		.context("same"),
		.remove("old"),
		.add("new"),
	])
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", indexLine: "index 1111111..2222222 100644", hunks: [hunk])

	let patch = DiffPatchBuilder.patch(file: file, hunk: hunk)

	#expect(patch == """
	diff --git a/file.txt b/file.txt
	index 1111111..2222222 100644
	--- a/file.txt
	+++ b/file.txt
	@@ -7,2 +7,2 @@
	 same
	-old
	+new

	""")
}
