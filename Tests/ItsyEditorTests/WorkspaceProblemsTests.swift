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

@Test func workspaceProblemParserAppliesCustomMatcherGroups() {
	let root = URL(fileURLWithPath: "/tmp/project", isDirectory: true)
	let matcher = WorkspaceProblemMatcher(
		id: "eslint",
		label: "eslint",
		pattern: #"^(.+)\((\d+),(\d+)\): (warning|error) (.+)$"#,
		severityGroup: 4,
		messageGroup: 5,
		source: "eslint"
	)

	let snapshot = WorkspaceProblemParser.parse(
		"src/app.ts(8,12): warning no unused vars",
		root: root,
		matchers: [matcher]
	)

	#expect(snapshot.problems == [
		WorkspaceProblem(path: "src/app.ts", line: 8, column: 12, severity: .warning, message: "no unused vars", source: "eslint"),
	])
}

@Test func workspaceProblemParserHandlesMultipleFilesSeveritiesAndInvalidCaptures() {
	let root = URL(fileURLWithPath: "/tmp/project", isDirectory: true)
	let matcher = WorkspaceProblemMatcher(
		id: "tool",
		label: "Tool",
		pattern: #"^(.+):(\d+):(\d+): (error|warning|info|hint): (.+)$"#,
		severityGroup: 4,
		messageGroup: 5,
		source: "tool"
	)
	let snapshot = WorkspaceProblemParser.parse(
		"Sources/B.swift:4:2: warning: second\nSources/A.swift:2:1: error: first\n/tmp/external.swift:8:3: hint: third",
		root: root,
		matchers: [matcher]
	)

	#expect(snapshot.problems == [
		WorkspaceProblem(path: "/tmp/external.swift", line: 8, column: 3, severity: .hint, message: "third", source: "tool"),
		WorkspaceProblem(path: "Sources/A.swift", line: 2, column: 1, severity: .error, message: "first", source: "tool"),
		WorkspaceProblem(path: "Sources/B.swift", line: 4, column: 2, severity: .warning, message: "second", source: "tool"),
	])
	#expect(snapshot.url(for: snapshot.problems[0]).path == "/tmp/external.swift")

	let invalid = WorkspaceProblemMatcher(id: "invalid", label: "Invalid", pattern: #"^(.+):(\d+)$"#, messageGroup: 3)
	#expect(WorkspaceProblemParser.parse("Sources/App.swift:3", root: root, matchers: [invalid]).problems.isEmpty)
}

@Test func workspaceProblemMatcherLoaderReadsWorkspaceTOML() throws {
	let fixture = try TemporaryProblemFixture()
	try fixture.write(".itsy/matchers.toml", """
	[matcher.eslint]
	label = "ESLint"
	pattern = "^(.+)\\\\((\\\\d+),(\\\\d+)\\\\): (warning|error) (.+)$"
	file_group = 1
	line_group = 2
	column_group = 3
	severity_group = 4
	message_group = 5
	source = "eslint"
	""")

	let matchers = try WorkspaceProblemMatcherLoader.load(from: fixture.root.appendingPathComponent(".itsy/matchers.toml"))

	#expect(matchers == [
		WorkspaceProblemMatcher(
			id: "eslint",
			label: "ESLint",
			pattern: #"^(.+)\((\d+),(\d+)\): (warning|error) (.+)$"#,
			fileGroup: 1,
			lineGroup: 2,
			columnGroup: 3,
			severityGroup: 4,
			messageGroup: 5,
			defaultSeverity: .error,
			source: "eslint"
		),
	])
}

@Test func workspaceProblemMatcherLoaderRejectsInvalidCaptureGroupsAndTaskSelection() throws {
	let fixture = try TemporaryProblemFixture()
	try fixture.write(".itsy/matchers.toml", """
	[matcher.swift]
	pattern = "^(.+):(\\\\d+): (.+)$"
	column_group = 3
	message_group = 3

	[matcher.eslint]
	pattern = "^(.+)\\\\((\\\\d+),(\\\\d+)\\\\): (.+)$"
	message_group = 4
	""")
	let task = WorkspaceTask(
		id: "project:lint",
		label: "Lint",
		source: .workspaceTaskFile,
		command: "/usr/bin/true",
		problemMatchers: ["eslint"]
	)

	let selected = WorkspaceProblemMatcherDiscovery.matchers(for: task, root: fixture.root)
	#expect(selected.map(\.id) == ["eslint"])

	try fixture.write(".itsy/invalid-matchers.toml", """
	[matcher.bad]
	pattern = "^(.+):(\\\\d+)$"
	""")
	#expect(throws: WorkspaceProblemMatcherLoaderError.invalidCaptureGroup(matcherID: "bad", group: 3)) {
		_ = try WorkspaceProblemMatcherLoader.load(from: fixture.root.appendingPathComponent(".itsy/invalid-matchers.toml"))
	}
}

@Test func workspaceProblemMatcherDiscoveryIncludesExtensionMatchers() throws {
	let fixture = try TemporaryProblemFixture()
	try fixture.write(".itsy/extensions/eslint.json", """
	{
	  "schemaVersion": 2,
	  "identifier": "dev.example.eslint",
	  "name": "ESLint",
	  "version": "1.0.0",
	  "contributes": {
	    "problemMatchers": [
	      {
	        "id": "eslint",
	        "label": "ESLint",
	        "pattern": "^(.+)\\\\((\\\\d+),(\\\\d+)\\\\): (warning|error) (.+)$",
	        "severity_group": 4,
	        "message_group": 5,
	        "source": "eslint"
	      }
	    ]
	  }
	}
	""")

	let matcher = try #require(WorkspaceProblemMatcherDiscovery.discover(root: fixture.root).first)
	let snapshot = WorkspaceProblemParser.parse(
		"src/app.ts(8,12): error no undef",
		root: fixture.root,
		matchers: [matcher]
	)

	#expect(matcher.id == "extension:dev.example.eslint:eslint")
	#expect(snapshot.problems.first?.message == "no undef")
	#expect(snapshot.problems.first?.severity == .error)
}

private final class TemporaryProblemFixture {
	let root: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-problems-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ path: String, _ contents: String, fileManager: FileManager = .default) throws {
		let url = root.appendingPathComponent(path)
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
