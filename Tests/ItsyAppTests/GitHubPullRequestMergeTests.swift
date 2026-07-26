import Foundation
import ItsyEditor
@testable import ItsyApp
import Testing

@Test func gitHubPullRequestMergeMetadataCoversCompatibleMethodsAndMergeQueue() async throws {
	let executor = GitHubPullRequestMergeFixtureExecutor([.init(exitStatus: 0, standardOutput: metadataJSON(merge: true, squash: true, rebase: false, automatic: false, inQueue: true))])
	let query = GitHubPullRequestMergeMetadataQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let state = try await query.refresh(repository: GitHubRepositoryName("owner/repo"), pullRequestNumber: 7, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .ready(metadata) = state else {
		Issue.record("expected merge metadata")
		return
	}
	#expect(metadata.compatibleMethods == [.merge, .squash, .automatic])
	#expect(GitHubPullRequestMergePreflight.make(detail: detailJSON(state: "OPEN", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN"), metadata: metadata, method: .automatic) == .mergeQueue)
	let calls = await executor.arguments
	#expect(calls[0][0...1] == ["api", "graphql"])
}

@Test func gitHubPullRequestMergePreflightCoversConflictProtectionAndAlreadyMerged() {
	let metadata = GitHubPullRequestMergeMetadata(mergeCommitAllowed: true, squashMergeAllowed: true, rebaseMergeAllowed: true, autoMergeAllowed: true, isInMergeQueue: false)
	#expect(GitHubPullRequestMergePreflight.make(detail: detailJSON(state: "OPEN", mergeable: "CONFLICTING", mergeStateStatus: "DIRTY"), metadata: metadata, method: .merge) == .conflicting)
	#expect(GitHubPullRequestMergePreflight.make(detail: detailJSON(state: "OPEN", mergeable: "MERGEABLE", mergeStateStatus: "BLOCKED"), metadata: metadata, method: .squash) == .protected)
	#expect(GitHubPullRequestMergePreflight.make(detail: detailJSON(state: "MERGED", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN"), metadata: metadata, method: .rebase) == .alreadyMerged)
}

@Test func gitHubPullRequestMergeConfirmsRunsExactCommandRefreshesAndCleansOnlyMergedBranch() async throws {
	let executor = GitHubPullRequestMergeFixtureExecutor([
		.init(exitStatus: 0),
		.init(exitStatus: 0, standardOutput: detailJSONData(state: "MERGED", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN")),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let cleaner = GitHubPullRequestMergeCleaner()
	let service = GitHubPullRequestMergeService(
		bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor),
		gitRefresher: GitHubPullRequestMergeRefresher(status: GitStatus(branch: GitBranchStatus(head: "main", upstream: "origin/main"))),
		branchCleaner: cleaner
	)
	let intent = try GitHubPullRequestMergeIntent(repository: GitHubRepositoryName("owner/repo"), pullRequestNumber: 7, method: .squash, headOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))
	let confirmation = GitHubPullRequestMergePlanner.requestConfirmation(for: intent)
	let metadata = GitHubPullRequestMergeMetadata(mergeCommitAllowed: true, squashMergeAllowed: true, rebaseMergeAllowed: false, autoMergeAllowed: false, isInMergeQueue: false)
	let result = try await service.merge(confirmation, detail: detailJSON(state: "OPEN", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN"), metadata: metadata, cleanup: .deleteLocalBranch(try GitHubCLIReferenceName("feature")), workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .completed(.ready(detail), gitStatus, cleanup) = result else {
		Issue.record("expected completed merge")
		return
	}
	#expect(detail.state == "MERGED")
	#expect(gitStatus?.branch.head == "main")
	#expect(cleanup == .deleted)
	#expect(await cleaner.deleted == ["feature"])
	let calls = await executor.arguments
	#expect(calls[0] == ["pr", "merge", "7", "--repo", "owner/repo", "--match-head-commit", "0123456789abcdef0123456789abcdef01234567", "--squash"])
}

@Test func gitHubPullRequestMergeSkipsOrReportsOptionalCleanupFailure() async throws {
	let metadata = GitHubPullRequestMergeMetadata(mergeCommitAllowed: true, squashMergeAllowed: false, rebaseMergeAllowed: false, autoMergeAllowed: false, isInMergeQueue: false)
	let intent = try GitHubPullRequestMergeIntent(repository: GitHubRepositoryName("owner/repo"), pullRequestNumber: 7, method: .merge)
	let confirmation = GitHubPullRequestMergePlanner.requestConfirmation(for: intent)
	let failureExecutor = GitHubPullRequestMergeFixtureExecutor([
		.init(exitStatus: 0),
		.init(exitStatus: 0, standardOutput: detailJSONData(state: "MERGED", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN")),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let failedCleanup = GitHubPullRequestMergeCleaner(shouldFail: true)
	let failureService = GitHubPullRequestMergeService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: failureExecutor), gitRefresher: GitHubPullRequestMergeRefresher(status: GitStatus()), branchCleaner: failedCleanup)
	guard case let .completed(_, _, cleanup) = try await failureService.merge(confirmation, detail: detailJSON(state: "OPEN", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN"), metadata: metadata, cleanup: .deleteLocalBranch(try GitHubCLIReferenceName("feature")), workspaceURL: URL(fileURLWithPath: "/workspace")) else {
		Issue.record("expected merge with cleanup failure")
		return
	}
	#expect(cleanup == .failed)

	let noMergeExecutor = GitHubPullRequestMergeFixtureExecutor([])
	let noMergeCleaner = GitHubPullRequestMergeCleaner()
	let noMergeService = GitHubPullRequestMergeService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: noMergeExecutor), gitRefresher: GitHubPullRequestMergeRefresher(status: GitStatus()), branchCleaner: noMergeCleaner)
	#expect(try await noMergeService.merge(confirmation, detail: detailJSON(state: "OPEN", mergeable: "CONFLICTING", mergeStateStatus: "DIRTY"), metadata: metadata, cleanup: .deleteLocalBranch(try GitHubCLIReferenceName("feature")), workspaceURL: URL(fileURLWithPath: "/workspace")) == .notMergeable(.conflicting))
	#expect(await noMergeCleaner.deleted.isEmpty)
}

@Test func gitHubPullRequestMergeRecoversRemoteStateAfterAnAmbiguousFailure() async throws {
	let executor = GitHubPullRequestMergeFixtureExecutor([
		.init(exitStatus: 1, standardError: "merge status unavailable"),
		.init(exitStatus: 0, standardOutput: detailJSONData(state: "MERGED", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN")),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let cleaner = GitHubPullRequestMergeCleaner()
	let service = GitHubPullRequestMergeService(
		bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor),
		gitRefresher: GitHubPullRequestMergeRefresher(status: GitStatus(branch: GitBranchStatus(head: "main", upstream: "origin/main"))),
		branchCleaner: cleaner
	)
	let confirmation = GitHubPullRequestMergePlanner.requestConfirmation(for: try GitHubPullRequestMergeIntent(repository: GitHubRepositoryName("owner/repo"), pullRequestNumber: 7, method: .merge))
	let metadata = GitHubPullRequestMergeMetadata(mergeCommitAllowed: true, squashMergeAllowed: false, rebaseMergeAllowed: false, autoMergeAllowed: false, isInMergeQueue: false)
	guard case let .recovered(error, .ready(detail), gitStatus, cleanup) = try await service.merge(confirmation, detail: detailJSON(state: "OPEN", mergeable: "MERGEABLE", mergeStateStatus: "CLEAN"), metadata: metadata, cleanup: .deleteLocalBranch(try GitHubCLIReferenceName("feature")), workspaceURL: URL(fileURLWithPath: "/workspace")) else {
		Issue.record("expected recovered merge state")
		return
	}
	guard case .processFailure = error else {
		Issue.record("expected merge process failure")
		return
	}
	#expect(detail.state == "MERGED")
	#expect(gitStatus?.branch.head == "main")
	#expect(cleanup == .deleted)
	#expect(await cleaner.deleted == ["feature"])
	let calls = await executor.arguments
	#expect(calls.map { Array($0.prefix(2)) } == [["pr", "merge"], ["pr", "view"], ["pr", "checks"]])
}

private func detailJSON(state: String, mergeable: String, mergeStateStatus: String) -> GitHubPullRequestDetail {
	try! JSONDecoder().decode(GitHubPullRequestDetail.self, from: Data(detailJSONData(state: state, mergeable: mergeable, mergeStateStatus: mergeStateStatus).utf8))
}

private func detailJSONData(state: String, mergeable: String, mergeStateStatus: String) -> String {
	"""
	{"number":7,"url":"https://github.com/owner/repo/pull/7","title":"Merge fixture","body":"Body","state":"\(state)","isDraft":false,"headRefName":"feature","headRepositoryOwner":{"login":"owner"},"baseRefName":"main","reviewDecision":null,"mergeable":"\(mergeable)","mergeStateStatus":"\(mergeStateStatus)","commits":[],"files":[]}
	"""
}

private func metadataJSON(merge: Bool, squash: Bool, rebase: Bool, automatic: Bool, inQueue: Bool) -> String {
	"""
	{"data":{"repository":{"mergeCommitAllowed":\(merge),"squashMergeAllowed":\(squash),"rebaseMergeAllowed":\(rebase),"autoMergeAllowed":\(automatic),"pullRequest":{"isInMergeQueue":\(inQueue)}}}}
	"""
}

private actor GitHubPullRequestMergeFixtureExecutor: GitHubCLIJSONExecuting {
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

private actor GitHubPullRequestMergeCleaner: GitHubLocalBranchCleaning {
	private let shouldFail: Bool
	private(set) var deleted: [String] = []

	init(shouldFail: Bool = false) {
		self.shouldFail = shouldFail
	}

	func deleteMergedBranch(_ branch: GitHubCLIReferenceName, workspaceURL _: URL) async throws {
		if shouldFail { throw GitHubCLIJSONBridgeError.unavailable }
		deleted.append(branch.value)
	}
}

private struct GitHubPullRequestMergeRefresher: GitHubLocalGitRefreshing {
	let status: GitStatus

	func refresh(workspaceURL _: URL) async throws -> GitStatus {
		status
	}
}
