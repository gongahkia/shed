import Foundation
import ItsyEditor

public struct GitHubCLIReferenceName: Equatable, Sendable {
	public let value: String

	public init(_ value: String) throws {
		guard !value.isEmpty,
			value.unicodeScalars.allSatisfy({ !$0.properties.isWhitespace && !CharacterSet.controlCharacters.contains($0) }),
			!GitHubCLIJSONCommand.containsShellMetacharacter(value)
		else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.value = value
	}
}

public enum GitHubCLIRepositoryJSONField: String, CaseIterable, Equatable, Sendable {
	case nameWithOwner
	case url
	case defaultBranchRef
	case isPrivate
	case viewerPermission
}

public enum GitHubCLIPullRequestJSONField: String, CaseIterable, Equatable, Sendable {
	case number
	case url
	case title
	case state
	case isDraft
	case headRefName
	case baseRefName
	case author
	case reviewDecision
	case body
	case comments
	case commits
	case files
	case reviewRequests
	case reviews
	case statusCheckRollup
	case mergeable
	case mergeStateStatus
	case createdAt
	case updatedAt
	case additions
	case deletions
	case changedFiles
	case maintainerCanModify
}

public enum GitHubCLIPullRequestCheckJSONField: String, CaseIterable, Equatable, Sendable {
	case bucket
	case completedAt
	case description
	case event
	case link
	case name
	case startedAt
	case state
	case workflow
}

public enum GitHubCLIJSONCommand: Equatable, Sendable {
	case repository(fields: [GitHubCLIRepositoryJSONField])
	case pullRequest(number: Int?, fields: [GitHubCLIPullRequestJSONField])
	case pullRequestList(limit: Int, headRefName: GitHubCLIReferenceName?, fields: [GitHubCLIPullRequestJSONField])
	case pullRequestChecks(number: Int, fields: [GitHubCLIPullRequestCheckJSONField])

	public func arguments() throws -> [String] {
		let arguments: [String]
		switch self {
		case let .repository(fields):
			arguments = ["repo", "view", "--json", try fieldList(fields)]
		case let .pullRequest(number, fields):
			guard number == nil || number! > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
			arguments = ["pr", "view"] + (number.map { [String($0)] } ?? []) + ["--json", try fieldList(fields)]
		case let .pullRequestList(limit, headRefName, fields):
			guard (1 ... 100).contains(limit) else { throw GitHubCLIJSONBridgeError.invalidCommand }
			arguments = ["pr", "list", "--limit", String(limit)]
				+ (headRefName.map { ["--head", $0.value] } ?? [])
				+ ["--json", try fieldList(fields)]
		case let .pullRequestChecks(number, fields):
			guard number > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
			arguments = ["pr", "checks", String(number), "--json", try fieldList(fields)]
		}
		guard Self.isAllowlisted(arguments: arguments) else { throw GitHubCLIJSONBridgeError.invalidCommand }
		return arguments
	}

	public static func isAllowlisted(arguments: [String]) -> Bool {
		guard !arguments.isEmpty, arguments.allSatisfy({ !$0.isEmpty && !containsShellMetacharacter($0) }) else { return false }
		if arguments.count == 4, Array(arguments[0...2]) == ["repo", "view", "--json"] {
			return matches(arguments[3], allowed: GitHubCLIRepositoryJSONField.allCases.map(\.rawValue))
		}
		if arguments.count == 4, Array(arguments[0...2]) == ["pr", "view", "--json"] {
			return matches(arguments[3], allowed: GitHubCLIPullRequestJSONField.allCases.map(\.rawValue))
		}
		if arguments.count == 5, arguments[0] == "pr", arguments[1] == "view", arguments[3] == "--json" {
			return Int(arguments[2]).map { $0 > 0 } == true && matches(arguments[4], allowed: GitHubCLIPullRequestJSONField.allCases.map(\.rawValue))
		}
		if arguments.count == 6, Array(arguments[0...2]) == ["pr", "list", "--limit"], arguments[4] == "--json" {
			return Int(arguments[3]).map { (1 ... 100).contains($0) } == true && matches(arguments[5], allowed: GitHubCLIPullRequestJSONField.allCases.map(\.rawValue))
		}
		if arguments.count == 8, Array(arguments[0...2]) == ["pr", "list", "--limit"], arguments[4] == "--head", arguments[6] == "--json" {
			return Int(arguments[3]).map { (1 ... 100).contains($0) } == true && !arguments[5].isEmpty && matches(arguments[7], allowed: GitHubCLIPullRequestJSONField.allCases.map(\.rawValue))
		}
		if arguments.count == 5, Array(arguments[0...1]) == ["pr", "checks"], arguments[3] == "--json" {
			return Int(arguments[2]).map { $0 > 0 } == true && matches(arguments[4], allowed: GitHubCLIPullRequestCheckJSONField.allCases.map(\.rawValue))
		}
		return false
	}

