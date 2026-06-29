import Foundation
import ItsyEditor
import Testing

@Test func workspaceProblemParserReadsCompilerStyleLines() {
	let root = URL(fileURLWithPath: "/tmp/project", isDirectory: true)
	let output = """
	Sources/App.swift:12:5: error: cannot find value
	Sources/App.swift:13: warning: unused result
	noise
	Sources/Other.swift:1:1: note: declared here
	"""

	let snapshot = WorkspaceProblemParser.parse(output, root: root)

	#expect(snapshot.problems == [
		WorkspaceProblem(path: "Sources/App.swift", line: 12, column: 5, severity: .error, message: "cannot find value", source: "task"),
		WorkspaceProblem(path: "Sources/App.swift", line: 13, severity: .warning, message: "unused result", source: "task"),
		WorkspaceProblem(path: "Sources/Other.swift", line: 1, column: 1, severity: .info, message: "declared here", source: "task"),
	])
	#expect(snapshot.errorCount == 1)
	#expect(snapshot.warningCount == 1)
	#expect(snapshot.url(for: snapshot.problems[0]).path == "/tmp/project/Sources/App.swift")
}
