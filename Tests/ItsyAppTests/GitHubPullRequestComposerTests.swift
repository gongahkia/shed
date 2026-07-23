import Foundation
import ItsyEditor
@testable import ItsyApp
import Testing

@Test func gitHubPullRequestComposerRejectsUnpublishedBranchesAndLoadsTemplates() throws {
	let unpublished = try GitHubPullRequestBranchValidation.make(status: GitStatus(branch: GitBranchStatus(head: "feature", upstream: nil)))
	#expect(unpublished == .unpublished(try GitHubCLIReferenceName("feature")))
	#expect(throws: GitHubCLIJSONBridgeError.invalidCommand) {
		try GitHubPullRequestCreateInput(repository: GitHubRepositoryName("owner/repo"), base: GitHubCLIReferenceName("main"), branch: unpublished, title: "Title", body: "Body", isDraft: false)
	}
	let fixture = try GitHubPullRequestComposerFixture()
	try fixture.write(".github/PULL_REQUEST_TEMPLATE.md", "## Summary\n")
	let loader = GitHubPullRequestTemplateLoader(workspaceURL: fixture.root)
	#expect(try loader.loadDefault() == "## Summary\n")
	#expect(throws: GitHubCLIJSONBridgeError.invalidCommand) { try loader.load(relativePath: "../outside.md") }
}

@Test func gitHubPullRequestComposerCreatesForkDraftWithReviewersAndLeavesGitStateUntouchedOnFailure() async throws {
	let repository = try GitHubRepositoryName("upstream/repo")
	let branchStatus = GitStatus(branch: GitBranchStatus(oid: "before", head: "feature", upstream: "origin/feature", ahead: 1, behind: 0))
	let branch = try GitHubPullRequestBranchValidation.make(status: branchStatus)
	let input = try GitHubPullRequestCreateInput(
		repository: repository,
		base: GitHubCLIReferenceName("main"),
		branch: branch,
		headOwner: GitHubAccountName("forkowner"),
		title: "Add review flow",
		body: "Template body",
		isDraft: true,
		reviewers: [GitHubPullRequestReviewer("octocat"), GitHubPullRequestReviewer("team/reviewers")]
	)
	let executor = GitHubPullRequestComposerFixtureExecutor([
		.init(exitStatus: 0, standardOutput: "https://github.com/upstream/repo/pull/7\n"),
		.init(exitStatus: 1, standardError: "remote rejected"),
	])
	let composer = GitHubPullRequestComposer(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	#expect(try await composer.create(input, workspaceURL: URL(fileURLWithPath: "/workspace")) == .created(URL(string: "https://github.com/upstream/repo/pull/7")!))
	guard case .failed(.processFailure) = try await composer.retryCreate(input, workspaceURL: URL(fileURLWithPath: "/workspace")) else {
		Issue.record("expected create failure")
		return
	}
	#expect(branchStatus == GitStatus(branch: GitBranchStatus(oid: "before", head: "feature", upstream: "origin/feature", ahead: 1, behind: 0)))
	let calls = await executor.arguments
	#expect(calls == [
		["pr", "create", "--repo", "upstream/repo", "--base", "main", "--head", "forkowner:feature", "--title", "Add review flow", "--body", "Template body", "--draft", "--reviewer", "octocat", "--reviewer", "team/reviewers"],
		["pr", "create", "--repo", "upstream/repo", "--base", "main", "--head", "forkowner:feature", "--title", "Add review flow", "--body", "Template body", "--draft", "--reviewer", "octocat", "--reviewer", "team/reviewers"],
	])
}

@Test func gitHubPullRequestComposerUpdatesAndRefreshesMetadata() async throws {
	let input = try GitHubPullRequestUpdateInput(repository: GitHubRepositoryName("owner/repo"), pullRequestNumber: 7, title: "New title", body: "New body", base: GitHubCLIReferenceName("release"), reviewers: [GitHubPullRequestReviewer("octocat")])
	let executor = GitHubPullRequestComposerFixtureExecutor([
		.init(exitStatus: 0),
		.init(exitStatus: 0, standardOutput: detailJSON()),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let composer = GitHubPullRequestComposer(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let result = try await composer.update(input, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .updated(.ready(detail)) = result else {
		Issue.record("expected updated detail")
		return
	}
	#expect(detail.title == "Updated fixture")
	let calls = await executor.arguments
	#expect(calls[0] == ["pr", "edit", "7", "--repo", "owner/repo", "--title", "New title", "--body", "New body", "--base", "release", "--add-reviewer", "octocat"])
}

@Test func gitHubPullRequestComposerPropagatesCancellation() async throws {
	let branch = try GitHubPullRequestBranchValidation.make(status: GitStatus(branch: GitBranchStatus(head: "feature", upstream: "origin/feature")))
	let input = try GitHubPullRequestCreateInput(repository: GitHubRepositoryName("owner/repo"), base: GitHubCLIReferenceName("main"), branch: branch, title: "Title", body: "Body", isDraft: false)
	let composer = GitHubPullRequestComposer(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: GitHubPullRequestComposerFixtureExecutor([], throwsCancellation: true)))
	await #expect(throws: CancellationError.self) {
		_ = try await composer.create(input, workspaceURL: URL(fileURLWithPath: "/workspace"))
	}
}

private func detailJSON() -> String {
	#"{"number":7,"url":"https://github.com/owner/repo/pull/7","title":"Updated fixture","body":"Body","state":"OPEN","isDraft":false,"headRefName":"feature","headRepositoryOwner":{"login":"owner"},"baseRefName":"main","reviewDecision":null,"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","commits":[],"files":[]}"#
}

private actor GitHubPullRequestComposerFixtureExecutor: GitHubCLIJSONExecuting {
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

private final class GitHubPullRequestComposerFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-pr-composer-\(UUID().uuidString)", isDirectory: true)

	init() throws {
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
