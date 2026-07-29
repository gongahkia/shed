import CryptoKit
import Foundation

public enum ManagedSupportArchiveFormat: String, Codable, Equatable, Sendable {
	case directory
	case zip
}

public struct ManagedSupportArtifact: Codable, Equatable, Sendable {
	public var version: String
	public var archiveURL: URL
	public var sha256: String
	public var format: ManagedSupportArchiveFormat
	public var executablePaths: [String]

	public init(version: String, archiveURL: URL, sha256: String, format: ManagedSupportArchiveFormat, executablePaths: [String]) {
		self.version = version
		self.archiveURL = archiveURL
		self.sha256 = sha256.lowercased()
		self.format = format
		self.executablePaths = executablePaths
	}
}

public struct ManagedSupportInstallRequest: Sendable {
	public var component: ManagedSupportComponent
	public var artifact: ManagedSupportArtifact
	public var installRoot: URL

	public init(component: ManagedSupportComponent, artifact: ManagedSupportArtifact, installRoot: URL = ManagedSupportInstaller.defaultInstallRoot()) {
		self.component = component
		self.artifact = artifact
		self.installRoot = installRoot
	}
}

public struct ManagedSupportInstallReceipt: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var componentID: String
	public var version: String
	public var sha256: String
	public var executablePaths: [String]
	public var installedAt: Date

	public init(componentID: String, version: String, sha256: String, executablePaths: [String], installedAt: Date = Date()) {
		schemaVersion = 1
		self.componentID = componentID
		self.version = version
		self.sha256 = sha256.lowercased()
		self.executablePaths = executablePaths.sorted()
		self.installedAt = installedAt
	}
}

public enum ManagedSupportInstallError: Error, Equatable, Sendable {
	case invalidArtifactURL(String)
	case invalidSHA256(String)
	case invalidVersion(String)
	case invalidExecutablePath(String)
	case downloadStatus(Int)
	case sha256Mismatch(expected: String, actual: String)
	case archiveExtractionFailed(String)
	case symlinkRejected(String)
	case nestedAppBundleRejected(String)
	case unexpectedExecutable(String)
	case missingExecutable(String)
	case installedVersionExists(String)
	case unsupportedArchiveFormat(String)
}

public enum ManagedSupportInstaller {
	public static func defaultInstallRoot(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory
			.appendingPathComponent("Library", isDirectory: true)
			.appendingPathComponent("Application Support", isDirectory: true)
			.appendingPathComponent("Itsy", isDirectory: true)
			.appendingPathComponent("Support", isDirectory: true)
	}

	public static func receiptURL(installedURL: URL) -> URL {
		installedURL.appendingPathComponent(".itsy-support-receipt.json")
	}

	public static func installedURL(for request: ManagedSupportInstallRequest) -> URL {
		request.installRoot
			.appendingPathComponent(request.component.id, isDirectory: true)
			.appendingPathComponent(request.artifact.version, isDirectory: true)
	}

	public static func downloadAndInstall(_ request: ManagedSupportInstallRequest, fileManager: FileManager = .default, session: URLSession = .shared) async throws -> ManagedSupportInstallReceipt {
		try validate(request)
		guard request.artifact.archiveURL.scheme?.lowercased() == "https" else {
			throw ManagedSupportInstallError.invalidArtifactURL(request.artifact.archiveURL.absoluteString)
		}
		let (archiveURL, response) = try await session.download(from: request.artifact.archiveURL)
		guard let response = response as? HTTPURLResponse else {
			throw ManagedSupportInstallError.downloadStatus(-1)
		}
		guard (200 ... 299).contains(response.statusCode) else {
			throw ManagedSupportInstallError.downloadStatus(response.statusCode)
		}
		defer { try? fileManager.removeItem(at: archiveURL) }
		return try install(request, archiveURL: archiveURL, fileManager: fileManager)
	}

	public static func install(_ request: ManagedSupportInstallRequest, archiveURL: URL, fileManager: FileManager = .default) throws -> ManagedSupportInstallReceipt {
		try validate(request)
		let actualSHA = try sha256(url: archiveURL)
		guard actualSHA == request.artifact.sha256 else {
			throw ManagedSupportInstallError.sha256Mismatch(expected: request.artifact.sha256, actual: actualSHA)
		}
		let destination = installedURL(for: request)
		guard !fileManager.fileExists(atPath: destination.path) else {
			throw ManagedSupportInstallError.installedVersionExists(destination.path)
		}
		let parent = destination.deletingLastPathComponent()
		try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
		let temporary = parent.appendingPathComponent(".(request.artifact.version).tmp-(UUID().uuidString)", isDirectory: true)
		do {
			try extract(archiveURL: archiveURL, format: request.artifact.format, destination: temporary, fileManager: fileManager)
			try validateInstallTree(root: temporary, allowedExecutables: Set(request.artifact.executablePaths), fileManager: fileManager)
			let receipt = ManagedSupportInstallReceipt(
				componentID: request.component.id,
				version: request.artifact.version,
				sha256: actualSHA,
				executablePaths: request.artifact.executablePaths
			)
			try JSONEncoder().encode(receipt).write(to: receiptURL(installedURL: temporary), options: .atomic)
			try fileManager.moveItem(at: temporary, to: destination)
			return receipt
		} catch {
			try? fileManager.removeItem(at: temporary)
			throw error
		}
	}

