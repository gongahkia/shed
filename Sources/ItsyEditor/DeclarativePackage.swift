import CryptoKit
import Foundation

public enum DeclarativePackageScope: String, Codable, Equatable, Sendable {
	case global
	case project
}

public enum DeclarativePackageSourceKind: String, Codable, Equatable, Sendable {
	case path
	case url
	case registry
}

public struct DeclarativePackageResourceFilter: Codable, Equatable, Sendable {
	public var resource: String
	public var enabled: Bool

	public init(resource: String, enabled: Bool) {
		self.resource = resource
		self.enabled = enabled
	}
}

public struct DeclarativePackageSource: Codable, Equatable, Sendable, Identifiable {
	public var id: String
	public var packageID: String
	public var kind: DeclarativePackageSourceKind
	public var location: String
	public var expectedSHA256: String?
	public var enabled: Bool
	public var resourceFilters: [DeclarativePackageResourceFilter]

	public init(
		id: String,
		packageID: String,
		kind: DeclarativePackageSourceKind,
		location: String,
		expectedSHA256: String? = nil,
		enabled: Bool = true,
		resourceFilters: [DeclarativePackageResourceFilter] = []
	) {
		self.id = id
		self.packageID = packageID
		self.kind = kind
		self.location = location
		self.expectedSHA256 = expectedSHA256?.lowercased()
		self.enabled = enabled
		self.resourceFilters = resourceFilters
	}

	private enum CodingKeys: String, CodingKey {
		case id
		case packageID = "package_id"
		case kind
		case location
		case expectedSHA256 = "sha256"
		case enabled
		case resourceFilters = "resource_filters"
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		packageID = try container.decode(String.self, forKey: .packageID)
		kind = try container.decode(DeclarativePackageSourceKind.self, forKey: .kind)
		location = try container.decode(String.self, forKey: .location)
		expectedSHA256 = try container.decodeIfPresent(String.self, forKey: .expectedSHA256)?.lowercased()
		enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
		resourceFilters = try container.decodeIfPresent([DeclarativePackageResourceFilter].self, forKey: .resourceFilters) ?? []
	}
}

public struct DeclarativePackageConfiguration: Codable, Equatable, Sendable {
	public static let currentSchemaVersion = 1
	public var schemaVersion: Int
	public var sources: [DeclarativePackageSource]

	public init(schemaVersion: Int = DeclarativePackageConfiguration.currentSchemaVersion, sources: [DeclarativePackageSource] = []) {
		self.schemaVersion = schemaVersion
		self.sources = sources
	}

	private enum CodingKeys: String, CodingKey {
		case schemaVersion = "schema_version"
		case sources
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
		sources = try container.decodeIfPresent([DeclarativePackageSource].self, forKey: .sources) ?? []
	}
}

public enum DeclarativePackageConfigurationError: Error, Equatable, Sendable, CustomStringConvertible {
	case malformed(String)
	case unsupportedSchemaVersion(Int)
	case emptySourceID(Int)
	case duplicateSourceID(String)
	case emptyPackageID(String)
	case emptyLocation(String)
	case invalidLocalPath(String)
	case invalidHTTPSURL(String)
	case invalidSHA256(String)
	case missingSHA256(String)
	case emptyResourceFilter(String)
	case duplicateResourceFilter(sourceID: String, resource: String)

	public var description: String {
		switch self {
		case let .malformed(message): "Malformed package configuration: \(message)"
		case let .unsupportedSchemaVersion(version): "Unsupported package configuration schema version \(version)."
		case let .emptySourceID(index): "Source \(index + 1) requires an id."
		case let .duplicateSourceID(id): "Duplicate package source id \(id)."
		case let .emptyPackageID(id): "Package source \(id) requires a package_id."
		case let .emptyLocation(id): "Package source \(id) requires a location."
		case let .invalidLocalPath(id): "Package source \(id) requires an absolute path without traversal."
		case let .invalidHTTPSURL(id): "Package source \(id) requires an HTTPS URL."
		case let .invalidSHA256(id): "Package source \(id) has an invalid SHA-256 digest."
		case let .missingSHA256(id): "Package source \(id) requires a SHA-256 digest."
		case let .emptyResourceFilter(id): "Package source \(id) has an empty resource filter."
		case let .duplicateResourceFilter(sourceID, resource): "Package source \(sourceID) has a duplicate filter for \(resource)."
		}
	}
}

