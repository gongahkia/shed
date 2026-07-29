import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubPullRequestDetailQueryDecodesDetailsAndChecks() async throws {
	let executor = GitHubPullRequestDetailFixtureExecutor([
		.init(exitStatus: 0, standardOutput: detailJSON(state: "OPEN", draft: true, owner: "fork-owner", fileCount: 2)),
		.init(exitStatus: 0, standardOutput: #"[{"name":"build","state":"SUCCESS","bucket":"pass","workflow":"CI","link":"https://github.com/owner/repo/actions/runs/1"}]"#),
	])
	let query = GitHubPullRequestDetailQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let state = try await query.refresh(number: 7, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .ready(detail) = state else {
		Issue.record("expected detail")
		return
	}
	#expect(detail.isDraft)
	#expect(detail.headRepositoryOwner?.login == "fork-owner")
	#expect(detail.commits.count == 1)
	#expect(detail.files.count == 2)
	guard case let .available(checks) = detail.checks else {
		Issue.record("expected checks")
		return
	}
	#expect(checks.map(\.name) == ["build"])
	let commands = await executor.arguments
	#expect(commands.count == 2)
	#expect(commands[0][0...1] == ["pr", "view"])
	#expect(commands[1][0...1] == ["pr", "checks"])
}

@Test func gitHubPullRequestDetailFixturesCoverStatesPaginationAndUnavailableChecks() async throws {
	for fixture in [("OPEN", true, "fork-owner"), ("MERGED", false, "owner"), ("CLOSED", false, "owner")] {
		let detail = try JSONDecoder().decode(GitHubPullRequestDetail.self, from: Data(detailJSON(state: fixture.0, draft: fixture.1, owner: fixture.2, fileCount: 101).utf8))
		#expect(detail.state == fixture.0)
		#expect(detail.isDraft == fixture.1)
		#expect(detail.headRepositoryOwner?.login == fixture.2)
		let page = try #require(GitHubPullRequestFilePage.make(files: detail.files, page: 2, pageSize: 50))
		#expect(page.pageCount == 3)
		#expect(page.files.count == 1)
	}

	let unavailableExecutor = GitHubPullRequestDetailFixtureExecutor([
		.init(exitStatus: 0, standardOutput: detailJSON(state: "OPEN", draft: false, owner: "owner", fileCount: 1)),
		.init(exitStatus: 1, standardError: "checks unavailable"),
	])
	let query = GitHubPullRequestDetailQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: unavailableExecutor))
	let state = try await query.refresh(number: 7, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .ready(detail) = state, case .unavailable = detail.checks else {
		Issue.record("expected unavailable checks")
		return
	}
	let context = GitHubPullRequestDiffContext.make(detail: detail, file: GitHubPullRequestChangedFile(path: "../outside.swift", additions: 1, deletions: 0), workspaceURL: URL(fileURLWithPath: "/workspace"))
	#expect(context.localFileURL == nil)
}

@MainActor @Test func gitHubPullRequestDetailPanelRendersReviewAndLocalOnlyContext() throws {
	let detail = try JSONDecoder().decode(GitHubPullRequestDetail.self, from: Data(detailJSON(state: "OPEN", draft: true, owner: "fork-owner", fileCount: 1).utf8))
	let text = GitHubPullRequestDetailPanel.overviewText(detail)
	#expect(text.contains("draft"))
	#expect(text.contains("fork-owner:feature"))
	#expect(text.contains("Checks: unavailable"))
}

private func detailJSON(state: String, draft: Bool, owner: String, fileCount: Int) -> String {
	let files = (0 ..< fileCount).map { #"{"path":"Sources/File\#($0).swift","additions":\#($0 + 1),"deletions":0}"# }.joined(separator: ",")
	return #"{"number":7,"url":"https://github.com/owner/repo/pull/7","title":"Detail fixture","body":"Body","state":"\#(state)","isDraft":\#(draft),"headRefName":"feature","headRepositoryOwner":{"login":"\#(owner)"},"baseRefName":"main","reviewDecision":"APPROVED","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","commits":[{"oid":"abcdef012345","messageHeadline":"Commit title","authoredDate":"2026-01-01T00:00:00Z","committedDate":"2026-01-01T00:00:00Z"}],"files":[\#(files)]}"#
}

private actor GitHubPullRequestDetailFixtureExecutor: GitHubCLIJSONExecuting {
	private var results: [GitHubCLIProcessResult]
	private var recorded: [[String]] = []

	init(_ results: [GitHubCLIProcessResult]) {
		self.results = results
	}

	var arguments: [[String]] { recorded }

	func run(executableURL _: URL, arguments: [String], workingDirectoryURL _: URL?) async throws -> GitHubCLIProcessResult {
		recorded.append(arguments)
		return results.isEmpty ? GitHubCLIProcessResult(exitStatus: 1) : results.removeFirst()
	}
}
