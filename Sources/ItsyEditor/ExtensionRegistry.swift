import CryptoKit
import Foundation

public struct ExtensionRegisteredContributions: Equatable, Sendable {
	public var manifest: ExtensionManifest
	public var root: URL
	public var themes: [ExtensionRegisteredTheme]
	public var snippets: [ExtensionRegisteredSnippet]
	public var languages: [ExtensionRegisteredLanguage]
	public var problemMatchers: [ExtensionRegisteredProblemMatcher]

	public init(
		manifest: ExtensionManifest,
		root: URL,
		themes: [ExtensionRegisteredTheme] = [],
		snippets: [ExtensionRegisteredSnippet] = [],
		languages: [ExtensionRegisteredLanguage] = [],
		problemMatchers: [ExtensionRegisteredProblemMatcher] = []
	) {
		self.manifest = manifest
		self.root = root
		self.themes = themes
		self.snippets = snippets
		self.languages = languages
		self.problemMatchers = problemMatchers
	}
}

public struct ExtensionRegisteredTheme: Equatable, Sendable {
	public var id: String
	public var label: String
	public var url: URL

	public init(id: String, label: String, url: URL) {
		self.id = id
		self.label = label
		self.url = url
	}
}

public struct ExtensionRegisteredSnippet: Equatable, Sendable {
	public var language: String
	public var url: URL

	public init(language: String, url: URL) {
		self.language = language
		self.url = url
	}
}

public struct ExtensionRegisteredLanguage: Equatable, Sendable {
	public var id: String
	public var aliases: [String]
	public var extensions: [String]

	public init(id: String, aliases: [String], extensions: [String]) {
		self.id = id
		self.aliases = aliases
		self.extensions = extensions
	}
}

public struct ExtensionRegisteredProblemMatcher: Equatable, Sendable {
	public var id: String
	public var label: String
	public var pattern: String
	public var fileLocation: String?
	public var fileGroup: Int
	public var lineGroup: Int
	public var columnGroup: Int?
	public var severityGroup: Int?
	public var messageGroup: Int
	public var defaultSeverity: WorkspaceProblemSeverity
	public var source: String?

	public init(
		id: String,
		label: String,
		pattern: String,
		fileLocation: String? = nil,
		fileGroup: Int = 1,
		lineGroup: Int = 2,
		columnGroup: Int? = 3,
		severityGroup: Int? = nil,
		messageGroup: Int = 4,
		defaultSeverity: WorkspaceProblemSeverity = .error,
		source: String? = nil
	) {
		self.id = id
		self.label = label
		self.pattern = pattern
		self.fileLocation = fileLocation
		self.fileGroup = fileGroup
		self.lineGroup = lineGroup
		self.columnGroup = columnGroup
		self.severityGroup = severityGroup
		self.messageGroup = messageGroup
		self.defaultSeverity = defaultSeverity
		self.source = source
	}
}

public enum ExtensionRegistryError: Error, Equatable, Sendable {
	case absolutePath(String)
	case parentDirectoryPath(String)
	case pathEscapesExtensionRoot(String)
	case missingContributionFile(String)
	case invalidProblemMatcherPattern(String)
}

public enum ExtensionContributionRegistry {
	public static func register(manifest: ExtensionManifest, root: URL, fileManager: FileManager = .default) throws -> ExtensionRegisteredContributions {
		let languageIDs = Set(manifest.contributes.languages.map(\.id))
		let languages = manifest.contributes.languages.map { language in
			ExtensionRegisteredLanguage(
				id: scopedID(manifest: manifest, localID: language.id),
				aliases: language.aliases,
				extensions: language.extensions
			)
		}
		let themes = try manifest.contributes.themes.map { theme in
			ExtensionRegisteredTheme(
				id: scopedID(manifest: manifest, localID: theme.id),
				label: theme.label,
				url: try resolveContributionFile(path: theme.path, root: root, fileManager: fileManager)
			)
		}
		let snippets = try manifest.contributes.snippets.map { snippet in
			ExtensionRegisteredSnippet(
				language: languageIDs.contains(snippet.language) ? scopedID(manifest: manifest, localID: snippet.language) : snippet.language,
				url: try resolveContributionFile(path: snippet.path, root: root, fileManager: fileManager)
			)
		}
		let matchers = try manifest.contributes.problemMatchers.map { matcher in
			do {
				_ = try NSRegularExpression(pattern: matcher.pattern)
			} catch {
				throw ExtensionRegistryError.invalidProblemMatcherPattern(matcher.pattern)
			}
			return ExtensionRegisteredProblemMatcher(
				id: scopedID(manifest: manifest, localID: matcher.id),
				label: matcher.label,
				pattern: matcher.pattern,
				fileLocation: matcher.fileLocation,
				fileGroup: matcher.fileGroup,
				lineGroup: matcher.lineGroup,
				columnGroup: matcher.columnGroup,
				severityGroup: matcher.severityGroup,
				messageGroup: matcher.messageGroup,
				defaultSeverity: matcher.defaultSeverity,
				source: matcher.source
			)
		}
		return ExtensionRegisteredContributions(
			manifest: manifest,
			root: root,
			themes: themes,
			snippets: snippets,
			languages: languages,
			problemMatchers: matchers
		)
	}