public enum DeclarativePackageConfigurationParser {
	public static func parse(data: Data) throws -> DeclarativePackageConfiguration {
		let configuration: DeclarativePackageConfiguration
		do {
			configuration = try JSONDecoder().decode(DeclarativePackageConfiguration.self, from: data)
		} catch {
			throw DeclarativePackageConfigurationError.malformed(String(describing: error))
		}
		try validate(configuration)
		return configuration
	}

	public static func validate(_ configuration: DeclarativePackageConfiguration) throws {
		guard configuration.schemaVersion == DeclarativePackageConfiguration.currentSchemaVersion else {
			throw DeclarativePackageConfigurationError.unsupportedSchemaVersion(configuration.schemaVersion)
		}
		var sourceIDs = Set<String>()
		for (index, source) in configuration.sources.enumerated() {
			let id = source.id.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !id.isEmpty else {
				throw DeclarativePackageConfigurationError.emptySourceID(index)
			}
			guard sourceIDs.insert(id).inserted else {
				throw DeclarativePackageConfigurationError.duplicateSourceID(id)
			}
			guard !source.packageID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
				throw DeclarativePackageConfigurationError.emptyPackageID(id)
			}
			guard !source.location.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
				throw DeclarativePackageConfigurationError.emptyLocation(id)
			}
			switch source.kind {
			case .path:
				let components = source.location.split(separator: "/", omittingEmptySubsequences: true)
				guard source.location.hasPrefix("/"), !components.contains("..") else {
					throw DeclarativePackageConfigurationError.invalidLocalPath(id)
				}
				if source.location.lowercased().hasSuffix(".zip"), source.expectedSHA256 == nil {
					throw DeclarativePackageConfigurationError.missingSHA256(id)
				}
			case .url, .registry:
				guard URL(string: source.location)?.scheme?.lowercased() == "https" else {
					throw DeclarativePackageConfigurationError.invalidHTTPSURL(id)
				}
			}
			if let expectedSHA256 = source.expectedSHA256, !DeclarativePackageValidator.isSHA256(expectedSHA256) {
				throw DeclarativePackageConfigurationError.invalidSHA256(id)
			}
			if source.kind == .url, source.expectedSHA256 == nil {
				throw DeclarativePackageConfigurationError.missingSHA256(id)
			}
			var resources = Set<String>()
			for filter in source.resourceFilters {
				let resource = filter.resource.trimmingCharacters(in: .whitespacesAndNewlines)
				guard !resource.isEmpty else {
					throw DeclarativePackageConfigurationError.emptyResourceFilter(id)
				}
				guard resources.insert(resource).inserted else {
					throw DeclarativePackageConfigurationError.duplicateResourceFilter(sourceID: id, resource: resource)
				}
			}
		}
	}
}

public struct DeclarativePackageManifest: Codable, Equatable, Sendable {
	public static let currentSchemaVersion = 1
	public var schemaVersion: Int
	public var identifier: String
	public var name: String
	public var version: String
	public var resources: [String]

	public init(schemaVersion: Int = DeclarativePackageManifest.currentSchemaVersion, identifier: String, name: String, version: String, resources: [String] = []) {
		self.schemaVersion = schemaVersion
		self.identifier = identifier
		self.name = name
		self.version = version
		self.resources = resources
	}

	private enum CodingKeys: String, CodingKey {
		case schemaVersion = "schema_version"
		case identifier
		case name
		case version
		case resources
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
		identifier = try container.decode(String.self, forKey: .identifier)
		name = try container.decode(String.self, forKey: .name)
		version = try container.decode(String.self, forKey: .version)
		resources = try container.decodeIfPresent([String].self, forKey: .resources) ?? []
	}
}

public enum DeclarativePackageValidationError: Error, Equatable, Sendable, CustomStringConvertible {
	case missingPath(String)
	case pathIsNotDirectory(String)
	case pathIsNotRegularFile(String)
	case symlinkRejected(String)
	case nestedAppBundleRejected(String)
	case executableFileRejected(String)
	case missingManifest(String)
	case malformedManifest(String)
	case unsupportedManifestSchemaVersion(Int)
	case invalidManifestField(String)
	case manifestIdentifierMismatch(expected: String, actual: String)
	case invalidArchive(String)
	case archiveTraversal(String)
	case sha256Mismatch(expected: String, actual: String)
	case invalidHTTPSURL(String)

