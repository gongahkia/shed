import Foundation
import ItsyEditor
import Testing

@Test func gitRemoteOperationClassifierDistinguishesRecoverableFailures() {
	#expect(GitRemoteOperationClassifier.classify(GitRemoteCommandResult(
		exitStatus: 128,
		output: "fatal: could not read Username for 'https://example.invalid'"
	)) == .failed(.authenticationRequired))
	#expect(GitRemoteOperationClassifier.classify(GitRemoteCommandResult(
		exitStatus: 1,
		output: "! [rejected] main -> main (non-fast-forward)"
	)) == .failed(.nonFastForward))
	#expect(GitRemoteOperationClassifier.classify(GitRemoteCommandResult(
		exitStatus: 128,
		output: "fatal: Could not resolve host: example.invalid"
	)) == .failed(.networkUnavailable))
	#expect(GitRemoteOperationClassifier.classify(GitRemoteCommandResult(
		exitStatus: 15,
		output: "terminated",
		wasCancelled: true
	)) == .failed(.cancelled))
	#expect(GitRemoteOperationClassifier.classify(GitRemoteCommandResult(exitStatus: 0, output: "up to date\n")) == .succeeded(output: "up to date\n"))
}

@Test func gitRemoteOperationCoordinatorDiscardsCanceledResult() async {
	let coordinator = GitRemoteOperationCoordinator()
	let runner = FixtureRemoteRunner(result: GitRemoteCommandResult(exitStatus: 1, output: "network is unreachable"), delay: 50_000_000)
	let task = Task {
		await coordinator.run(operation: .fetch, root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), runner: runner)
	}
	try? await Task.sleep(nanoseconds: 5_000_000)
	await coordinator.cancel()

	#expect(await task.value == nil)
}

@Test func gitWorkspaceSnapshotDisplaysAheadBehindState() {
	let snapshot = GitWorkspaceSnapshot(root: URL(fileURLWithPath: "/tmp/project", isDirectory: true), status: GitStatus(
		branch: GitBranchStatus(head: "main", ahead: 2, behind: 1)
	))

	#expect(snapshot.syncLabel == "main ↑2 ↓1")
}

private struct FixtureRemoteRunner: GitRemoteCommandRunning {
	var result: GitRemoteCommandResult
	var delay: UInt64 = 0

	func run(operation _: GitRemoteOperation, root _: URL) async -> GitRemoteCommandResult {
		if delay > 0 {
			try? await Task.sleep(nanoseconds: delay)
		}
		return result
	}
}
