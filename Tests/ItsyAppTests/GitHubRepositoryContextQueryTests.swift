import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubRepositoryContextQueryReturnsCurrentBranchPRAndConfiguredLists() async throws {
	let executor = GitHubRepositoryFixtureExecutor([
		.init(exitStatus: 0, standardOutput: #"{"nameWithOwner":"owner/repo","url":"https://github.com/owner/repo","defaultBranchRef":{"name":"main"}}"#),
		.init(exitStatus: 0, standardOutput: pullRequestJSON(number: 7)),
		.init(exitStatus: 0, standardOutput: "[\(pullRequestJSON(number: 8))]"),
	])
	let query = GitHubRepositoryContextQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let configuration = try GitHubPullRequestListConfiguration(title: "Feature PRs", limit: 5, headRefName: GitHubCLIReferenceName("feature"))
	let state = try await query.refresh(workspaceURL: URL(fileURLWithPath: "/workspace"), expectedRepository: "owner/repo", pullRequestLists: [configuration])
	guard case let .ready(context) = state else {
		Issue.record("expected ready context")
		return
	}
	#expect(context.nameWithOwner == "owner/repo")
	#expect(context.defaultBranchName == "main")
	#expect(context.currentBranchPullRequest?.number == 7)
	#expect(context.pullRequestLists == [GitHubPullRequestList(title: "Feature PRs", pullRequests: [pullRequest(number: 8)])])
	let commands = await executor.arguments
	#expect(commands.count == 3)
	#expect(commands[1][0...1] == ["pr", "view"])
	#expect(commands[2].contains("--head"))
	#expect(commands[2].contains("feature"))
}

@Test func gitHubRepositoryContextQueryHandlesMismatchEmptyAndQueryFailures() async throws {
	let mismatchExecutor = GitHubRepositoryFixtureExecutor([.init(exitStatus: 0, standardOutput: #"{"nameWithOwner":"other/repo","url":"https://github.com/other/repo","defaultBranchRef":null}"#)])
	let mismatchQuery = GitHubRepositoryContextQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: mismatchExecutor))
	let mismatch = try await mismatchQuery.refresh(workspaceURL: URL(fileURLWithPath: "/workspace"), expectedRepository: "owner/repo")
	#expect(mismatch == .repositoryMismatch(expected: "owner/repo", actual: "other/repo"))

	let emptyExecutor = GitHubRepositoryFixtureExecutor([
		.init(exitStatus: 0, standardOutput: #"{"nameWithOwner":"owner/repo","url":"https://github.com/owner/repo","defaultBranchRef":null}"#),
		.init(exitStatus: 1, standardError: "no pull requests found for branch \"main\""),
		.init(exitStatus: 0, standardOutput: "[]"),
	])
	let emptyQuery = GitHubRepositoryContextQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: emptyExecutor))
	let list = try GitHubPullRequestListConfiguration(title: "Empty", limit: 1)
	let empty = try await emptyQuery.refresh(workspaceURL: URL(fileURLWithPath: "/workspace"), pullRequestLists: [list])
	guard case let .ready(context) = empty else {
		Issue.record("expected empty ready context")
		return
	}
	#expect(context.currentBranchPullRequest == nil)
	#expect(context.pullRequestLists.first?.pullRequests == [])

	let failedExecutor = GitHubRepositoryFixtureExecutor([
		.init(exitStatus: 0, standardOutput: #"{"nameWithOwner":"owner/repo","url":"https://github.com/owner/repo","defaultBranchRef":null}"#),
		.init(exitStatus: 1, standardError: "network unavailable"),
	])
	let failedQuery = GitHubRepositoryContextQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: failedExecutor))
	let failure = try await failedQuery.refresh(workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .queryFailed(operation, error) = failure else {
		Issue.record("expected query failure")
		return
	}
	#expect(operation == .currentBranchPullRequest)
	#expect(error.exitStatus == 1)
}

@Test func gitHubRepositoryContextQueryRefreshPropagatesCancellation() async throws {
	let executor = GitHubRepositoryFixtureExecutor([], suspends: true)
	let query = GitHubRepositoryContextQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let task = Task { try await query.refresh(workspaceURL: URL(fileURLWithPath: "/workspace")) }
	try await Task.sleep(for: .milliseconds(50))
	task.cancel()
	await #expect(throws: CancellationError.self) {
		_ = try await task.value
	}
}

private func pullRequestJSON(number: Int) -> String {
	#"{"number":\#(number),"url":"https://github.com/owner/repo/pull/\#(number)","title":"PR \#(number)","state":"OPEN","isDraft":false,"headRefName":"feature","baseRefName":"main","reviewDecision":null}"#
}

private func pullRequest(number: Int) -> GitHubPullRequestSummary {
	GitHubPullRequestSummary(number: number, url: URL(string: "https://github.com/owner/repo/pull/\(number)")!, title: "PR \(number)", state: "OPEN", isDraft: false, headRefName: "feature", baseRefName: "main", reviewDecision: nil)
}

private extension GitHubCLIJSONBridgeError {
	var exitStatus: Int32? {
		switch self {
		case let .processFailure(diagnostics), let .invalidJSON(diagnostics): diagnostics.exitStatus
		default: nil
		}
	}
}

private actor GitHubRepositoryFixtureExecutor: GitHubCLIJSONExecuting {
	private var results: [GitHubCLIProcessResult]
	private let suspends: Bool
	private var recorded: [[String]] = []

	init(_ results: [GitHubCLIProcessResult], suspends: Bool = false) {
		self.results = results
		self.suspends = suspends
	}

	var arguments: [[String]] { recorded }

	func run(executableURL _: URL, arguments: [String], workingDirectoryURL _: URL?) async throws -> GitHubCLIProcessResult {
		recorded.append(arguments)
		if suspends { try await Task.sleep(for: .seconds(10)) }
		return results.isEmpty ? GitHubCLIProcessResult(exitStatus: 1) : results.removeFirst()
	}
}