	public var description: String {
		switch self {
		case let .missingPath(path): "Package path does not exist: \(path)"
		case let .pathIsNotDirectory(path): "Package path is not a directory: \(path)"
		case let .pathIsNotRegularFile(path): "Package archive is not a regular file: \(path)"
		case let .symlinkRejected(path): "Symlink rejected: \(path)"
		case let .nestedAppBundleRejected(path): "Nested app bundle rejected: \(path)"
		case let .executableFileRejected(path): "Executable file rejected: \(path)"
		case let .missingManifest(path): "Package manifest is missing: \(path)"
		case let .malformedManifest(message): "Malformed package manifest: \(message)"
		case let .unsupportedManifestSchemaVersion(version): "Unsupported package manifest schema version \(version)."
		case let .invalidManifestField(field): "Invalid package manifest field \(field)."
		case let .manifestIdentifierMismatch(expected, actual): "Package identifier \(actual) does not match expected \(expected)."
		case let .invalidArchive(message): "Invalid package archive: \(message)"
		case let .archiveTraversal(path): "Archive path traversal rejected: \(path)"
		case let .sha256Mismatch(expected, actual): "SHA-256 mismatch; expected \(expected), received \(actual)."
		case let .invalidHTTPSURL(url): "Package URL must use HTTPS: \(url)"
		}
	}
}

public struct DeclarativePackageInspection: Equatable, Sendable {
	public var manifest: DeclarativePackageManifest
	public var sha256: String

	public init(manifest: DeclarativePackageManifest, sha256: String) {
		self.manifest = manifest
		self.sha256 = sha256
	}
}

public enum DeclarativePackageValidator {
	public static let manifestFilename = "itsy-package.json"

