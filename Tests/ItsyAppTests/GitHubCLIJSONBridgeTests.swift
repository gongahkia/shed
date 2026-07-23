import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubCLIJSONCommandsRejectUnknownAndShellInjectedArguments() throws {
	#expect(!GitHubCLIJSONCommand.isAllowlisted(arguments: ["api", "--method", "DELETE", "/repos/owner/repo"]))
	#expect(!GitHubCLIJSONCommand.isAllowlisted(arguments: ["pr", "view", "1;open -a Calculator", "--json", "number"]))
	#expect(throws: GitHubCLIJSONBridgeError.invalidCommand) {
		try GitHubCLIReferenceName("feature;open -a Calculator")
	}
	#expect(try GitHubCLIJSONCommand.pullRequest(number: 7, fields: [.number, .title]).arguments() == ["pr", "view", "7", "--json", "number,title"])
}

@Test func gitHubCLIJSONBridgeDecodesAllowlistedResult() async throws {
	let executor = GitHubCLIJSONFixtureExecutor([.init(exitStatus: 0, standardOutput: #"{"number":7,"title":"Typed bridge"}"#)])
	let bridge = GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: executor)
	let value = try await bridge.execute(.pullRequest(number: 7, fields: [.number, .title]), as: PullRequestFixture.self, workspaceURL: URL(fileURLWithPath: "/workspace"))
	#expect(value == PullRequestFixture(number: 7, title: "Typed bridge"))
	#expect(await executor.arguments == [["pr", "view", "7", "--json", "number,title"]])
}

@Test func gitHubCLIJSONBridgeRedactsFailureOutputAndPropagatesCancellation() async throws {
	let executor = GitHubCLIJSONFixtureExecutor([.init(exitStatus: 1, standardOutput: "BRIDGE_SECRET=fixture-secret ghs_visibleToken", standardError: "Bearer fixture-secret https://fixture-secret@github.com")])
	let bridge = GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), environment: ["BRIDGE_SECRET": "fixture-secret"], executor: executor)
	let diagnostics: GitHubCLIJSONDiagnostics
	do {
		_ = try await bridge.execute(.repository(fields: [.nameWithOwner]), as: RepositoryFixture.self, workspaceURL: nil)
		Issue.record("expected a process failure")
		return
	} catch let GitHubCLIJSONBridgeError.processFailure(value) {
		diagnostics = value
	} catch {
		Issue.record("unexpected error: \(error)")
		return
	}
	#expect(!diagnostics.standardOutput.contains("fixture-secret"))
	#expect(!diagnostics.standardOutput.contains("ghs_visibleToken"))
	#expect(!diagnostics.standardError.contains("fixture-secret"))

	let cancelled = GitHubCLIJSONFixtureExecutor([], throwsCancellation: true)
	let cancelledBridge = GitHubCLIJSONBridge(executableURL: URL(fileURLWithPath: "/fixture/gh"), executor: cancelled)
	await #expect(throws: CancellationError.self) {
		try await cancelledBridge.execute(.repository(fields: [.nameWithOwner]), as: RepositoryFixture.self, workspaceURL: nil)
	}
}

@Test func gitHubCLIJSONProcessExecutorCancelsItsChildProcess() async throws {
	let executor = GitHubCLIJSONProcessExecutor()
	let task = Task {
		try await executor.run(executableURL: URL(fileURLWithPath: "/bin/sleep"), arguments: ["10"], workingDirectoryURL: nil)
	}
	try await Task.sleep(for: .milliseconds(50))
	task.cancel()
	await #expect(throws: CancellationError.self) {
		_ = try await task.value
	}
}

private struct PullRequestFixture: Codable, Equatable, Sendable {
	let number: Int
	let title: String
}

private struct RepositoryFixture: Codable, Equatable, Sendable {
	let nameWithOwner: String
}

private actor GitHubCLIJSONFixtureExecutor: GitHubCLIJSONExecuting {
	private var results: [GitHubCLIProcessResult]
	private let throwsCancellation: Bool
	private var recorded: [[String]] = []

	init(_ results: [GitHubCLIProcessResult], throwsCancellation: Bool = false) {
		self.results = results
		self.throwsCancellation = throwsCancellation
	}

	var arguments: [[String]] {
		recorded
	}

	func run(executableURL _: URL, arguments: [String], workingDirectoryURL _: URL?) async throws -> GitHubCLIProcessResult {
		recorded.append(arguments)
		let result = results.isEmpty ? nil : results.removeFirst()
		if throwsCancellation { throw CancellationError() }
		return result ?? GitHubCLIProcessResult(exitStatus: 1)
	}
}
