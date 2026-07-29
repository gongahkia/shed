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
		let lines = output.split(whereSeparator: { $0.isNewline })
		for line in lines {
			let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
			let version: Substring
			if trimmed.hasPrefix("gh version ") {
				version = trimmed.dropFirst("gh version ".count).split(whereSeparator: { $0.isWhitespace }).first ?? ""
			} else if lines.count == 1 {
				version = Substring(trimmed)
			} else {
				continue
			}
			let value = version.hasPrefix("v") ? version.dropFirst() : version
			let parts = value.split(separator: ".", omittingEmptySubsequences: false)
			guard (2 ... 3).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy({ $0.isNumber }) }), let major = Int(parts[0]), let minor = Int(parts[1]), let patch = parts.count == 3 ? Int(parts[2]) : 0 else {
				continue
			}
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
	case missingWorkspace
	case unauthenticated
	case inaccessibleRepository
	case unavailable
}

public struct GitHubCLIDisabledPresentation: Equatable, Sendable {
	public var title: String
	public var message: String
	public var remediation: String
	public var retryTitle: String

	public init(title: String, message: String, remediation: String, retryTitle: String = "Retry") {
		self.title = title
		self.message = message
		self.remediation = remediation
		self.retryTitle = retryTitle
	}
}

public enum GitHubCLICapabilityAvailability: Equatable, Sendable {
	case enabled(GitHubCLICapability)
	case disabled(GitHubCLIDisabledPresentation)

	public init(status: GitHubCLIProbeStatus) {
		switch status {
		case let .ready(capability):
			self = .enabled(capability)
		case .missingExecutable:
			self = .disabled(.init(title: "GitHub CLI unavailable", message: "GitHub CLI was not found.", remediation: "Install GitHub CLI, then retry."))
		case let .unsupportedVersion(found, minimum):
			self = .disabled(.init(title: "GitHub CLI upgrade required", message: "GitHub CLI \(found.major).\(found.minor).\(found.patch) is below \(minimum.major).\(minimum.minor).\(minimum.patch).", remediation: "Upgrade GitHub CLI, then retry."))
		case .missingWorkspace:
			self = .disabled(.init(title: "GitHub repository unavailable", message: "No workspace is open.", remediation: "Open a GitHub repository, then retry."))
		case .unauthenticated:
			self = .disabled(.init(title: "GitHub authentication required", message: "GitHub CLI is not authenticated for github.com.", remediation: "Run gh auth login, then retry."))
		case .inaccessibleRepository:
			self = .disabled(.init(title: "GitHub repository unavailable", message: "The workspace repository could not be accessed.", remediation: "Check repository access, then retry."))
		case .unavailable:
			self = .disabled(.init(title: "GitHub CLI unavailable", message: "GitHub CLI could not be queried.", remediation: "Check GitHub CLI, then retry."))
		}
	}

	public var isEnabled: Bool {
		if case .enabled = self { return true }
		return false
	}
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
		var seenDirectories: Set<String> = []
		let configuredDirectories = environment["PATH"]?.split(separator: ":", omittingEmptySubsequences: true).map(String.init) ?? []
		for directory in configuredDirectories + ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"] {
			guard directory.hasPrefix("/") else { continue }
			let directoryURL = URL(fileURLWithPath: directory).standardizedFileURL
			guard seenDirectories.insert(directoryURL.path).inserted else { continue }
			let executable = directoryURL.appendingPathComponent("gh").standardizedFileURL
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
		guard let workspaceURL else {
			return finish(.missingWorkspace, workspaceURL: nil)
		}
		guard let authentication = executor.run(executableURL: executableURL, arguments: ["auth", "status", "--hostname", "github.com"], workingDirectoryURL: workspaceURL) else {
			return finish(.unavailable, workspaceURL: workspaceURL)
		}
		guard authentication.exitStatus == 0 else {
			return finish(authentication.exitStatus == 1 ? .unauthenticated : .unavailable, workspaceURL: workspaceURL)
		}
		guard let repository = executor.run(executableURL: executableURL, arguments: ["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"], workingDirectoryURL: workspaceURL), repository.exitStatus == 0,
			isRepositoryName(repository.standardOutput)
		else {
			return finish(.inaccessibleRepository, workspaceURL: workspaceURL)
		}
		return finish(.ready(GitHubCLICapability(executableURL: executableURL, version: version, repository: repository.standardOutput.trimmingCharacters(in: .whitespacesAndNewlines))), workspaceURL: workspaceURL)
	}

	private static func finish(_ status: GitHubCLIProbeStatus, workspaceURL: URL?) -> GitHubCLIProbeStatus {
		let workspace = workspaceURL?.standardizedFileURL.path ?? "default"
		let detailLogReference = "github://\(workspace)/capability"
		let availability = GitHubCLICapabilityAvailability(status: status)
		let report: (IntegrationHealthState, String?, String?)
		switch availability {
		case .enabled:
			report = (.healthy, nil, nil)
		case let .disabled(presentation):
			switch status {
			case .missingExecutable, .unsupportedVersion, .unavailable:
				report = (.unavailable, presentation.message, presentation.remediation)
			case .missingWorkspace, .unauthenticated, .inaccessibleRepository:
				report = (.degraded, presentation.message, presentation.remediation)
			case .ready:
				fatalError("ready capability must be enabled")
			}
		}
		Task {
			await IntegrationHealthStore.shared.report(service: .gitHub, identifier: workspace, lifecycle: .stopped, state: report.0, lastError: report.1, remediation: report.2, detailLogReference: detailLogReference)
		}
		return status
	}

	private static func isRepositoryName(_ value: String) -> Bool {
		let parts = value.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "/", omittingEmptySubsequences: false)
		return parts.count == 2 && parts.allSatisfy { part in
			!part.isEmpty && String(part).unicodeScalars.allSatisfy { !$0.properties.isWhitespace && !CharacterSet.controlCharacters.contains($0) }
		}
	}
}
