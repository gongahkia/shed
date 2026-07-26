import Foundation

public struct LSPServerVersion: Comparable, Equatable, Sendable {
	public var major: Int
	public var minor: Int
	public var patch: Int

	public init(major: Int, minor: Int = 0, patch: Int = 0) {
		self.major = major
		self.minor = minor
		self.patch = patch
	}

	public static func < (lhs: LSPServerVersion, rhs: LSPServerVersion) -> Bool {
		(lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
	}

	public static func parse(_ output: String) -> LSPServerVersion? {
		for token in output.split(whereSeparator: { !$0.isNumber && $0 != "." }) {
			let parts = token.split(separator: ".", omittingEmptySubsequences: false)
			guard (1 ... 3).contains(parts.count), let major = Int(parts[0]) else {
				continue
			}
			let minor = parts.count > 1 ? Int(parts[1]) : 0
			let patch = parts.count > 2 ? Int(parts[2]) : 0
			guard let minor, let patch else {
				continue
			}
			return LSPServerVersion(major: major, minor: minor, patch: patch)
		}
		return nil
	}
}

public struct LSPExecutableProbe: Equatable, Sendable {
	public var executable: String
	public var approvedPlatformLocations: [String]
	public var overrideEnvironmentVariable: String
	public var versionArguments: [String]
	public var minimumVersion: LSPServerVersion?

	public init(
		executable: String,
		approvedPlatformLocations: [String] = ["/opt/homebrew/bin", "/usr/local/bin"],
		overrideEnvironmentVariable: String? = nil,
		versionArguments: [String] = ["--version"],
		minimumVersion: LSPServerVersion? = nil
	) {
		self.executable = executable
		self.approvedPlatformLocations = approvedPlatformLocations
		self.overrideEnvironmentVariable = overrideEnvironmentVariable ?? "ITSY_LSP_\(Self.environmentComponent(executable))_PATH"
		self.versionArguments = versionArguments
		self.minimumVersion = minimumVersion
	}

	private static func environmentComponent(_ executable: String) -> String {
		executable.uppercased().map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined()
	}
}

public enum LSPExecutableResolutionSource: String, Equatable, Sendable {
	case explicitCommand
	case environmentOverride
	case path
	case approvedPlatformLocation
}

public struct LSPExecutableResolution: Equatable, Sendable {
	public var executableURL: URL
	public var source: LSPExecutableResolutionSource
	public var version: LSPServerVersion?

	public init(executableURL: URL, source: LSPExecutableResolutionSource, version: LSPServerVersion?) {
		self.executableURL = executableURL.standardizedFileURL
		self.source = source
		self.version = version
	}
}

public enum LSPExecutableDetectionError: Error, Equatable, Sendable {
	case invalidEnvironmentOverride(String, String)
	case missingExecutable(String)
	case unreadableVersion(String, String)
	case unsupportedVersion(String, found: LSPServerVersion, minimum: LSPServerVersion)
}

public enum LSPExecutableDetector {
	public static func detect(
		command: String,
		probe: LSPExecutableProbe? = nil,
		environment: [String: String] = ProcessInfo.processInfo.environment,
		fileManager: FileManager = .default,
		versionReader: ((URL, [String]) -> String?)? = nil
	) throws -> LSPExecutableResolution {
		let executable = probe?.executable ?? command
		let candidates = try candidateURLs(command: command, probe: probe, environment: environment)
		guard let candidate = candidates.first(where: { fileManager.isExecutableFile(atPath: $0.url.path) }) else {
			throw LSPExecutableDetectionError.missingExecutable(executable)
		}
		guard let minimumVersion = probe?.minimumVersion else {
			return LSPExecutableResolution(executableURL: candidate.url, source: candidate.source, version: nil)
		}
		let output = versionReader?(candidate.url, probe?.versionArguments ?? []) ?? readVersion(at: candidate.url, arguments: probe?.versionArguments ?? [])
		guard let output, let version = LSPServerVersion.parse(output) else {
			throw LSPExecutableDetectionError.unreadableVersion(executable, candidate.url.path)
		}
		guard version >= minimumVersion else {
			throw LSPExecutableDetectionError.unsupportedVersion(executable, found: version, minimum: minimumVersion)
		}
		return LSPExecutableResolution(executableURL: candidate.url, source: candidate.source, version: version)
	}

	private static func candidateURLs(command: String, probe: LSPExecutableProbe?, environment: [String: String]) throws -> [(url: URL, source: LSPExecutableResolutionSource)] {
		if command.hasPrefix("/") {
			return [(URL(fileURLWithPath: command), .explicitCommand)]
		}
		if let variable = probe?.overrideEnvironmentVariable, let override = environment[variable], !override.isEmpty {
			guard override.hasPrefix("/") else {
				throw LSPExecutableDetectionError.invalidEnvironmentOverride(variable, override)
			}
			return [(URL(fileURLWithPath: override), .environmentOverride)]
		}
		var candidates: [(url: URL, source: LSPExecutableResolutionSource)] = []
		for directory in (environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin").split(separator: ":") where !directory.isEmpty {
			candidates.append((URL(fileURLWithPath: String(directory)).appendingPathComponent(command), .path))
		}
		for directory in probe?.approvedPlatformLocations ?? [] {
			candidates.append((URL(fileURLWithPath: directory).appendingPathComponent(command), .approvedPlatformLocation))
		}
		var seen: Set<String> = []
		return candidates.filter { seen.insert($0.url.standardizedFileURL.path).inserted }
	}

	private static func readVersion(at executableURL: URL, arguments: [String]) -> String? {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		let output = Pipe()
		process.standardOutput = output
		process.standardError = Pipe()
		do {
			try process.run()
			process.waitUntilExit()
		} catch {
			return nil
		}
		guard process.terminationStatus == 0 else {
			return nil
		}
		return String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
	}
}
