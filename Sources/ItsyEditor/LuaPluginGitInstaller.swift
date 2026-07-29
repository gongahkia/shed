import Foundation

public struct LuaPluginGitInstallRequest: Sendable {
	public let repositoryURL: String
	public let revision: String
	public let packageIdentifier: String
	public let packageVersion: String
	public let installRoot: URL

	public init(repositoryURL: String, revision: String, packageIdentifier: String, packageVersion: String, installRoot: URL) {
		self.repositoryURL = repositoryURL
		self.revision = revision
		self.packageIdentifier = packageIdentifier
		self.packageVersion = packageVersion
		self.installRoot = installRoot.standardizedFileURL
	}
}

public struct LuaPluginGitInstallReceipt: Codable, Equatable, Sendable {
	public static let currentSchemaVersion = 1

	public let schemaVersion: Int
	public let repositoryURL: String
	public let revision: String
	public let packageIdentifier: String
	public let packageVersion: String
	public let installedAt: Date

	public init(repositoryURL: String, revision: String, packageIdentifier: String, packageVersion: String, installedAt: Date = Date()) {
		schemaVersion = Self.currentSchemaVersion
		self.repositoryURL = repositoryURL
		self.revision = revision.lowercased()
		self.packageIdentifier = packageIdentifier
		self.packageVersion = packageVersion
		self.installedAt = installedAt
	}
}

public enum LuaPluginGitInstallError: Error, Equatable, Sendable {
	case invalidRepositoryURL(String)
	case invalidRevision(String)
	case invalidPackageIdentifier(String)
	case invalidPackageVersion(String)
	case packageAlreadyInstalled(URL)
	case packageNotInstalled(URL)
	case invalidReceipt(URL)
	case receiptIdentityMismatch(URL)
	case repositoryMismatch(expected: String, actual: String)
	case gitFailure(GitCommandError)
	case revisionMismatch(expected: String, actual: String)
	case invalidManifest(LuaPluginManifestError)
	case manifestIdentityMismatch(expectedIdentifier: String, expectedVersion: String, actualIdentifier: String, actualVersion: String)
}

public enum LuaPluginGitInstaller {
	public static func defaultInstallRoot(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		LuaPluginDiscovery.globalRoot(homeDirectory: homeDirectory)
	}

	public static func installedURL(for request: LuaPluginGitInstallRequest) -> URL {
		request.installRoot
			.appendingPathComponent(request.packageIdentifier, isDirectory: true)
			.appendingPathComponent(request.packageVersion, isDirectory: true)
	}

	public static func receiptURL(installedURL: URL) -> URL {
		installedURL.appendingPathComponent(".itsy-plugin-lock.json")
	}

	public static func install(
		_ request: LuaPluginGitInstallRequest,
		fileManager: FileManager = .default,
		runner: any GitCommandRunning = ProcessGitCommandRunner()
	) throws -> LuaPluginGitInstallReceipt {
		try validate(request)
		let destination = installedURL(for: request)
		guard !fileManager.fileExists(atPath: destination.path) else {
			throw LuaPluginGitInstallError.packageAlreadyInstalled(destination)
		}
		let staged = try stage(request, fileManager: fileManager, runner: runner)
		defer { try? fileManager.removeItem(at: staged.url) }
		try fileManager.moveItem(at: staged.url, to: destination)
		return staged.receipt
	}

	public static func update(
		_ request: LuaPluginGitInstallRequest,
		fileManager: FileManager = .default,
		runner: any GitCommandRunning = ProcessGitCommandRunner()
	) throws -> LuaPluginGitInstallReceipt {
		try validate(request)
		let destination = installedURL(for: request)
		guard fileManager.fileExists(atPath: destination.path) else {
			throw LuaPluginGitInstallError.packageNotInstalled(destination)
		}
		let existing = try loadReceipt(installedURL: destination)
		guard existing.packageIdentifier == request.packageIdentifier, existing.packageVersion == request.packageVersion else {
			throw LuaPluginGitInstallError.receiptIdentityMismatch(destination)
		}
		guard existing.repositoryURL == request.repositoryURL else {
			throw LuaPluginGitInstallError.repositoryMismatch(expected: existing.repositoryURL, actual: request.repositoryURL)
		}
		let staged = try stage(request, fileManager: fileManager, runner: runner)
		defer { try? fileManager.removeItem(at: staged.url) }
		try replace(destination: destination, with: staged.url, fileManager: fileManager)
		return staged.receipt
	}

	@discardableResult
	public static func remove(
		packageIdentifier: String,
		packageVersion: String,
		installRoot: URL = defaultInstallRoot(),
		fileManager: FileManager = .default
	) throws -> LuaPluginGitInstallReceipt {
		try validateIdentity(packageIdentifier: packageIdentifier, packageVersion: packageVersion)
		let destination = installRoot.standardizedFileURL
			.appendingPathComponent(packageIdentifier, isDirectory: true)
			.appendingPathComponent(packageVersion, isDirectory: true)
		guard fileManager.fileExists(atPath: destination.path) else {
			throw LuaPluginGitInstallError.packageNotInstalled(destination)
		}
		let receipt = try loadReceipt(installedURL: destination)
		guard receipt.packageIdentifier == packageIdentifier, receipt.packageVersion == packageVersion else {
			throw LuaPluginGitInstallError.receiptIdentityMismatch(destination)
		}
		try fileManager.removeItem(at: destination)
		return receipt
	}