	static func containsShellMetacharacter(_ value: String) -> Bool {
		value.rangeOfCharacter(from: CharacterSet(charactersIn: ";&|`$()<>\\\\\"'\n\r")) != nil
	}

	private func fieldList<Field: RawRepresentable>(_ fields: [Field]) throws -> String where Field.RawValue == String {
		guard !fields.isEmpty, Set(fields.map(\.rawValue)).count == fields.count else { throw GitHubCLIJSONBridgeError.invalidCommand }
		return fields.map(\.rawValue).sorted().joined(separator: ",")
	}

	private static func matches(_ fields: String, allowed: [String]) -> Bool {
		let values = fields.split(separator: ",").map(String.init)
		return !values.isEmpty && Set(values).count == values.count && Set(values).isSubset(of: Set(allowed))
	}
}

public struct GitHubCLIJSONDiagnostics: Equatable, Sendable {
	public let arguments: [String]
	public let exitStatus: Int32
	public let standardOutput: String
	public let standardError: String

	public init(arguments: [String], result: GitHubCLIProcessResult, environment: [String: String] = ProcessInfo.processInfo.environment) {
		self.arguments = arguments
		exitStatus = result.exitStatus
		standardOutput = GitHubCLIJSONDiagnostics.redact(result.standardOutput, environment: environment)
		standardError = GitHubCLIJSONDiagnostics.redact(result.standardError, environment: environment)
	}

	private static func redact(_ output: String, environment: [String: String]) -> String {
		var redacted = LSPLogRedactor.redact(output, environment: environment)
		for pattern in ["\\bgh[pousr]_[A-Za-z0-9_]+\\b", "\\bgithub_pat_[A-Za-z0-9_]+\\b", "(?i)(https?://)[^/@\\s]+@"] {
			guard let expression = try? NSRegularExpression(pattern: pattern) else { continue }
			let range = NSRange(redacted.startIndex..., in: redacted)
			let template = pattern.hasPrefix("(?i)") ? "$1<redacted>@" : "<redacted>"
			redacted = expression.stringByReplacingMatches(in: redacted, range: range, withTemplate: template)
		}
		return redacted
	}
}

public enum GitHubCLIJSONBridgeError: Error, Equatable, Sendable {
	case invalidCommand
	case unavailable
	case processFailure(GitHubCLIJSONDiagnostics)
	case invalidJSON(GitHubCLIJSONDiagnostics)
}

public protocol GitHubCLIJSONExecuting: Sendable {
	func run(executableURL: URL, arguments: [String], workingDirectoryURL: URL?) async throws -> GitHubCLIProcessResult
}

public struct GitHubCLIJSONProcessExecutor: GitHubCLIJSONExecuting {
	public init() {}

