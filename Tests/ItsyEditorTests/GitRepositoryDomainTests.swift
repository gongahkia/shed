import Foundation
import ItsyEditor
import Testing

@Test func gitRepositoryDomainDelegatesOperationsThroughItsFactory() throws {
	let root = URL(fileURLWithPath: "/tmp/itsy-git-domain", isDirectory: true)
	let runner = GitRepositoryDomainRunner(output: "")
	let domain = GitRepositoryDomain { GitRepository(root: $0, runner: runner) }

	#expect(try domain.status(at: root) == GitStatus())
	try domain.stage(paths: ["Sources/App.swift"], at: root)
	try domain.unstage(paths: ["Sources/App.swift"], at: root)

	#expect(runner.arguments == [
		["status", "--porcelain=v2", "--branch", "--untracked-files=all", "--ignored=matching"],
		["add", "--", "Sources/App.swift"],
		["restore", "--staged", "--", "Sources/App.swift"],
	])
}

@Test func gitRepositoryDomainPreservesRepositoryFailures() throws {
	let expected = GitCommandError.failed(status: 1, stderr: "denied")
	let runner = GitRepositoryDomainRunner(failure: expected)
	let domain = GitRepositoryDomain { GitRepository(root: $0, runner: runner) }

	#expect(throws: expected) {
		_ = try domain.status(at: URL(fileURLWithPath: "/tmp/itsy-git-domain", isDirectory: true))
	}
}

private final class GitRepositoryDomainRunner: GitCommandRunning, @unchecked Sendable {
	private let lock = NSLock()
	private let output: String
	private let failure: Error?
	private var recordedArguments: [[String]] = []

	init(output: String = "", failure: Error? = nil) {
		self.output = output
		self.failure = failure
	}

	var arguments: [[String]] {
		lock.lock()
		defer { lock.unlock() }
		return recordedArguments
	}

	func runGit(arguments: [String], root _: URL) throws -> String {
		lock.lock()
		recordedArguments.append(arguments)
		lock.unlock()
		if let failure {
			throw failure
		}
		return output
	}

	func runGit(arguments: [String], input _: String, root: URL) throws -> String {
		try runGit(arguments: arguments, root: root)
	}
}
