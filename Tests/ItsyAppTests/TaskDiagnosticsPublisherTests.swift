import AppKit
import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@Test @MainActor func taskDiagnosticsPublisherStreamsReplacesAndClearsTaskProblems() {
	_ = NSApplication.shared
	let root = URL(fileURLWithPath: "/tmp/itsy-task-diagnostics", isDirectory: true)
	let problems = ProblemsCoordinator(documentController: ItsyDocumentController())
	let task = WorkspaceTask(id: "watch", label: "watch", source: .workspaceTaskFile, command: "/usr/bin/true")
	let publisher = TaskDiagnosticsPublisher(problemsCoordinator: problems, task: task, root: root)
	defer { problems.closeProblemsForTesting() }

	publisher.append(.init(kind: .stderr, text: "Sources/App.swift:12:5: erro"))
	#expect(problems.problemCountForTesting == 0)
	publisher.append(.init(kind: .stderr, text: "r: streamed failure\n"))
	#expect(problems.problemsForTesting == [
		WorkspaceProblem(path: "Sources/App.swift", line: 12, column: 5, severity: .error, message: "streamed failure", source: "task"),
	])

	publisher.replace(stdout: "Sources/App.swift:18: warning: replacement warning\n", stderr: "")
	#expect(problems.problemsForTesting == [
		WorkspaceProblem(path: "Sources/App.swift", line: 18, severity: .warning, message: "replacement warning", source: "task"),
	])
	publisher.clear()
	#expect(problems.problemCountForTesting == 0)
}