	public static func scopedID(manifest: ExtensionManifest, localID: String) -> String {
		"extension:\(manifest.identifier):\(localID)"
	}

	static func resolveContributionFile(path rawPath: String, root: URL, fileManager: FileManager = .default) throws -> URL {
		let path = rawPath.trimmingCharacters(in: .whitespacesAndNewlines)
		if path.hasPrefix("/") {
			throw ExtensionRegistryError.absolutePath(rawPath)
		}
		if path.split(separator: "/").contains("..") {
			throw ExtensionRegistryError.parentDirectoryPath(rawPath)
		}
		let rootURL = root.standardizedFileURL
		let url = rootURL.appendingPathComponent(path).standardizedFileURL
		guard isDescendant(url, of: rootURL) else {
			throw ExtensionRegistryError.pathEscapesExtensionRoot(rawPath)
		}
		guard fileManager.fileExists(atPath: url.path) else {
			throw ExtensionRegistryError.missingContributionFile(rawPath)
		}
		return url
	}

	private static func isDescendant(_ url: URL, of root: URL) -> Bool {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		return path == rootPath || path.hasPrefix(rootPath + "/")
	}
}

public struct ExtensionInstallRequest {
	public typealias VouchEvidenceProvider = (VouchSubject) -> VouchDecision?

	public var archiveURL: URL
	public var extractedRoot: URL
	public var expectedSHA256: String
	public var installRoot: URL
	public var repoRoot: URL
	public var workspaceRoot: URL
	public var homeDirectory: URL
	public var vouchEvidence: VouchEvidenceProvider?

	public init(
		archiveURL: URL,
		extractedRoot: URL,
		expectedSHA256: String,
		installRoot: URL,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		vouchEvidence: VouchEvidenceProvider? = VouchCLI.decision
	) {
		self.archiveURL = archiveURL
		self.extractedRoot = extractedRoot
		self.expectedSHA256 = expectedSHA256.lowercased()
		self.installRoot = installRoot
		self.repoRoot = repoRoot
		self.workspaceRoot = workspaceRoot
		self.homeDirectory = homeDirectory
		self.vouchEvidence = vouchEvidence
	}
}

public struct ExtensionInstallResult: Equatable, Sendable {
	public var manifest: ExtensionManifest
	public var installedURL: URL
	public var contributions: ExtensionRegisteredContributions
	public var trust: VouchDecision
}

public enum ExtensionInstallError: Error, Equatable, Sendable {
	case invalidExpectedSHA256(String)
	case sha256Mismatch(expected: String, actual: String)
	case missingManifest(URL)
	case trustDenied(VouchRecord)
	case trustMissing(VouchSubject)
	case installedVersionExists(URL)
	case symlinkRejected(String)
	case nestedAppBundleRejected(String)
	case executableFileRejected(String)
}

public enum ExtensionInstaller {
	public static func install(_ request: ExtensionInstallRequest, fileManager: FileManager = .default) throws -> ExtensionInstallResult {
		guard isSHA256(request.expectedSHA256) else {
			throw ExtensionInstallError.invalidExpectedSHA256(request.expectedSHA256)
		}
		let actualSHA = try sha256(url: request.archiveURL)
		guard actualSHA == request.expectedSHA256 else {
			throw ExtensionInstallError.sha256Mismatch(expected: request.expectedSHA256, actual: actualSHA)
		}
		let manifestURL = request.extractedRoot.appendingPathComponent("extension.json")
		guard fileManager.fileExists(atPath: manifestURL.path) else {
			throw ExtensionInstallError.missingManifest(manifestURL)
		}
		let manifest = try ExtensionManifestLoader.load(url: manifestURL)
		let subject = VouchSubject(sha256: actualSHA, identifier: manifest.identifier, version: manifest.version)
		let trust = try trustDecision(for: subject, request: request, fileManager: fileManager)
		try validateInstallTree(root: request.extractedRoot, fileManager: fileManager)
		let destination = request.installRoot
			.appendingPathComponent(manifest.identifier, isDirectory: true)
			.appendingPathComponent(manifest.version, isDirectory: true)
		guard !fileManager.fileExists(atPath: destination.path) else {
			throw ExtensionInstallError.installedVersionExists(destination)
		}
		let parent = destination.deletingLastPathComponent()
		try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
		let temp = parent.appendingPathComponent(".\(manifest.version).tmp-\(UUID().uuidString)", isDirectory: true)
		if fileManager.fileExists(atPath: temp.path) {
			try fileManager.removeItem(at: temp)
		}
		do {
			try fileManager.copyItem(at: request.extractedRoot, to: temp)
			try fileManager.moveItem(at: temp, to: destination)
		} catch {
			try? fileManager.removeItem(at: temp)
			throw error
		}
		let contributions = try ExtensionContributionRegistry.register(manifest: manifest, root: destination, fileManager: fileManager)
		return ExtensionInstallResult(manifest: manifest, installedURL: destination, contributions: contributions, trust: trust)
	}