	public static func loadReceipt(installedURL: URL) throws -> ManagedSupportInstallReceipt {
		try JSONDecoder().decode(ManagedSupportInstallReceipt.self, from: Data(contentsOf: receiptURL(installedURL: installedURL)))
	}

	public static func sha256(url: URL) throws -> String {
		var isDirectory: ObjCBool = false
		guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
			throw CocoaError(.fileNoSuchFile)
		}
		let digest: SHA256.Digest
		if isDirectory.boolValue {
			digest = try directorySHA256(url: url)
		} else {
			digest = SHA256.hash(data: try Data(contentsOf: url))
		}
		return digest.map { String(format: "%02x", $0) }.joined()
	}

	private static func validate(_ request: ManagedSupportInstallRequest) throws {
		guard request.component.installMode == .managed else {
			throw ManagedSupportInstallError.unsupportedArchiveFormat(request.component.installMode.rawValue)
		}
		guard isSHA256(request.artifact.sha256) else {
			throw ManagedSupportInstallError.invalidSHA256(request.artifact.sha256)
		}
		guard isPlainPathComponent(request.component.id), isPlainPathComponent(request.artifact.version) else {
			throw ManagedSupportInstallError.invalidVersion(request.artifact.version)
		}
		guard !request.artifact.executablePaths.isEmpty else {
			throw ManagedSupportInstallError.missingExecutable("")
		}
		for path in request.artifact.executablePaths where !isSafeRelativePath(path) {
			throw ManagedSupportInstallError.invalidExecutablePath(path)
		}
	}

	private static func extract(archiveURL: URL, format: ManagedSupportArchiveFormat, destination: URL, fileManager: FileManager) throws {
		switch format {
		case .directory:
			try fileManager.copyItem(at: archiveURL, to: destination)
		case .zip:
			try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
			let process = Process()
			process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
			process.arguments = ["-x", "-k", archiveURL.path, destination.path]
			process.standardOutput = Pipe()
			process.standardError = Pipe()
			do {
				try process.run()
				process.waitUntilExit()
			} catch {
				throw ManagedSupportInstallError.archiveExtractionFailed(String(describing: error))
			}
			guard process.terminationStatus == 0 else {
				throw ManagedSupportInstallError.archiveExtractionFailed("ditto exited \(process.terminationStatus)")
			}
		}
	}

	private static func validateInstallTree(root: URL, allowedExecutables: Set<String>, fileManager: FileManager) throws {
		let keys: Set<URLResourceKey> = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .isExecutableKey]
		guard let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]) else {
			throw ManagedSupportInstallError.missingExecutable("")
		}
		var foundAllowedFiles: [String: URL] = [:]
		for case let url as URL in enumerator {
			let values = try url.resourceValues(forKeys: keys)
			let relative = relativePath(url, root: root)
			if values.isSymbolicLink == true {
				throw ManagedSupportInstallError.symlinkRejected(relative)
			}
			if values.isDirectory == true, url.pathExtension == "app" {
				throw ManagedSupportInstallError.nestedAppBundleRejected(relative)
			}
			if values.isRegularFile == true {
				if values.isExecutable == true, !allowedExecutables.contains(relative) {
					throw ManagedSupportInstallError.unexpectedExecutable(relative)
				}
				if allowedExecutables.contains(relative) {
					foundAllowedFiles[relative] = url
				}
			}
		}
		for executable in allowedExecutables {
			guard let url = foundAllowedFiles[executable] else {
				throw ManagedSupportInstallError.missingExecutable(executable)
			}
			try fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		}
	}

	private static func relativePath(_ url: URL, root: URL) -> String {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
	}

	private static func directorySHA256(url: URL, fileManager: FileManager = .default) throws -> SHA256.Digest {
		let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
		guard let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]) else {
			throw CocoaError(.fileReadUnknown)
		}
		let entries = enumerator.compactMap { $0 as? URL }.sorted { $0.path < $1.path }
		var hasher = SHA256()
		for entry in entries {
			let values = try entry.resourceValues(forKeys: keys)
			guard values.isSymbolicLink != true else {
				throw ManagedSupportInstallError.symlinkRejected(relativePath(entry, root: url))
			}
			let relative = relativePath(entry, root: url)
			hasher.update(data: Data(relative.utf8))
			hasher.update(data: Data([0]))
			if values.isRegularFile == true {
				hasher.update(data: try Data(contentsOf: entry))
			}
			hasher.update(data: Data([0]))
		}
		return hasher.finalize()
	}

	private static func isSHA256(_ value: String) -> Bool {
		value.count == 64 && value.allSatisfy { "0123456789abcdef".contains($0) }
	}

	private static func isPlainPathComponent(_ value: String) -> Bool {
		!value.isEmpty && !value.contains("/") && value != "." && value != ".."
	}

	private static func isSafeRelativePath(_ value: String) -> Bool {
		!value.hasPrefix("/") && !value.split(separator: "/").contains(where: { $0 == "." || $0 == ".." })
	}
}
