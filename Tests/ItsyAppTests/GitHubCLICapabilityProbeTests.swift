import Foundation
@testable import ItsyApp
import Testing

@Test func gitHubCLIVersionParsesStableAndPrefixedOutput() {
	#expect(GitHubCLIVersion.parse("gh version 2.81.0 (2026-01-01)") == GitHubCLIVersion(major: 2, minor: 81, patch: 0))
	#expect(GitHubCLIVersion.parse("v2.4") == GitHubCLIVersion(major: 2, minor: 4, patch: 0))
	#expect(GitHubCLIVersion.parse("unavailable") == nil)
}

@Test func gitHubCLICapabilityProbeDistinguishesMissingUnauthenticatedOldAndInaccessibleStates() {
	let executable = URL(fileURLWithPath: "/fixture/gh")
	#expect(GitHubCLICapabilityProbe.probe(workspaceURL: URL(fileURLWithPath: "/workspace"), environment: ["PATH": ""], executor: GitHubCLIFixtureExecutor([]), locateExecutable: { _ in nil }) == .missingExecutable)

	let old = GitHubCLIFixtureExecutor([.init(exitStatus: 0, standardOutput: "gh version 1.14.0")])
	#expect(GitHubCLICapabilityProbe.probe(workspaceURL: URL(fileURLWithPath: "/workspace"), executor: old, executableURL: executable) == .unsupportedVersion(found: GitHubCLIVersion(major: 1, minor: 14, patch: 0), minimum: GitHubCLICapabilityProbe.minimumVersion))

	let unauthenticated = GitHubCLIFixtureExecutor([.init(exitStatus: 0, standardOutput: "gh version 2.81.0"), .init(exitStatus: 1, standardError: "not logged in")])
	#expect(GitHubCLICapabilityProbe.probe(workspaceURL: URL(fileURLWithPath: "/workspace"), executor: unauthenticated, executableURL: executable) == .unauthenticated)

	let inaccessible = GitHubCLIFixtureExecutor([.init(exitStatus: 0, standardOutput: "gh version 2.81.0"), .init(exitStatus: 0), .init(exitStatus: 1, standardError: "repository not found")])
	#expect(GitHubCLICapabilityProbe.probe(workspaceURL: URL(fileURLWithPath: "/workspace"), executor: inaccessible, executableURL: executable) == .inaccessibleRepository)
}

@Test func gitHubCLICapabilityProbeUsesFixedCredentialSafeArguments() {
	let executor = GitHubCLIFixtureExecutor([
		.init(exitStatus: 0, standardOutput: "gh version 2.81.0"),
		.init(exitStatus: 0, standardError: "Logged in as fixture-user"),
		.init(exitStatus: 0, standardOutput: "gongahkia/itsy\n"),
	])
	let executable = URL(fileURLWithPath: "/fixture/gh")
	let result = GitHubCLICapabilityProbe.probe(workspaceURL: URL(fileURLWithPath: "/workspace"), executor: executor, executableURL: executable)
	#expect(result == .ready(GitHubCLICapability(executableURL: executable, version: GitHubCLIVersion(major: 2, minor: 81, patch: 0), repository: "gongahkia/itsy")))
	#expect(executor.arguments == [["--version"], ["auth", "status", "--hostname", "github.com"], ["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"]])
}

private final class GitHubCLIFixtureExecutor: GitHubCLIExecuting {
	private var results: [GitHubCLIProcessResult]
	private(set) var arguments: [[String]] = []

	init(_ results: [GitHubCLIProcessResult]) {
		self.results = results
	}

	func run(executableURL _: URL, arguments: [String], workingDirectoryURL _: URL?) -> GitHubCLIProcessResult? {
		self.arguments.append(arguments)
		guard !results.isEmpty else { return nil }
		return results.removeFirst()
	}
}
