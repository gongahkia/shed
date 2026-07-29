import Foundation

public struct ManagedNodeRuntimeArtifact: Equatable, Sendable {
	public var version: String
	public var artifact: ManagedSupportArtifact

	public init(version: String, artifact: ManagedSupportArtifact) {
		self.version = version
		self.artifact = artifact
	}
}

public enum ManagedNodeRuntimeInstallError: Error, Equatable, Sendable {
	case invalidArchive
	case downloadStatus(Int)
	case checksumMismatch(expected: String, actual: String)
	case missingNodeBinary
	case extractionFailed(Int32)
	case installedVersionExists(String)
}

public enum ManagedNodeRuntimeInstaller {
	public static let version = "22.23.1"
	public static let artifacts = ManagedSupportArtifacts(
		arm64: .init(
			version: version,
			archiveURL: URL(string: "https://nodejs.org/download/release/v22.23.1/node-v22.23.1-darwin-arm64.tar.gz")!,
			sha256: "ef28d8fab2c0e4314522d4bb1b7173270aa3937e93b92cb7de79c112ac1fa953",
			format: .directory,
			executablePaths: ["node"]
		),
		x86_64: .init(
			version: version,
			archiveURL: URL(string: "https://nodejs.org/download/release/v22.23.1/node-v22.23.1-darwin-x64.tar.gz")!,
			sha256: "b8da981b8a0b1241b70249204916da76c63573ddf5814dbd2d1e41069105cb81",
			format: .directory,
			executablePaths: ["node"]
		)
	)

	public static func executableURL(
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		fileManager: FileManager = .default
	) -> URL? {
		let componentRoot = installRoot.appendingPathComponent("node-runtime", isDirectory: true)
		guard let versions = try? fileManager.contentsOfDirectory(at: componentRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
			return nil
		}
		for versionURL in versions.sorted(by: { $0.lastPathComponent > $1.lastPathComponent }) {
			let node = versionURL.appendingPathComponent("node")
			if fileManager.isExecutableFile(atPath: node.path) {
				return node
			}
		}
		return nil
	}

	public static func downloadAndInstall(
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		architecture: ManagedSupportArchitecture = .current ?? .arm64,
		fileManager: FileManager = .default,
		session: URLSession = .shared
	) async throws -> URL {
		guard let artifact = artifacts.artifact(for: architecture) else { throw ManagedNodeRuntimeInstallError.invalidArchive }
		let (archive, response) = try await session.download(from: artifact.archiveURL)
		defer { try? fileManager.removeItem(at: archive) }
		guard let response = response as? HTTPURLResponse else { throw ManagedNodeRuntimeInstallError.downloadStatus(-1) }
		guard (200 ... 299).contains(response.statusCode) else { throw ManagedNodeRuntimeInstallError.downloadStatus(response.statusCode) }
		return try install(archiveURL: archive, artifact: artifact, installRoot: installRoot, fileManager: fileManager)
	}

	public static func install(
		archiveURL: URL,
		artifact: ManagedSupportArtifact,
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		fileManager: FileManager = .default
	) throws -> URL {
		let actual = try ManagedSupportInstaller.sha256(url: archiveURL)
		guard actual == artifact.sha256 else {
			throw ManagedNodeRuntimeInstallError.checksumMismatch(expected: artifact.sha256, actual: actual)
		}
		let destination = installRoot.appendingPathComponent("node-runtime", isDirectory: true).appendingPathComponent(artifact.version, isDirectory: true)
		guard !fileManager.fileExists(atPath: destination.path) else {
			if let executable = executableURL(installRoot: installRoot, fileManager: fileManager) { return executable }
			throw ManagedNodeRuntimeInstallError.installedVersionExists(destination.path)
		}
		let parent = destination.deletingLastPathComponent()
		let temporary = parent.appendingPathComponent(".\(artifact.version).tmp-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: temporary, withIntermediateDirectories: true)
		do {
			let archiveName = artifact.archiveURL.lastPathComponent
			guard archiveName.hasSuffix(".tar.gz") else { throw ManagedNodeRuntimeInstallError.invalidArchive }
			let entry = "\(archiveName.dropLast(".tar.gz".count))/bin/node"
			let process = Process()
			process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
			process.arguments = ["-xzf", archiveURL.path, "-C", temporary.path, "--strip-components=2", entry]
			process.standardOutput = Pipe()
			process.standardError = Pipe()
			try process.run()
			process.waitUntilExit()
			guard process.terminationStatus == 0 else { throw ManagedNodeRuntimeInstallError.extractionFailed(process.terminationStatus) }
			let node = temporary.appendingPathComponent("node")
			guard fileManager.fileExists(atPath: node.path) else { throw ManagedNodeRuntimeInstallError.missingNodeBinary }
			try fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: node.path)
			try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
			try fileManager.moveItem(at: temporary, to: destination)
			return destination.appendingPathComponent("node")
		} catch {
			try? fileManager.removeItem(at: temporary)
			throw error
		}
	}
}