	public static func inspectDirectory(_ root: URL, expectedPackageID: String? = nil, fileManager: FileManager = .default) throws -> DeclarativePackageInspection {
		let root = root.standardizedFileURL
		guard fileManager.fileExists(atPath: root.path) else {
			throw DeclarativePackageValidationError.missingPath(root.path)
		}
		let rootValues = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
		guard rootValues.isSymbolicLink != true else {
			throw DeclarativePackageValidationError.symlinkRejected(root.path)
		}
		guard rootValues.isDirectory == true else {
			throw DeclarativePackageValidationError.pathIsNotDirectory(root.path)
		}
		let keys: Set<URLResourceKey> = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .isExecutableKey]
		if let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: Array(keys)) {
			for case let url as URL in enumerator {
				let values = try url.resourceValues(forKeys: keys)
				let relative = relativePath(url, root: root)
				if values.isSymbolicLink == true {
					throw DeclarativePackageValidationError.symlinkRejected(relative)
				}
				if values.isDirectory == true, url.pathExtension.lowercased() == "app" {
					throw DeclarativePackageValidationError.nestedAppBundleRejected(relative)
				}
				if values.isRegularFile == true, values.isExecutable == true {
					throw DeclarativePackageValidationError.executableFileRejected(relative)
				}
			}
		}
		let manifestURL = root.appendingPathComponent(manifestFilename)
		guard fileManager.fileExists(atPath: manifestURL.path) else {
			throw DeclarativePackageValidationError.missingManifest(manifestURL.path)
		}
		let manifest: DeclarativePackageManifest
		do {
			manifest = try JSONDecoder().decode(DeclarativePackageManifest.self, from: Data(contentsOf: manifestURL))
		} catch {
			throw DeclarativePackageValidationError.malformedManifest(String(describing: error))
		}
		try validateManifest(manifest)
		if let expectedPackageID, manifest.identifier != expectedPackageID {
			throw DeclarativePackageValidationError.manifestIdentifierMismatch(expected: expectedPackageID, actual: manifest.identifier)
		}
		return DeclarativePackageInspection(manifest: manifest, sha256: try sha256Directory(root, fileManager: fileManager))
	}

	public static func inspectArchive(_ archiveURL: URL, expectedSHA256: String, fileManager: FileManager = .default) throws -> String {
		let archiveURL = archiveURL.standardizedFileURL
		guard fileManager.fileExists(atPath: archiveURL.path) else {
			throw DeclarativePackageValidationError.missingPath(archiveURL.path)
		}
		let values = try archiveURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
		guard values.isSymbolicLink != true else {
			throw DeclarativePackageValidationError.symlinkRejected(archiveURL.path)
		}
		guard values.isRegularFile == true else {
			throw DeclarativePackageValidationError.pathIsNotRegularFile(archiveURL.path)
		}
		let actualSHA256 = try sha256File(archiveURL)
		guard actualSHA256 == expectedSHA256.lowercased() else {
			throw DeclarativePackageValidationError.sha256Mismatch(expected: expectedSHA256.lowercased(), actual: actualSHA256)
		}
		try inspectZIPPaths(data: try Data(contentsOf: archiveURL))
		return actualSHA256
	}

	public static func validateHTTPSURL(_ value: String) throws -> URL {
		guard let url = URL(string: value), url.scheme?.lowercased() == "https" else {
			throw DeclarativePackageValidationError.invalidHTTPSURL(value)
		}
		return url
	}

	public static func sha256File(_ url: URL) throws -> String {
		let digest = SHA256.hash(data: try Data(contentsOf: url))
		return digest.map { String(format: "%02x", $0) }.joined()
	}

	public static func isSHA256(_ value: String) -> Bool {
		guard value.count == 64 else { return false }
		let hexadecimal = Set("0123456789abcdef")
		return value.lowercased().allSatisfy { hexadecimal.contains($0) }
	}

	private static func validateManifest(_ manifest: DeclarativePackageManifest) throws {
		guard manifest.schemaVersion == DeclarativePackageManifest.currentSchemaVersion else {
			throw DeclarativePackageValidationError.unsupportedManifestSchemaVersion(manifest.schemaVersion)
		}
		for (field, value) in [("identifier", manifest.identifier), ("name", manifest.name), ("version", manifest.version)] {
			guard validValue(value) else {
				throw DeclarativePackageValidationError.invalidManifestField(field)
			}
		}
		var resources = Set<String>()
		for resource in manifest.resources {
			guard validResource(resource), resources.insert(resource).inserted else {
				throw DeclarativePackageValidationError.invalidManifestField("resources")
			}
		}
	}

	private static func validValue(_ value: String) -> Bool {
		let value = value.trimmingCharacters(in: .whitespacesAndNewlines)
		return !value.isEmpty && !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
	}

	private static func validResource(_ value: String) -> Bool {
		validValue(value) && !value.contains("..") && !value.contains("/") && !value.contains("\\")
	}

	private static func sha256Directory(_ root: URL, fileManager: FileManager) throws -> String {
		let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey]
		let urls = ((fileManager.enumerator(at: root, includingPropertiesForKeys: Array(keys))?.allObjects as? [URL]) ?? [])
			.sorted { relativePath($0, root: root) < relativePath($1, root: root) }
		var hasher = SHA256()
		for url in urls {
			let relative = relativePath(url, root: root)
			hasher.update(data: Data(relative.utf8))
			hasher.update(data: Data([0]))
			if (try url.resourceValues(forKeys: keys).isRegularFile) == true {
				hasher.update(data: try Data(contentsOf: url))
			}
			hasher.update(data: Data([0]))
		}
		return hasher.finalize().map { String(format: "%02x", $0) }.joined()
	}

	private static func relativePath(_ url: URL, root: URL) -> String {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
	}

	private static func inspectZIPPaths(data: Data) throws {
		let endSignature: UInt32 = 0x06054b50
		let directorySignature: UInt32 = 0x02014b50
		guard let endOffset = (max(0, data.count - 65_557) ... max(0, data.count - 4)).reversed().first(where: { offset in
			readUInt32(data, at: offset) == endSignature
		}) else {
			throw DeclarativePackageValidationError.invalidArchive("missing ZIP end record")
		}
		guard let entryCount = readUInt16(data, at: endOffset + 10), let directoryOffset = readUInt32(data, at: endOffset + 16) else {
			throw DeclarativePackageValidationError.invalidArchive("truncated ZIP end record")
		}
		var offset = Int(directoryOffset)
		for _ in 0 ..< Int(entryCount) {
			guard readUInt32(data, at: offset) == directorySignature,
				let nameLength = readUInt16(data, at: offset + 28),
				let extraLength = readUInt16(data, at: offset + 30),
				let commentLength = readUInt16(data, at: offset + 32),
				let attributes = readUInt32(data, at: offset + 38) else {
				throw DeclarativePackageValidationError.invalidArchive("truncated ZIP directory entry")
			}
			let nameStart = offset + 46
			let nameEnd = nameStart + Int(nameLength)
			guard nameEnd <= data.count else {
				throw DeclarativePackageValidationError.invalidArchive("truncated ZIP entry name")
			}
			let path = String(decoding: data[nameStart ..< nameEnd], as: UTF8.self)
			guard validArchivePath(path) else {
				throw DeclarativePackageValidationError.archiveTraversal(path)
			}
			let mode = attributes >> 16
			if mode & 0o170000 == 0o120000 {
				throw DeclarativePackageValidationError.symlinkRejected(path)
			}
			if mode & 0o111 != 0 {
				throw DeclarativePackageValidationError.executableFileRejected(path)
			}
			offset = nameEnd + Int(extraLength) + Int(commentLength)
			guard offset <= data.count else {
				throw DeclarativePackageValidationError.invalidArchive("truncated ZIP directory")
			}
		}
	}

	private static func validArchivePath(_ path: String) -> Bool {
		guard !path.isEmpty, !path.hasPrefix("/"), !path.hasPrefix("\\"), !path.contains("\\") else { return false }
		return !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == ".." || $0.isEmpty })
	}

	private static func readUInt16(_ data: Data, at offset: Int) -> UInt16? {
		guard offset >= 0, offset + 2 <= data.count else { return nil }
		return UInt16(data[offset]) | UInt16(data[offset + 1]) << 8
	}

	private static func readUInt32(_ data: Data, at offset: Int) -> UInt32? {
		guard offset >= 0, offset + 4 <= data.count else { return nil }
		return UInt32(data[offset]) | UInt32(data[offset + 1]) << 8 | UInt32(data[offset + 2]) << 16 | UInt32(data[offset + 3]) << 24
	}
}

