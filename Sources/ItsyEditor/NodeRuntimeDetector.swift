import Foundation

public struct NodeRuntime: Equatable, Sendable {
	public let executableURL: URL
	public let version: LSPServerVersion

	public init(executableURL: URL, version: LSPServerVersion) {
		self.executableURL = executableURL.standardizedFileURL
		self.version = version
	}
}

public enum NodeRuntimeDetector {
	public static let minimumVersion = LSPServerVersion(major: 20)

	public static func resolve(
		environment: [String: String] = ProcessInfo.processInfo.environment,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default,
		versionReader: ((URL) -> String?)? = nil
	) -> NodeRuntime? {
		for candidate in candidates(environment: environment, homeDirectory: homeDirectory, fileManager: fileManager)
			where fileManager.isExecutableFile(atPath: candidate.path)
		{
			let output = versionReader?(candidate) ?? readVersion(at: candidate, environment: environment)
			guard let output, let version = LSPServerVersion.parse(output), version >= minimumVersion else {
				continue
			}
			return NodeRuntime(executableURL: candidate, version: version)
		}
		return nil
	}

	public static func launchEnvironment(
		for runtime: NodeRuntime,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> [String: String] {
		var environment = environment
		let preferredDirectories = [
			runtime.executableURL.deletingLastPathComponent().path,
			"/opt/homebrew/bin",
			"/usr/local/bin",
		]
		let existingDirectories = (environment["PATH"] ?? "").split(separator: ":").map(String.init)
		var seen: Set<String> = []
		environment["PATH"] = (preferredDirectories + existingDirectories).filter { seen.insert($0).inserted }
			.joined(separator: ":")
		return environment
	}

	private static func candidates(
		environment: [String: String],
		homeDirectory: URL,
		fileManager: FileManager
	) -> [URL] {
		var paths: [String] = []
		if let override = environment["ITSY_NODE_PATH"], override.hasPrefix("/") {
			paths.append(override)
		}
		for directory in (environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin").split(separator: ":")
			where !directory.isEmpty
		{
			paths.append(URL(fileURLWithPath: String(directory)).appendingPathComponent("node").path)
		}
		for variable in ["NVM_BIN", "FNM_MULTISHELL_PATH"] {
			if let directory = environment[variable], directory.hasPrefix("/") {
				paths.append(URL(fileURLWithPath: directory).appendingPathComponent("node").path)
			}
		}
		paths += [
			"/opt/homebrew/bin/node",
			"/usr/local/bin/node",
			homeDirectory.appendingPathComponent(".volta/bin/node").path,
			homeDirectory.appendingPathComponent(".asdf/shims/node").path,
			homeDirectory.appendingPathComponent(".local/share/mise/shims/node").path,
		]
		paths += versionManagedCandidates(
			in: homeDirectory.appendingPathComponent(".local/share/fnm/node-versions", isDirectory: true),
			suffix: ["installation", "bin", "node"],
			fileManager: fileManager
		)
		paths += versionManagedCandidates(
			in: homeDirectory.appendingPathComponent(".nvm/versions/node", isDirectory: true),
			suffix: ["bin", "node"],
			fileManager: fileManager
		)
		var seen: Set<String> = []
		return paths.map { URL(fileURLWithPath: $0).standardizedFileURL }.filter { seen.insert($0.path).inserted }
	}

	private static func versionManagedCandidates(in root: URL, suffix: [String], fileManager: FileManager) -> [String] {
		guard let versions = try? fileManager.contentsOfDirectory(
			at: root,
			includingPropertiesForKeys: [.isDirectoryKey],
			options: [.skipsHiddenFiles]
		) else {
			return []
		}
		return versions.sorted { $0.lastPathComponent > $1.lastPathComponent }.map { version in
			suffix.reduce(version) { $0.appendingPathComponent($1) }.path
		}
	}

	private static func readVersion(at executableURL: URL, environment: [String: String]) -> String? {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = ["--version"]
		process.environment = environment
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
