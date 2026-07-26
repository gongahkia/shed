import Foundation
import ItsyEditor

@MainActor final class TaskDiagnosticsPublisher {
	let sourceID: String
	private let root: URL
	private let matchers: [WorkspaceProblemMatcher]
	private let problemsCoordinator: ProblemsCoordinator
	private var stdout = ""
	private var stderr = ""

	init(problemsCoordinator: ProblemsCoordinator, task: WorkspaceTask, root: URL) {
		self.problemsCoordinator = problemsCoordinator
		self.root = root.standardizedFileURL
		matchers = WorkspaceProblemMatcherDiscovery.matchers(for: task, root: root)
		sourceID = "task:\(self.root.path):\(task.id)"
	}

	func append(_ output: WorkspaceTaskOutput) {
		switch output.kind {
		case .stdout:
			stdout += output.text
		case .stderr:
			stderr += output.text
		}
		publish()
	}

	func replace(stdout: String, stderr: String) {
		self.stdout = stdout
		self.stderr = stderr
		publish()
	}

	func clear() {
		stdout = ""
		stderr = ""
		publish()
	}

	private func publish() {
		problemsCoordinator.setProblems(
			WorkspaceProblemParser.parse(stdout + "\n" + stderr, root: root, matchers: matchers),
			sourceID: sourceID
		)
	}
}