public enum DeclarativePackageResolver {
	public static func effectiveSources(global: DeclarativePackageConfiguration, project: DeclarativePackageConfiguration) -> [(source: DeclarativePackageSource, scope: DeclarativePackageScope)] {
		var resolved = Dictionary(uniqueKeysWithValues: global.sources.map { ($0.id, ($0, DeclarativePackageScope.global)) })
		for source in project.sources {
			resolved[source.id] = (source, .project)
		}
		return resolved.values
			.sorted { lhs, rhs in lhs.0.id.localizedStandardCompare(rhs.0.id) == .orderedAscending }
			.map { (source: $0.0, scope: $0.1) }
	}

	public static func isResourceEnabled(_ resource: String, for source: DeclarativePackageSource) -> Bool {
		guard source.enabled else { return false }
		return source.resourceFilters.first(where: { $0.resource == resource })?.enabled ?? true
	}
}

public struct DeclarativePackageStatus: Equatable, Sendable, Identifiable {
	public var source: DeclarativePackageSource
	public var scope: DeclarativePackageScope
	public var manifest: DeclarativePackageManifest?
	public var trust: VouchDecision
	public var error: String?

	public var id: String { "\(scope.rawValue):\(source.id)" }
	public var sourceDescription: String { "\(source.kind.rawValue): \(source.location)" }
	public var version: String { manifest?.version ?? "Unknown" }

	public init(source: DeclarativePackageSource, scope: DeclarativePackageScope, manifest: DeclarativePackageManifest? = nil, trust: VouchDecision = .missing, error: String? = nil) {
		self.source = source
		self.scope = scope
		self.manifest = manifest
		self.trust = trust
		self.error = error
	}
}

public enum DeclarativePackageInspector {
	public static func inspect(
		source: DeclarativePackageSource,
		scope: DeclarativePackageScope,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default,
		vouchEvidence: ExtensionInstallRequest.VouchEvidenceProvider? = nil
	) -> DeclarativePackageStatus {
		guard source.kind == .path else {
			return DeclarativePackageStatus(source: source, scope: scope, error: "Select Update to fetch and validate this remote source.")
		}
		do {
			let inspection = try DeclarativePackageValidator.inspectDirectory(URL(fileURLWithPath: source.location), expectedPackageID: source.packageID, fileManager: fileManager)
			let subject = VouchSubject(sha256: inspection.sha256, identifier: inspection.manifest.identifier, version: inspection.manifest.version)
			var records = try VouchStore.load(urls: VouchStore.defaultURLs(repoRoot: repoRoot, workspaceRoot: workspaceRoot, homeDirectory: homeDirectory), fileManager: fileManager).records
			if let evidence = vouchEvidence?(subject) {
				switch evidence {
				case let .allow(record), let .deny(record): records.append(record)
				case .missing: break
				}
			}
			return DeclarativePackageStatus(source: source, scope: scope, manifest: inspection.manifest, trust: VouchStore(records: records).decision(for: subject))
		} catch {
			return DeclarativePackageStatus(source: source, scope: scope, error: String(describing: error))
		}
	}
}

