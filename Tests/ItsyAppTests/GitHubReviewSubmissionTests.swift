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

@Test func gitHubReviewDraftSubmissionRemovesOnlyConfirmedThreadDrafts() async throws {
	let fixture = try GitHubReviewSubmissionDraftFixture()
	let store = GitHubReviewDraftStore(workspaceURL: fixture.root)
	let location = try GitHubReviewLineLocation(path: "Sources/File.swift", range: 4 ... 4, side: .right, commitOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))
	let draft = try GitHubReviewDraft(pullRequestNumber: 7, target: .inline(location), body: "Please revise")
	try store.save([draft])
	let executor = GitHubReviewSubmissionFixtureExecutor([
		.init(exitStatus: 1, standardError: "temporary failure"),
		.init(exitStatus: 0, standardOutput: #"{"id":55}"#),
	])
	let service = GitHubReviewDraftSubmissionService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let repository = try GitHubRepositoryName("owner/repo")
	guard case .failed(.processFailure) = try await service.submit(draft: draft, repository: repository, workspaceURL: fixture.root, store: store) else {
		Issue.record("expected retained remote-delivery failure")
		return
	}
	#expect(store.load().map(\.id) == [draft.id])
	#expect(try await service.submit(draft: draft, repository: repository, workspaceURL: fixture.root, store: store) == .submitted(.comment(commentID: 55)))
	#expect(store.load().isEmpty)
	#expect(try await service.submit(draft: draft, reviewAction: .approve, repository: repository, workspaceURL: fixture.root, store: store) == .failed(.invalidCommand))
}

@Test func gitHubReviewDraftSubmissionSubmitsReviewsAndReportsLocalRecovery() async throws {
	let fixture = try GitHubReviewSubmissionDraftFixture()
	let store = GitHubReviewDraftStore(workspaceURL: fixture.root)
	let draft = try GitHubReviewDraft(pullRequestNumber: 7, target: .review, body: "Approved")
	try store.save([draft])
	let executor = GitHubReviewSubmissionFixtureExecutor([
		.init(exitStatus: 0),
		.init(exitStatus: 0, standardOutput: detailJSON()),
		.init(exitStatus: 0, standardOutput: #"[]"#),
	])
	let service = GitHubReviewDraftSubmissionService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let repository = try GitHubRepositoryName("owner/repo")
	#expect(try await service.submit(draft: draft, repository: repository, workspaceURL: fixture.root, store: store) == .requiresReviewAction)
	guard case let .submitted(.review(action, refreshed)) = try await service.submit(draft: draft, reviewAction: .approve, repository: repository, workspaceURL: fixture.root, store: store) else {
		Issue.record("expected persisted review submission")
		return
	}
	#expect(action == .approve)
	guard case let .ready(detail) = refreshed else {
		Issue.record("expected refreshed submitted review")
		return
	}
	#expect(detail.reviewDecision == "APPROVED")
	#expect(store.load().isEmpty)

	let invalidStore = GitHubReviewDraftStore(workspaceURL: fixture.fileURL)
	let retainedExecutor = GitHubReviewSubmissionFixtureExecutor([.init(exitStatus: 0, standardOutput: #"{"id":56}"#)])
	let retainedService = GitHubReviewDraftSubmissionService(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: retainedExecutor))
	let inline = try GitHubReviewDraft(pullRequestNumber: 7, target: .inline(try GitHubReviewLineLocation(path: "Sources/File.swift", range: 5 ... 5, side: .right, commitOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))), body: "Retained locally")
	#expect(try await retainedService.submit(draft: inline, repository: repository, workspaceURL: fixture.root, store: invalidStore) == .submittedButRetained(.comment(commentID: 56)))
	#expect(!retainedService.persistRemoval(of: inline, in: invalidStore))
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

private final class GitHubReviewSubmissionDraftFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-review-submission-\(UUID().uuidString)", isDirectory: true)
	let fileURL: URL

	init() throws {
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
		fileURL = root.appendingPathComponent("not-a-workspace")
		try Data().write(to: fileURL)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}
}
