import Foundation
import ItsyEditor

public struct GitHubCLIVersion: Codable, Comparable, Equatable, Sendable {
	public var major: Int
	public var minor: Int
	public var patch: Int

	public init(major: Int, minor: Int, patch: Int) {
		self.major = major
		self.minor = minor
		self.patch = patch
	}

	public static func < (lhs: GitHubCLIVersion, rhs: GitHubCLIVersion) -> Bool {
		(lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
	}

	public static func parse(_ output: String) -> GitHubCLIVersion? {
		for token in output.split(whereSeparator: { $0.isWhitespace }) {
			let values = token.trimmingCharacters(in: CharacterSet(charactersIn: "v").union(.whitespacesAndNewlines))
			let parts = values.split(separator: ".", omittingEmptySubsequences: false)
			guard parts.count >= 2,
				let major = Int(parts[0]), let minor = Int(parts[1])
			else { continue }
			let patch = parts.count >= 3 ? Int(parts[2].prefix { $0.isNumber }) ?? 0 : 0
			return GitHubCLIVersion(major: major, minor: minor, patch: patch)
		}
		return nil
	}
}

public struct GitHubCLICapability: Equatable, Sendable {
	public var executableURL: URL
	public var version: GitHubCLIVersion
	public var repository: String

	public init(executableURL: URL, version: GitHubCLIVersion, repository: String) {
		self.executableURL = executableURL
		self.version = version
		self.repository = repository
	}
}

public enum GitHubCLIProbeStatus: Equatable, Sendable {
	case ready(GitHubCLICapability)
	case missingExecutable
	case unsupportedVersion(found: GitHubCLIVersion, minimum: GitHubCLIVersion)
	case unauthenticated
	case inaccessibleRepository
	case unavailable
}

public struct GitHubCLIProcessResult: Equatable, Sendable {
	public var exitStatus: Int32
	public var standardOutput: String
	public var standardError: String

	public init(exitStatus: Int32, standardOutput: String = "", standardError: String = "") {
		self.exitStatus = exitStatus
		self.standardOutput = standardOutput
		self.standardError = standardError
	}
}

public protocol GitHubCLIExecuting {
	func run(executableURL: URL, arguments: [String], workingDirectoryURL: URL?) -> GitHubCLIProcessResult?
}

public struct GitHubCLIProcessExecutor: GitHubCLIExecuting {
	public init() {}

	public func run(executableURL: URL, arguments: [String], workingDirectoryURL: URL?) -> GitHubCLIProcessResult? {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		process.currentDirectoryURL = workingDirectoryURL
		let standardOutput = Pipe()
		let standardError = Pipe()
		process.standardOutput = standardOutput
		process.standardError = standardError
		guard (try? process.run()) != nil else { return nil }
		process.waitUntilExit()
		return GitHubCLIProcessResult(
			exitStatus: process.terminationStatus,
			standardOutput: String(decoding: standardOutput.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self),
			standardError: String(decoding: standardError.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
		)
	}
}

public enum GitHubCLIExecutableLocator {
	public static func executableURL(
		environment: [String: String] = ProcessInfo.processInfo.environment,
		fileManager: FileManager = .default
	) -> URL? {
		let directories = (environment["PATH"]?.split(separator: ":").map(String.init) ?? []) + ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"]
		for directory in directories {
			let executable = URL(fileURLWithPath: directory).appendingPathComponent("gh").standardizedFileURL
			if fileManager.isExecutableFile(atPath: executable.path) {
				return executable
			}
		}
		return nil
	}
}

public enum GitHubCLICapabilityProbe {
	public static let minimumVersion = GitHubCLIVersion(major: 2, minor: 0, patch: 0)

	public static func probe(
		workspaceURL: URL?,
		environment: [String: String] = ProcessInfo.processInfo.environment,
		executor: some GitHubCLIExecuting = GitHubCLIProcessExecutor(),
		executableURL: URL? = nil,
		locateExecutable: ([String: String]) -> URL? = { GitHubCLIExecutableLocator.executableURL(environment: $0) }
	) -> GitHubCLIProbeStatus {
		guard let executableURL = executableURL ?? locateExecutable(environment) else {
			return finish(.missingExecutable, workspaceURL: workspaceURL)
		}
		guard let versionResult = executor.run(executableURL: executableURL, arguments: ["--version"], workingDirectoryURL: nil), versionResult.exitStatus == 0,
			let version = GitHubCLIVersion.parse(versionResult.standardOutput)
		else {
			return finish(.unavailable, workspaceURL: workspaceURL)
		}
		guard version >= minimumVersion else {
			return finish(.unsupportedVersion(found: version, minimum: minimumVersion), workspaceURL: workspaceURL)
		}
		guard let authentication = executor.run(executableURL: executableURL, arguments: ["auth", "status", "--hostname", "github.com"], workingDirectoryURL: workspaceURL), authentication.exitStatus == 0 else {
			return finish(.unauthenticated, workspaceURL: workspaceURL)
		}
		guard let workspaceURL,
			let repository = executor.run(executableURL: executableURL, arguments: ["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"], workingDirectoryURL: workspaceURL), repository.exitStatus == 0,
			isRepositoryName(repository.standardOutput)
		else {
			return finish(.inaccessibleRepository, workspaceURL: workspaceURL)
		}
		return finish(.ready(GitHubCLICapability(executableURL: executableURL, version: version, repository: repository.standardOutput.trimmingCharacters(in: .whitespacesAndNewlines))), workspaceURL: workspaceURL)
	}

	private static func finish(_ status: GitHubCLIProbeStatus, workspaceURL: URL?) -> GitHubCLIProbeStatus {
		let workspace = workspaceURL?.standardizedFileURL.path ?? "default"
		let detailLogReference = "github://\(workspace)/capability"
		let report: (IntegrationHealthState, String?, String?)
		switch status {
		case .ready:
			report = (.healthy, nil, nil)
		case .missingExecutable:
			report = (.unavailable, "GitHub CLI is unavailable.", "Install GitHub CLI and retry.")
		case let .unsupportedVersion(found, minimum):
			report = (.unavailable, "GitHub CLI \(found.major).\(found.minor).\(found.patch) is below \(minimum.major).\(minimum.minor).\(minimum.patch).", "Upgrade GitHub CLI and retry.")
		case .unauthenticated:
			report = (.degraded, "GitHub CLI is not authenticated.", "Run gh auth login, then retry.")
		case .inaccessibleRepository:
			report = (.degraded, "GitHub repository is inaccessible.", "Check repository access and retry.")
		case .unavailable:
			report = (.unavailable, "GitHub CLI could not be queried.", "Check GitHub CLI and retry.")
		}
		Task {
			await IntegrationHealthStore.shared.report(service: .gitHub, identifier: workspace, lifecycle: .stopped, state: report.0, lastError: report.1, remediation: report.2, detailLogReference: detailLogReference)
		}
		return status
	}

	private static func isRepositoryName(_ value: String) -> Bool {
		let parts = value.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "/", omittingEmptySubsequences: false)
		return parts.count == 2 && !parts[0].isEmpty && !parts[1].isEmpty
	}
}
