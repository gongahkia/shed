import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubReviewSubmissionUsesExplicitConfirmationAndRefreshesState() async throws {
	let executor = GitHubReviewSubmissionFixtureExecutor([
		.init(exitStatus: 0),
		.init(exitStatus: 0, standardOutput: detailJSON()),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let bridge = GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor)
	let service = GitHubReviewSubmissionService(bridge: bridge)
	let intent = try GitHubReviewSubmissionIntent(pullRequestNumber: 7, action: .comment, body: "Looks good overall")
	let confirmation = GitHubReviewSubmissionPlanner.requestConfirmation(for: intent)
	#expect(confirmation.title == "Submit review comment")
	let result = try await service.submit(confirmation, repository: GitHubRepositoryName("owner/repo"), workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .submitted(action, refreshed) = result, case let .ready(detail) = refreshed else {
		Issue.record("expected submitted refreshed review")
		return
	}
	#expect(action == .comment)
	#expect(detail.reviewDecision == "APPROVED")
	let calls = await executor.arguments
	#expect(calls[0] == ["pr", "review", "7", "--repo", "owner/repo", "--body", "Looks good overall", "--comment"])
	#expect(!calls[0].contains("--request-changes"))
}

@Test func gitHubReviewSubmissionKeepsActionsSeparateAndReportsFailures() async throws {
	let repository = try GitHubRepositoryName("owner/repo")
	let executor = GitHubReviewSubmissionFixtureExecutor([
		.init(exitStatus: 1, standardError: "review rejected"),
		.init(exitStatus: 0),
		.init(exitStatus: 1, standardError: "refresh unavailable"),
	])
	let service = GitHubReviewSubmissionService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let approve = GitHubReviewSubmissionPlanner.requestConfirmation(for: try GitHubReviewSubmissionIntent(pullRequestNumber: 7, action: .approve, body: "Approved"))
	let failed = try await service.submit(approve, repository: repository, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .failed(action, .processFailure) = failed else {
		Issue.record("expected failed approval")
		return
	}
	#expect(action == .approve)
	let changes = GitHubReviewSubmissionPlanner.requestConfirmation(for: try GitHubReviewSubmissionIntent(pullRequestNumber: 7, action: .requestChanges, body: "Please revise"))
	let refreshedFailure = try await service.submit(changes, repository: repository, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .submitted(action, .failed(error)) = refreshedFailure else {
		Issue.record("expected submitted request-changes with refresh failure")
		return
	}
	#expect(action == .requestChanges)
	#expect(error == .processFailure(GitHubCLIJSONDiagnostics(arguments: ["pr", "view", "7", "--json", "baseRefName,body,commits,files,headRefName,headRepositoryOwner,isDraft,mergeStateStatus,mergeable,number,reviewDecision,state,title,url"], result: .init(exitStatus: 1, standardError: "refresh unavailable"))))
	let calls = await executor.arguments
	#expect(calls[0].last == "--approve")
	#expect(calls[1].last == "--request-changes")
	#expect(service.cancel(changes) == .cancelled)
}

@Test func gitHubReviewSubmissionPropagatesCancellation() async throws {
	let executor = GitHubReviewSubmissionFixtureExecutor([], throwsCancellation: true)
	let service = GitHubReviewSubmissionService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let confirmation = GitHubReviewSubmissionPlanner.requestConfirmation(for: try GitHubReviewSubmissionIntent(pullRequestNumber: 7, action: .comment, body: "Cancelled"))
	await #expect(throws: CancellationError.self) {
		_ = try await service.submit(confirmation, repository: GitHubRepositoryName("owner/repo"), workspaceURL: URL(fileURLWithPath: "/workspace"))
	}
}

private func detailJSON() -> String {
	#"{"number":7,"url":"https://github.com/owner/repo/pull/7","title":"Review fixture","body":"Body","state":"OPEN","isDraft":false,"headRefName":"feature","headRepositoryOwner":{"login":"owner"},"baseRefName":"main","reviewDecision":"APPROVED","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","commits":[],"files":[]}"#
}

private actor GitHubReviewSubmissionFixtureExecutor: GitHubCLIJSONExecuting {
	private var results: [GitHubCLIProcessResult]
	private let throwsCancellation: Bool
	private var recorded: [[String]] = []

	init(_ results: [GitHubCLIProcessResult], throwsCancellation: Bool = false) {
		self.results = results
		self.throwsCancellation = throwsCancellation
	}

	var arguments: [[String]] { recorded }

	func run(executableURL _: URL, arguments: [String], workingDirectoryURL _: URL?) async throws -> GitHubCLIProcessResult {
		recorded.append(arguments)
		if throwsCancellation { throw CancellationError() }
		return results.isEmpty ? GitHubCLIProcessResult(exitStatus: 1) : results.removeFirst()
	}
}
