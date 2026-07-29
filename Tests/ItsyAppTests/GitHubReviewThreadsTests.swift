import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubReviewThreadsQueryPaginatesTypedThreadData() async throws {
	let executor = GitHubReviewThreadsFixtureExecutor([
		.init(exitStatus: 0, standardOutput: responseJSON(threads: [threadJSON(id: "resolved", line: 3, originalLine: 3, startLine: nil, originalStartLine: nil, resolved: true, outdated: false), threadJSON(id: "outdated", line: 4, originalLine: 4, startLine: nil, originalStartLine: nil, resolved: false, outdated: true)], hasNextPage: true, endCursor: "cursor-2")),
		.init(exitStatus: 0, standardOutput: responseJSON(threads: [threadJSON(id: "multi", line: 8, originalLine: 8, startLine: 6, originalStartLine: 6, resolved: false, outdated: false), threadJSON(id: "deleted", line: nil, originalLine: 5, startLine: nil, originalStartLine: 5, resolved: false, outdated: false)], hasNextPage: false, endCursor: nil)),
	])
	let query = GitHubReviewThreadsQuery(bridge: GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor))
	let repository = try GitHubRepositoryName("owner/repo")
	let state = try await query.refresh(repository: repository, pullRequestNumber: 7, workspaceURL: URL(fileURLWithPath: "/workspace"))
	guard case let .ready(threads) = state else {
		Issue.record("expected threads")
		return
	}
	#expect(threads.map(\.id) == ["resolved", "outdated", "multi", "deleted"])
	#expect(threads[0].isResolved)
	#expect(threads[1].isOutdated)
	#expect(threads[2].range == 6 ... 8)
	#expect(threads[3].range == 5 ... 5)
	let calls = await executor.arguments
	#expect(calls.count == 2)
	#expect(calls[0][0...1] == ["api", "graphql"])
	#expect(!calls[0].contains(where: { $0.hasPrefix("endCursor=") }))
	#expect(calls[1].contains("endCursor=cursor-2"))
	#expect(throws: GitHubCLIJSONBridgeError.invalidCommand) { try GitHubRepositoryName("owner;rm/repo") }
}

@MainActor @Test func gitHubReviewThreadContextCoversLocalRangesAndNeverAppliesRemoteChanges() throws {
	let fixture = try LocalThreadFixture()
	let original = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n"
	try fixture.write("Sources/File.swift", original)
	let multiline = try decodeThread(threadJSON(id: "multi", line: 6, originalLine: 6, startLine: 4, originalStartLine: 4, resolved: false, outdated: false, path: "Sources/File.swift"))
	let context = GitHubReviewThreadContext.make(thread: multiline, workspaceURL: fixture.root, surroundingLineCount: 1)
	#expect(context.localFileURL == fixture.root.appendingPathComponent("Sources/File.swift").standardizedFileURL)
	#expect(context.sourceLines.map(\.number) == [3, 4, 5, 6, 7])
	#expect(try String(contentsOf: fixture.root.appendingPathComponent("Sources/File.swift"), encoding: .utf8) == original)
	#expect(GitHubReviewThreadPanel.threadSummary(multiline) == "[open] Sources/File.swift:4-6")

	let deleted = try decodeThread(threadJSON(id: "deleted", line: nil, originalLine: 5, startLine: nil, originalStartLine: 5, resolved: false, outdated: false))
	#expect(GitHubReviewThreadPanel.threadSummary(deleted).contains("deleted line 5"))
	let outside = try decodeThread(threadJSON(id: "outside", line: 1, originalLine: 1, startLine: nil, originalStartLine: nil, resolved: false, outdated: false, path: "../outside.swift"))
	let outsideContext = GitHubReviewThreadContext.make(thread: outside, workspaceURL: fixture.root)
	#expect(outsideContext.localFileURL == nil)
	#expect(GitHubReviewThreadPanel.contextText(outsideContext).contains("did not fetch or apply remote file content"))
}

private func responseJSON(threads: [String], hasNextPage: Bool, endCursor: String?) -> String {
	let cursor = endCursor.map { #""\#($0)""# } ?? "null"
	return #"{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[\#(threads.joined(separator: ","))],"pageInfo":{"hasNextPage":\#(hasNextPage),"endCursor":\#(cursor)}}}}}}"#
}

private func threadJSON(id: String, line: Int?, originalLine: Int?, startLine: Int?, originalStartLine: Int?, resolved: Bool, outdated: Bool, path: String = "Sources/File.swift") -> String {
	func number(_ value: Int?) -> String { value.map(String.init) ?? "null" }
	return #"{"id":"\#(id)","path":"\#(path)","line":\#(number(line)),"originalLine":\#(number(originalLine)),"startLine":\#(number(startLine)),"originalStartLine":\#(number(originalStartLine)),"diffSide":"RIGHT","startDiffSide":"RIGHT","isResolved":\#(resolved),"isOutdated":\#(outdated),"comments":{"nodes":[{"id":"comment-\#(id)","body":"comment \#(id)","createdAt":"2026-01-01T00:00:00Z","url":"https://github.com/owner/repo/pull/7#discussion_\#(id)","author":{"login":"reviewer"}}]}}"#
}

private func decodeThread(_ json: String) throws -> GitHubReviewThread {
	try JSONDecoder().decode(GitHubReviewThread.self, from: Data(json.utf8))
}

private actor GitHubReviewThreadsFixtureExecutor: GitHubCLIJSONExecuting {
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

private final class LocalThreadFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-review-thread-\(UUID().uuidString)", isDirectory: true)

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