	public func run(executableURL: URL, arguments: [String], workingDirectoryURL: URL?) async throws -> GitHubCLIProcessResult {
		let cancellation = GitHubCLIProcessCancellation()
		return try await withTaskCancellationHandler(operation: {
			try Task.checkCancellation()
			return try await withCheckedThrowingContinuation { continuation in
				DispatchQueue.global(qos: .userInitiated).async {
					guard !cancellation.isCancelled else {
						continuation.resume(throwing: CancellationError())
						return
					}
					let process = Process()
					process.executableURL = executableURL
					process.arguments = arguments
					process.currentDirectoryURL = workingDirectoryURL
					let stdout = Pipe()
					let stderr = Pipe()
					let stdoutBuffer = GitHubCLIOutputBuffer()
					let stderrBuffer = GitHubCLIOutputBuffer()
					process.standardOutput = stdout
					process.standardError = stderr
					stdout.fileHandleForReading.readabilityHandler = { stdoutBuffer.append($0.availableData) }
					stderr.fileHandleForReading.readabilityHandler = { stderrBuffer.append($0.availableData) }
					process.terminationHandler = { completed in
						stdout.fileHandleForReading.readabilityHandler = nil
						stderr.fileHandleForReading.readabilityHandler = nil
						stdoutBuffer.append(stdout.fileHandleForReading.readDataToEndOfFile())
						stderrBuffer.append(stderr.fileHandleForReading.readDataToEndOfFile())
						cancellation.finish()
						if cancellation.isCancelled {
							continuation.resume(throwing: CancellationError())
						} else {
							continuation.resume(returning: GitHubCLIProcessResult(exitStatus: completed.terminationStatus, standardOutput: stdoutBuffer.string, standardError: stderrBuffer.string))
						}
					}
					guard cancellation.register(process) else {
						continuation.resume(throwing: CancellationError())
						return
					}
					do {
						try process.run()
						if cancellation.isCancelled { process.terminate() }
					} catch {
						stdout.fileHandleForReading.readabilityHandler = nil
						stderr.fileHandleForReading.readabilityHandler = nil
						cancellation.finish()
						continuation.resume(throwing: GitHubCLIJSONBridgeError.unavailable)
					}
				}
			}
		}, onCancel: {
			cancellation.cancel()
		})
	}
}

public struct GitHubCLIJSONBridge: Sendable {
	private let executableURL: URL
	private let environment: [String: String]
	private let executor: any GitHubCLIJSONExecuting

	public init(executableURL: URL, environment: [String: String] = ProcessInfo.processInfo.environment, executor: any GitHubCLIJSONExecuting = GitHubCLIJSONProcessExecutor()) {
		self.executableURL = executableURL
		self.environment = environment
		self.executor = executor
	}

	public func execute<Response: Decodable & Sendable>(_ command: GitHubCLIJSONCommand, as _: Response.Type, workspaceURL: URL?) async throws -> Response {
		let arguments = try command.arguments()
		do {
			let result = try await executor.run(executableURL: executableURL, arguments: arguments, workingDirectoryURL: workspaceURL)
			let diagnostics = GitHubCLIJSONDiagnostics(arguments: arguments, result: result, environment: environment)
			guard result.exitStatus == 0 else { throw GitHubCLIJSONBridgeError.processFailure(diagnostics) }
			do {
				return try JSONDecoder().decode(Response.self, from: Data(result.standardOutput.utf8))
			} catch {
				throw GitHubCLIJSONBridgeError.invalidJSON(diagnostics)
			}
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			throw error
		} catch {
			throw GitHubCLIJSONBridgeError.unavailable
		}
	}
}

private final class GitHubCLIProcessCancellation: @unchecked Sendable {
	private let lock = NSLock()
	private var process: Process?
	private var cancelled = false

	var isCancelled: Bool {
		lock.lock()
		defer { lock.unlock() }
		return cancelled
	}

	func register(_ process: Process) -> Bool {
		lock.lock()
		defer { lock.unlock() }
		guard !cancelled else { return false }
		self.process = process
		return true
	}

	func cancel() {
		lock.lock()
		cancelled = true
		let process = process
		lock.unlock()
		process?.terminate()
	}

	func finish() {
		lock.lock()
		process = nil
		lock.unlock()
	}
}

private final class GitHubCLIOutputBuffer: @unchecked Sendable {
	private let lock = NSLock()
	private var data = Data()

	func append(_ next: Data) {
		guard !next.isEmpty else { return }
		lock.lock()
		data.append(next)
		lock.unlock()
	}

	var string: String {
		lock.lock()
		defer { lock.unlock() }
		return String(decoding: data, as: UTF8.self)
	}
}
