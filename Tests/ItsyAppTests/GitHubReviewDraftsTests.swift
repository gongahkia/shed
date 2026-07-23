import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubReviewDraftsQuoteLocalSourceMapLinesAndRestore() throws {
	let fixture = try GitHubReviewDraftFixture()
	try fixture.write("Sources/File.swift", "one\ntwo\nthree\nfour\nfive\n")
	let location = try GitHubReviewLineLocation(path: "Sources/File.swift", range: 2 ... 4, side: .right, commitOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))
	let draft = try GitHubReviewDraft(pullRequestNumber: 7, target: .inline(location), body: "Please extract this.", createdAt: Date(timeIntervalSince1970: 0))
	let preview = GitHubReviewMarkdown.preview(draft: draft, workspaceURL: fixture.root)
	#expect(preview == "`Sources/File.swift:2-4`\n\n> two\n> three\n> four\n\nPlease extract this.")
	let store = GitHubReviewDraftStore(workspaceURL: fixture.root)
	try store.save([draft])
	#expect(store.load(pullRequestNumber: 7) == [draft])
	#expect(store.load(pullRequestNumber: 8).isEmpty)
	#expect(throws: GitHubCLIJSONBridgeError.invalidCommand) { try GitHubReviewLineLocation(path: "../escape.swift", range: 1 ... 1, side: .right, commitOID: location.commitOID) }
}

@Test func gitHubReviewDraftDeliveryUsesExactTypedArgumentsAndRetainsFailuresForRetry() async throws {
	let repository = try GitHubRepositoryName("owner/repo")
	let location = try GitHubReviewLineLocation(path: "Sources/File.swift", range: 6 ... 8, side: .right, commitOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))
	let inline = try GitHubReviewDraft(pullRequestNumber: 7, target: .inline(location), body: "line one\nline two")
	let executor = GitHubReviewDraftFixtureExecutor([
		.init(exitStatus: 1, standardError: "temporary failure"),
		.init(exitStatus: 0, standardOutput: #"{"id":55}"#),
		.init(exitStatus: 0, standardOutput: #"{"id":56}"#),
	])
	let delivery = GitHubReviewDraftDelivery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let failed = try await delivery.submit(draft: inline, repository: repository, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case .failed(.processFailure) = failed else {
		Issue.record("expected failed inline delivery")
		return
	}
	#expect(try await delivery.retry(draft: inline, repository: repository, workspaceURL: URL(fileURLWithPath: "/workspace")) == .submitted(commentID: 55))
	let reply = try GitHubReviewDraft(pullRequestNumber: 7, target: .reply(try GitHubReviewReplyTarget(commentID: 99)), body: "Agreed")
	#expect(try await delivery.submit(draft: reply, repository: repository, workspaceURL: URL(fileURLWithPath: "/workspace")) == .submitted(commentID: 56))
	let calls = await executor.arguments
	#expect(calls == [
		["api", "--method", "POST", "repos/owner/repo/pulls/7/comments", "-f", "body=line one\nline two", "-f", "path=Sources/File.swift", "-f", "commit_id=0123456789abcdef0123456789abcdef01234567", "-F", "line=8", "-f", "side=RIGHT", "-F", "start_line=6", "-f", "start_side=RIGHT"],
		["api", "--method", "POST", "repos/owner/repo/pulls/7/comments", "-f", "body=line one\nline two", "-f", "path=Sources/File.swift", "-f", "commit_id=0123456789abcdef0123456789abcdef01234567", "-F", "line=8", "-f", "side=RIGHT", "-F", "start_line=6", "-f", "start_side=RIGHT"],
		["api", "--method", "POST", "repos/owner/repo/pulls/7/comments/99/replies", "-f", "body=Agreed"],
	])
}

@Test func gitHubReviewDraftsKeepReviewBodiesForExplicitReviewSubmission() async throws {
	let draft = try GitHubReviewDraft(pullRequestNumber: 7, target: .review, body: "General review")
	let delivery = GitHubReviewDraftDelivery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: GitHubReviewDraftFixtureExecutor([])))
	let result = try await delivery.submit(draft: draft, repository: GitHubRepositoryName("owner/repo"), workspaceURL: URL(fileURLWithPath: "/workspace"))
	#expect(result == .queuedForReview)
}

@Test func gitHubReviewDraftDeliveryPropagatesCancellation() async throws {
	let location = try GitHubReviewLineLocation(path: "Sources/File.swift", range: 1 ... 1, side: .right, commitOID: GitHubGitObjectID("0123456789abcdef0123456789abcdef01234567"))
	let draft = try GitHubReviewDraft(pullRequestNumber: 7, target: .inline(location), body: "Cancelled")
	let delivery = GitHubReviewDraftDelivery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: GitHubReviewDraftFixtureExecutor([], throwsCancellation: true)))
	await #expect(throws: CancellationError.self) {
		_ = try await delivery.submit(draft: draft, repository: GitHubRepositoryName("owner/repo"), workspaceURL: URL(fileURLWithPath: "/workspace"))
	}
}

private actor GitHubReviewDraftFixtureExecutor: GitHubCLIJSONExecuting {
	private var results: [GitHubCLIProcessResult]
	private var recorded: [[String]] = []
	private let throwsCancellation: Bool

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

private final class GitHubReviewDraftFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-review-draft-\(UUID().uuidString)", isDirectory: true)

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