	public static func sha256(url: URL) throws -> String {
		let digest = SHA256.hash(data: try Data(contentsOf: url))
		return digest.map { String(format: "%02x", $0) }.joined()
	}

	private static func trustDecision(for subject: VouchSubject, request: ExtensionInstallRequest, fileManager: FileManager) throws -> VouchDecision {
		var records = try VouchStore.load(
			urls: VouchStore.defaultURLs(repoRoot: request.repoRoot, workspaceRoot: request.workspaceRoot, homeDirectory: request.homeDirectory),
			fileManager: fileManager
		).records
		if let evidence = request.vouchEvidence?(subject) {
			switch evidence {
			case let .allow(record), let .deny(record):
				records.append(record)
			case .missing:
				break
			}
		}
		let decision = VouchStore(records: records).decision(for: subject)
		switch decision {
		case let .deny(record):
			throw ExtensionInstallError.trustDenied(record)
		case .missing:
			throw ExtensionInstallError.trustMissing(subject)
		case .allow:
			return decision
		}
	}

	private static func validateInstallTree(root: URL, fileManager: FileManager) throws {
		let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .isExecutableKey]
		guard let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else {
			return
		}
		for case let url as URL in enumerator {
			let values = try url.resourceValues(forKeys: Set(keys))
			let relative = relativePath(url, root: root)
			if values.isSymbolicLink == true {
				throw ExtensionInstallError.symlinkRejected(relative)
			}
			if values.isDirectory == true, url.pathExtension == "app" {
				throw ExtensionInstallError.nestedAppBundleRejected(relative)
			}
			if values.isRegularFile == true, values.isExecutable == true {
				throw ExtensionInstallError.executableFileRejected(relative)
			}
		}
	}

	private static func relativePath(_ url: URL, root: URL) -> String {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
	}

	private static func isSHA256(_ value: String) -> Bool {
		guard value.count == 64 else {
			return false
		}
		let hex = Set("0123456789abcdef")
		return value.allSatisfy { hex.contains($0) }
	}
}

public enum VouchCLI {
	public static func decision(for subject: VouchSubject) -> VouchDecision? {
		guard let executable = findExecutable("vouch") else {
			return nil
		}
		let process = Process()
		process.executableURL = executable
		process.arguments = [
			"verify",
			"--sha256", subject.sha256,
			"--id", subject.identifier,
			"--version", subject.version,
		]
		process.standardOutput = Pipe()
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
		return .allow(VouchRecord(
			directive: .allow,
			sha256: subject.sha256,
			identifier: subject.identifier,
			version: subject.version,
			signer: "vouch-cli"
		))
	}

	private static func findExecutable(_ name: String) -> URL? {
		let paths = (ProcessInfo.processInfo.environment["PATH"] ?? "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")
			.split(separator: ":")
			.map(String.init)
		for path in paths {
			let url = URL(fileURLWithPath: path).appendingPathComponent(name)
			if FileManager.default.isExecutableFile(atPath: url.path) {
				return url
			}
		}
		return nil
	}
}

public struct ExtensionMarketplaceEntry: Codable, Equatable, Sendable {
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
}

public struct ExtensionMarketplaceCache: Codable, Equatable, Sendable {
	public var entries: [ExtensionMarketplaceEntry]

	public init(entries: [ExtensionMarketplaceEntry] = []) {
		self.entries = entries
	}

	public func entry(identifier: String, version: String? = nil) -> ExtensionMarketplaceEntry? {
		entries.first { entry in
			entry.identifier == identifier && (version == nil || entry.version == version)
		}
	}

	public static func load(url: URL, decoder: JSONDecoder = JSONDecoder()) throws -> ExtensionMarketplaceCache {
		try decoder.decode(ExtensionMarketplaceCache.self, from: try Data(contentsOf: url))
	}

	public func save(url: URL, encoder: JSONEncoder = JSONEncoder()) throws {
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try encoder.encode(self).write(to: url, options: .atomic)
	}
}

public enum ExtensionMarketplaceClient {
	public static func fetchIndex(url: URL, decoder: JSONDecoder = JSONDecoder()) throws -> ExtensionMarketplaceCache {
		try decoder.decode(ExtensionMarketplaceCache.self, from: try Data(contentsOf: url))
	}
}