public final class DeclarativePackageStore {
	public let globalURL: URL
	public let projectURL: URL?
	private let fileManager: FileManager

	public init(globalURL: URL = DeclarativePackageStore.defaultGlobalURL(), projectURL: URL? = nil, fileManager: FileManager = .default) {
		self.globalURL = globalURL
		self.projectURL = projectURL
		self.fileManager = fileManager
	}

	public static func defaultGlobalURL(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory.appendingPathComponent(".config/itsy/packages.json")
	}

	public static func defaultProjectURL(workspaceRoot: URL) -> URL {
		workspaceRoot.appendingPathComponent(".itsy/packages.json")
	}

	public func load(scope: DeclarativePackageScope) throws -> DeclarativePackageConfiguration {
		guard let url = url(for: scope), fileManager.fileExists(atPath: url.path) else {
			return DeclarativePackageConfiguration()
		}
		return try DeclarativePackageConfigurationParser.parse(data: Data(contentsOf: url))
	}

	public func save(_ configuration: DeclarativePackageConfiguration, scope: DeclarativePackageScope) throws {
		try DeclarativePackageConfigurationParser.validate(configuration)
		guard let url = url(for: scope) else {
			throw DeclarativePackageValidationError.missingPath("Project package configuration requires an open workspace.")
		}
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		try encoder.encode(configuration).write(to: url, options: .atomic)
	}

	public func setEnabled(_ enabled: Bool, sourceID: String, scope: DeclarativePackageScope) throws {
		var configuration = try load(scope: scope)
		guard let index = configuration.sources.firstIndex(where: { $0.id == sourceID }) else { return }
		configuration.sources[index].enabled = enabled
		try save(configuration, scope: scope)
	}

	public func remove(sourceID: String, scope: DeclarativePackageScope) throws {
		var configuration = try load(scope: scope)
		configuration.sources.removeAll { $0.id == sourceID }
		try save(configuration, scope: scope)
	}

	private func url(for scope: DeclarativePackageScope) -> URL? {
		switch scope {
		case .global: globalURL
		case .project: projectURL
		}
	}
}

public struct DeclarativePackageRegistryEntry: Codable, Equatable, Sendable {
	public var identifier: String
	public var name: String
	public var version: String
	public var archiveURL: URL
	public var sha256: String

	public init(identifier: String, name: String, version: String, archiveURL: URL, sha256: String) {
		self.identifier = identifier
		self.name = name
		self.version = version
		self.archiveURL = archiveURL
		self.sha256 = sha256.lowercased()
	}

	private enum CodingKeys: String, CodingKey {
		case identifier, name, version
		case archiveURL = "archive_url"
		case sha256
	}
}

public struct DeclarativePackageRegistry: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var packages: [DeclarativePackageRegistryEntry]

	public init(schemaVersion: Int = 1, packages: [DeclarativePackageRegistryEntry] = []) {
		self.schemaVersion = schemaVersion
		self.packages = packages
	}

	private enum CodingKeys: String, CodingKey {
		case schemaVersion = "schema_version"
		case packages
	}
}

public enum DeclarativePackageRemoteError: Error, Equatable, Sendable, CustomStringConvertible {
	case downloadStatus(Int)
	case invalidRegistry(String)
	case packageMissingFromRegistry(String)
	case extractionFailed(String)
	case trustDenied(VouchRecord)
	case trustMissing(VouchSubject)

	public var description: String {
		switch self {
		case let .downloadStatus(status): "Package download failed with HTTP status \(status)."
		case let .invalidRegistry(message): "Invalid package registry: \(message)"
		case let .packageMissingFromRegistry(id): "Package \(id) is not in the configured registry."
		case let .extractionFailed(message): "Package extraction failed: \(message)"
		case let .trustDenied(record): "Package trust denied by \(record.source?.path ?? record.signer ?? "unknown source")."
		case let .trustMissing(subject): "Package trust is required for \(subject.identifier) \(subject.version)."
		}
	}
}