	public static func loadReceipt(installedURL: URL) throws -> LuaPluginGitInstallReceipt {
		let receiptURL = receiptURL(installedURL: installedURL)
		let receipt = try JSONDecoder().decode(LuaPluginGitInstallReceipt.self, from: Data(contentsOf: receiptURL))
		guard receipt.schemaVersion == LuaPluginGitInstallReceipt.currentSchemaVersion,
		      !receipt.repositoryURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
		      normalizedRevision(receipt.revision) == receipt.revision,
		      isValidIdentifier(receipt.packageIdentifier),
		      (try? LuaPluginVersion(parsing: receipt.packageVersion)) != nil
		else {
			throw LuaPluginGitInstallError.invalidReceipt(receiptURL)
		}
		return receipt
	}

	private static func stage(
		_ request: LuaPluginGitInstallRequest,
		fileManager: FileManager,
		runner: any GitCommandRunning
	) throws -> (url: URL, receipt: LuaPluginGitInstallReceipt) {
		let destination = installedURL(for: request)
		let parent = destination.deletingLastPathComponent()
		try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
		let staged = parent.appendingPathComponent(".\(request.packageVersion).staged-\(UUID().uuidString)", isDirectory: true)
		do {
			_ = try runGit(["clone", "--no-checkout", request.repositoryURL, staged.path], root: parent, runner: runner)
			_ = try runGit(["checkout", "--detach", request.revision], root: staged, runner: runner)
			let resolvedRevision = try runGit(["rev-parse", "HEAD"], root: staged, runner: runner)
			guard let actualRevision = normalizedRevision(resolvedRevision) else {
				throw LuaPluginGitInstallError.revisionMismatch(
					expected: request.revision.lowercased(),
					actual: resolvedRevision.trimmingCharacters(in: .whitespacesAndNewlines)
				)
			}
			guard actualRevision == request.revision.lowercased() else {
				throw LuaPluginGitInstallError.revisionMismatch(expected: request.revision.lowercased(), actual: actualRevision)
			}
			let manifest: LuaPluginManifest
			do {
				manifest = try LuaPluginManifestLoader.load(packageRoot: staged, fileManager: fileManager)
			} catch let error as LuaPluginManifestError {
				throw LuaPluginGitInstallError.invalidManifest(error)
			}
			let actualVersion = versionText(manifest.version)
			guard manifest.identifier == request.packageIdentifier, actualVersion == request.packageVersion else {
				throw LuaPluginGitInstallError.manifestIdentityMismatch(
					expectedIdentifier: request.packageIdentifier,
					expectedVersion: request.packageVersion,
					actualIdentifier: manifest.identifier,
					actualVersion: actualVersion
				)
			}
			try fileManager.removeItem(at: staged.appendingPathComponent(".git", isDirectory: true))
			let receipt = LuaPluginGitInstallReceipt(
				repositoryURL: request.repositoryURL,
				revision: actualRevision,
				packageIdentifier: manifest.identifier,
				packageVersion: actualVersion
			)
			try JSONEncoder().encode(receipt).write(to: receiptURL(installedURL: staged), options: .atomic)
			return (staged, receipt)
		} catch {
			try? fileManager.removeItem(at: staged)
			throw error
		}
	}

	private static func replace(destination: URL, with staged: URL, fileManager: FileManager) throws {
		let backup = destination.deletingLastPathComponent().appendingPathComponent(".\(destination.lastPathComponent).backup-\(UUID().uuidString)", isDirectory: true)
		try fileManager.moveItem(at: destination, to: backup)
		do {
			try fileManager.moveItem(at: staged, to: destination)
			try? fileManager.removeItem(at: backup)
		} catch {
			if fileManager.fileExists(atPath: backup.path), !fileManager.fileExists(atPath: destination.path) {
				try? fileManager.moveItem(at: backup, to: destination)
			}
			throw error
		}
	}

	private static func validate(_ request: LuaPluginGitInstallRequest) throws {
		guard !request.repositoryURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw LuaPluginGitInstallError.invalidRepositoryURL(request.repositoryURL)
		}
		guard normalizedRevision(request.revision) != nil else {
			throw LuaPluginGitInstallError.invalidRevision(request.revision)
		}
		try validateIdentity(request)
	}

	private static func validateIdentity(_ request: LuaPluginGitInstallRequest) throws {
		try validateIdentity(packageIdentifier: request.packageIdentifier, packageVersion: request.packageVersion)
	}

	private static func validateIdentity(packageIdentifier: String, packageVersion: String) throws {
		guard isValidIdentifier(packageIdentifier) else {
			throw LuaPluginGitInstallError.invalidPackageIdentifier(packageIdentifier)
		}
		guard (try? LuaPluginVersion(parsing: packageVersion)) != nil else {
			throw LuaPluginGitInstallError.invalidPackageVersion(packageVersion)
		}
	}

	private static func runGit(_ arguments: [String], root: URL, runner: any GitCommandRunning) throws -> String {
		do {
			return try runner.runGit(arguments: arguments, root: root)
		} catch let error as GitCommandError {
			throw LuaPluginGitInstallError.gitFailure(error)
		}
	}

	private static func normalizedRevision(_ value: String) -> String? {
		let revision = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		guard [40, 64].contains(revision.count), revision.allSatisfy(\.isHexDigit) else {
			return nil
		}
		return revision
	}

	private static func isValidIdentifier(_ value: String) -> Bool {
		guard let first = value.unicodeScalars.first,
		      isAlphanumeric(first),
		      let last = value.unicodeScalars.last,
		      isAlphanumeric(last)
		else {
			return false
		}
		return value.unicodeScalars.allSatisfy { isAlphanumeric($0) || $0 == "." || $0 == "-" || $0 == "_" }
	}

	private static func isAlphanumeric(_ scalar: UnicodeScalar) -> Bool {
		(48...57).contains(scalar.value) || (65...90).contains(scalar.value) || (97...122).contains(scalar.value)
	}

	private static func versionText(_ version: LuaPluginVersion) -> String {
		"\(version.major).\(version.minor).\(version.patch)"
	}
}
