import Foundation
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

@Test func diffTextRendererShowsBinaryAndModeChanges() {
	let file = DiffFile(oldPath: "tool", newPath: "tool", oldMode: "100644", newMode: "100755", isBinary: true)
	let rendered = DiffTextRenderer.unified(files: [file])

	#expect(rendered.text.contains("old mode 100644"))
	#expect(rendered.text.contains("new mode 100755"))
	#expect(rendered.text.contains("Binary files tool and tool differ"))
}

@Test func diffSelectionMapperMapsRenderedRangesToExactHunkLines() {
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", oldMode: "100644", newMode: "100755", hunks: [
		DiffHunk(oldStart: 2, oldCount: 1, newStart: 2, newCount: 1, lines: [.remove("old"), .add("new")]),
	])
	let document = DiffTextRenderer.unified(files: [file])
	let contexts = DiffSelectionMapper.contexts(files: [file], document: document)

	#expect(contexts.map(\.lineIndex) == [0, 1])
	#expect(DiffSelectionMapper.lineIndexes(selection: contexts[1].range, fileIndex: 0, hunkIndex: 0, contexts: contexts) == IndexSet(integer: 1))
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

@Test func diffPatchBuilderPreservesRenameAndNoNewlineMarkers() {
	let file = DiffFile(
		oldPath: "Sources/Old.swift",
		newPath: "Sources/New.swift",
		hunks: [
			DiffHunk(
				oldStart: 1,
				oldCount: 1,
				newStart: 1,
				newCount: 1,
				lines: [.remove("old"), .add("new")],
				noNewlineLineIndexes: [0, 1]
			),
		]
	)

	let patch = DiffPatchBuilder.patch(file: file, hunk: file.hunks[0])

	#expect(patch == """
	diff --git a/Sources/Old.swift b/Sources/New.swift
	rename from Sources/Old.swift
	rename to Sources/New.swift
	--- a/Sources/Old.swift
	+++ b/Sources/New.swift
	@@ -1 +1 @@
	-old
	\\ No newline at end of file
	+new
	\\ No newline at end of file

	""")
}

@Test func diffTextRendererShowsNoNewlineMarkersInBothModes() {
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [
		DiffHunk(oldStart: 1, oldCount: 1, newStart: 1, newCount: 1, lines: [.remove("old"), .add("new")], noNewlineLineIndexes: [0, 1]),
	])

	let unified = DiffTextRenderer.unified(files: [file])
	let sideBySide = DiffTextRenderer.sideBySide(files: [file])

	#expect(unified.text.components(separatedBy: "\\ No newline at end of file").count == 3)
	#expect(sideBySide.old.text.contains("\\ No newline at end of file"))
	#expect(sideBySide.new.text.contains("\\ No newline at end of file"))
}

@Test func diffPatchBuilderBuildsSelectedLinePatchForStaging() throws {
	let hunk = DiffHunk(oldStart: 1, oldCount: 5, newStart: 1, newCount: 5, lines: [
		.context("one"),
		.remove("two"),
		.add("two changed"),
		.context("three"),
		.remove("four"),
		.add("four changed"),
		.context("five"),
	])
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", indexLine: "index 1111111..2222222 100644", hunks: [hunk])

	let patch = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: IndexSet(integersIn: 1 ..< 3), operation: .stage)

	#expect(patch == """
	diff --git a/file.txt b/file.txt
	index 1111111..2222222 100644
	--- a/file.txt
	+++ b/file.txt
	@@ -1,5 +1,5 @@
	 one
	-two
	+two changed
	 three
	 four
	 five

	""")
}

@Test func diffPatchBuilderBuildsSelectedLinePatchForReverseUnstaging() throws {
	let hunk = DiffHunk(oldStart: 1, oldCount: 5, newStart: 1, newCount: 5, lines: [
		.context("one"),
		.remove("two"),
		.add("two changed"),
		.context("three"),
		.remove("four"),
		.add("four changed"),
		.context("five"),
	])
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", indexLine: "index 1111111..2222222 100644", hunks: [hunk])

	let patch = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: IndexSet(integersIn: 4 ..< 6), operation: .unstage)

	#expect(patch == """
	diff --git a/file.txt b/file.txt
	index 1111111..2222222 100644
	--- a/file.txt
	+++ b/file.txt
	@@ -1,5 +1,5 @@
	 one
	 two changed
	 three
	-four
	+four changed
	 five

	""")
}

@Test func diffPatchBuilderRejectsInvalidLineSelections() throws {
	let hunk = DiffHunk(oldStart: 1, oldCount: 2, newStart: 1, newCount: 2, lines: [
		.context("same"),
		.remove("old"),
		.add("new"),
	])
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [hunk])

	#expect(throws: DiffPatchBuilderError.emptySelection) {
		_ = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: IndexSet(), operation: .stage)
	}
	#expect(throws: DiffPatchBuilderError.nonContiguousSelection) {
		_ = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: IndexSet([1, 3]), operation: .stage)
	}
	#expect(throws: DiffPatchBuilderError.selectionIncludesContext) {
		_ = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: IndexSet(integer: 0), operation: .stage)
	}
}
