import ItsyEditor
import Testing

@Test func gitHunkIndicatorBuilderMarksAddsModificationsAndDeletes() {
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [
		DiffHunk(oldStart: 1, oldCount: 5, newStart: 1, newCount: 6, lines: [
			.context("one"),
			.add("inserted"),
			.context("two"),
			.remove("old"),
			.add("new"),
			.context("three"),
			.remove("gone"),
			.context("four"),
		]),
	])

	let indicators = GitHunkIndicatorBuilder.indicators(files: [file])

	#expect(indicators == [
		GitHunkIndicator(line: 1, kind: .added),
		GitHunkIndicator(line: 3, kind: .modified),
		GitHunkIndicator(line: 5, kind: .deleted),
	])
}

@Test func gitHunkIndicatorBuilderMarksExtraReplacementLines() {
	let file = DiffFile(oldPath: "file.txt", newPath: "file.txt", hunks: [
		DiffHunk(oldStart: 1, oldCount: 2, newStart: 1, newCount: 3, lines: [
			.remove("old a"),
			.remove("old b"),
			.add("new a"),
			.add("new b"),
			.add("new c"),
		]),
	])

	let indicators = GitHunkIndicatorBuilder.indicators(files: [file])

	#expect(indicators == [
		GitHunkIndicator(line: 0, kind: .modified),
		GitHunkIndicator(line: 1, kind: .modified),
		GitHunkIndicator(line: 2, kind: .added),
	])
}