public struct DeclarativePackageInstallReceipt: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var identifier: String
	public var version: String
	public var sha256: String
	public var source: String
	public var scope: DeclarativePackageScope

	public init(identifier: String, version: String, sha256: String, source: String, scope: DeclarativePackageScope) {
		schemaVersion = 1
		self.identifier = identifier
		self.version = version
		self.sha256 = sha256
		self.source = source
		self.scope = scope
	}
}

public enum DeclarativePackageManager {
	public static func defaultInstallRoot(scope: DeclarativePackageScope, workspaceRoot: URL? = nil, homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL? {
		switch scope {
		case .global: homeDirectory.appendingPathComponent(".config/itsy/packages", isDirectory: true)
		case .project: workspaceRoot?.appendingPathComponent(".itsy/packages", isDirectory: true)
		}
	}

	public static func install(
		source: DeclarativePackageSource,
		scope: DeclarativePackageScope,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default,
		session: URLSession = .shared,
		vouchEvidence: ExtensionInstallRequest.VouchEvidenceProvider? = VouchCLI.decision
	) async throws -> DeclarativePackageInstallReceipt {
		let temporary = fileManager.temporaryDirectory.appendingPathComponent("itsy-package-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: temporary, withIntermediateDirectories: true)
		defer { try? fileManager.removeItem(at: temporary) }
		let root: URL
		let archiveHash: String?
		switch source.kind {
		case .path:
			let local = URL(fileURLWithPath: source.location)
			if local.pathExtension.lowercased() == "zip" {
				guard let expected = source.expectedSHA256 else { throw DeclarativePackageConfigurationError.missingSHA256(source.id) }
				archiveHash = try DeclarativePackageValidator.inspectArchive(local, expectedSHA256: expected, fileManager: fileManager)
				root = try extract(archive: local, into: temporary, fileManager: fileManager)
			} else {
				archiveHash = nil
				root = local
			}
		case .url:
			guard let expected = source.expectedSHA256 else { throw DeclarativePackageConfigurationError.missingSHA256(source.id) }
			let archive = try await download(url: try DeclarativePackageValidator.validateHTTPSURL(source.location), into: temporary, session: session)
			archiveHash = try DeclarativePackageValidator.inspectArchive(archive, expectedSHA256: expected, fileManager: fileManager)
			root = try extract(archive: archive, into: temporary, fileManager: fileManager)
		case .registry:
			let registry = try await fetchRegistry(url: try DeclarativePackageValidator.validateHTTPSURL(source.location), session: session)
			guard let entry = registry.packages.first(where: { $0.identifier == source.packageID }) else {
				throw DeclarativePackageRemoteError.packageMissingFromRegistry(source.packageID)
			}
			let archive = try await download(url: entry.archiveURL, into: temporary, session: session)
			archiveHash = try DeclarativePackageValidator.inspectArchive(archive, expectedSHA256: entry.sha256, fileManager: fileManager)
			root = try extract(archive: archive, into: temporary, fileManager: fileManager)
		}
		let inspection = try DeclarativePackageValidator.inspectDirectory(root, expectedPackageID: source.packageID, fileManager: fileManager)
		let hash = archiveHash ?? inspection.sha256
		let subject = VouchSubject(sha256: hash, identifier: inspection.manifest.identifier, version: inspection.manifest.version)
		try requireTrust(subject: subject, repoRoot: repoRoot, workspaceRoot: workspaceRoot, homeDirectory: homeDirectory, fileManager: fileManager, vouchEvidence: vouchEvidence)
		guard let installRoot = defaultInstallRoot(scope: scope, workspaceRoot: workspaceRoot, homeDirectory: homeDirectory) else {
			throw DeclarativePackageValidationError.missingPath("Project package installation requires an open workspace.")
		}
		let destination = installRoot.appendingPathComponent(inspection.manifest.identifier, isDirectory: true).appendingPathComponent(inspection.manifest.version, isDirectory: true)
		let parent = destination.deletingLastPathComponent()
		try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
		let staged = parent.appendingPathComponent(".\(inspection.manifest.version).staged-\(UUID().uuidString)", isDirectory: true)
		try fileManager.copyItem(at: root, to: staged)
		let backup = parent.appendingPathComponent(".\(inspection.manifest.version).backup-\(UUID().uuidString)", isDirectory: true)
		if fileManager.fileExists(atPath: destination.path) {
			try fileManager.moveItem(at: destination, to: backup)
		}
		do {
			try fileManager.moveItem(at: staged, to: destination)
			try? fileManager.removeItem(at: backup)
		} catch {
			if fileManager.fileExists(atPath: backup.path), !fileManager.fileExists(atPath: destination.path) {
				try? fileManager.moveItem(at: backup, to: destination)
			}
			throw error
		}
		let receipt = DeclarativePackageInstallReceipt(identifier: inspection.manifest.identifier, version: inspection.manifest.version, sha256: hash, source: source.location, scope: scope)
		try JSONEncoder().encode(receipt).write(to: destination.appendingPathComponent(".itsy-package-receipt.json"), options: .atomic)
		return receipt
	}

	private static func download(url: URL, into directory: URL, session: URLSession) async throws -> URL {
		guard url.scheme?.lowercased() == "https" else { throw DeclarativePackageValidationError.invalidHTTPSURL(url.absoluteString) }
		let (temporary, response) = try await session.download(from: url)
		guard let response = response as? HTTPURLResponse, (200 ... 299).contains(response.statusCode) else {
			throw DeclarativePackageRemoteError.downloadStatus((response as? HTTPURLResponse)?.statusCode ?? -1)
		}
		let destination = directory.appendingPathComponent("download.zip")
		try FileManager.default.moveItem(at: temporary, to: destination)
		return destination
	}

	private static func fetchRegistry(url: URL, session: URLSession) async throws -> DeclarativePackageRegistry {
		guard url.scheme?.lowercased() == "https" else { throw DeclarativePackageValidationError.invalidHTTPSURL(url.absoluteString) }
		let (data, response) = try await session.data(from: url)
		guard let response = response as? HTTPURLResponse, (200 ... 299).contains(response.statusCode) else {
			throw DeclarativePackageRemoteError.downloadStatus((response as? HTTPURLResponse)?.statusCode ?? -1)
		}
		do {
			let registry = try JSONDecoder().decode(DeclarativePackageRegistry.self, from: data)
			guard registry.schemaVersion == 1, registry.packages.allSatisfy({ DeclarativePackageValidator.isSHA256($0.sha256) && $0.archiveURL.scheme?.lowercased() == "https" }) else {
				throw DeclarativePackageRemoteError.invalidRegistry("unsupported schema or invalid package URL/hash")
			}
			return registry
		} catch let error as DeclarativePackageRemoteError {
			throw error
		} catch {
			throw DeclarativePackageRemoteError.invalidRegistry(String(describing: error))
		}
	}

	private static func extract(archive: URL, into temporary: URL, fileManager: FileManager) throws -> URL {
		let destination = temporary.appendingPathComponent("extracted", isDirectory: true)
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
		process.arguments = ["-x", "-k", archive.path, destination.path]
		process.standardOutput = Pipe()
		process.standardError = Pipe()
		do {
			try process.run()
			process.waitUntilExit()
		} catch {
			throw DeclarativePackageRemoteError.extractionFailed(String(describing: error))
		}
		guard process.terminationStatus == 0, fileManager.fileExists(atPath: destination.path) else {
			throw DeclarativePackageRemoteError.extractionFailed("ditto exited \(process.terminationStatus)")
		}
		let children = (try? fileManager.contentsOfDirectory(at: destination, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? []
		if children.count == 1, (try? children[0].resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
			return children[0]
		}
		return destination
	}

	private static func requireTrust(subject: VouchSubject, repoRoot: URL, workspaceRoot: URL, homeDirectory: URL, fileManager: FileManager, vouchEvidence: ExtensionInstallRequest.VouchEvidenceProvider?) throws {
		var records = try VouchStore.load(urls: VouchStore.defaultURLs(repoRoot: repoRoot, workspaceRoot: workspaceRoot, homeDirectory: homeDirectory), fileManager: fileManager).records
		if let evidence = vouchEvidence?(subject) {
			switch evidence {
			case let .allow(record), let .deny(record): records.append(record)
			case .missing: break
			}
		}
		switch VouchStore(records: records).decision(for: subject) {
		case .allow: return
		case let .deny(record): throw DeclarativePackageRemoteError.trustDenied(record)
		case .missing: throw DeclarativePackageRemoteError.trustMissing(subject)
		}
	}
}
