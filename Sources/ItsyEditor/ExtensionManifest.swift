import Foundation

public struct ExtensionManifest: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var identifier: String
	public var name: String
	public var version: String
	public var contributes: ExtensionContributions

	public init(schemaVersion: Int = 1, identifier: String, name: String, version: String, contributes: ExtensionContributions = ExtensionContributions()) {
		self.schemaVersion = schemaVersion
		self.identifier = identifier
		self.name = name
		self.version = version
		self.contributes = contributes
	}
}

public struct ExtensionContributions: Codable, Equatable, Sendable {
	public var tasks: [ExtensionTaskContribution]
	public var commands: [ExtensionCommandContribution]
	public var keybindings: [ExtensionKeybindingContribution]
	public var themes: [ExtensionThemeContribution]
	public var snippets: [ExtensionSnippetContribution]
	public var languages: [ExtensionLanguageContribution]
	public var problemMatchers: [ExtensionProblemMatcherContribution]

	public init(
		tasks: [ExtensionTaskContribution] = [],
		commands: [ExtensionCommandContribution] = [],
		keybindings: [ExtensionKeybindingContribution] = [],
		themes: [ExtensionThemeContribution] = [],
		snippets: [ExtensionSnippetContribution] = [],
		languages: [ExtensionLanguageContribution] = [],
		problemMatchers: [ExtensionProblemMatcherContribution] = []
	) {
		self.tasks = tasks
		self.commands = commands
		self.keybindings = keybindings
		self.themes = themes
		self.snippets = snippets
		self.languages = languages
		self.problemMatchers = problemMatchers
	}

	private enum CodingKeys: String, CodingKey {
		case tasks
		case commands
		case keybindings
		case themes
		case snippets
		case languages
		case problemMatchers
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		tasks = try container.decodeIfPresent([ExtensionTaskContribution].self, forKey: .tasks) ?? []
		commands = try container.decodeIfPresent([ExtensionCommandContribution].self, forKey: .commands) ?? []
		keybindings = try container.decodeIfPresent([ExtensionKeybindingContribution].self, forKey: .keybindings) ?? []
		themes = try container.decodeIfPresent([ExtensionThemeContribution].self, forKey: .themes) ?? []
		snippets = try container.decodeIfPresent([ExtensionSnippetContribution].self, forKey: .snippets) ?? []
		languages = try container.decodeIfPresent([ExtensionLanguageContribution].self, forKey: .languages) ?? []
		problemMatchers = try container.decodeIfPresent([ExtensionProblemMatcherContribution].self, forKey: .problemMatchers) ?? []
	}
}

public struct ExtensionTaskContribution: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var command: String
	public var arguments: [String]

	public init(id: String, label: String, command: String, arguments: [String] = []) {
		self.id = id
		self.label = label
		self.command = command
		self.arguments = arguments
	}
}

public struct ExtensionCommandContribution: Codable, Equatable, Sendable {
	public var id: String
	public var title: String
	public var category: String?

	public init(id: String, title: String, category: String? = nil) {
		self.id = id
		self.title = title
		self.category = category
	}
}

public struct ExtensionKeybindingContribution: Codable, Equatable, Sendable {
	public var command: String
	public var key: String
	public var when: String?

	public init(command: String, key: String, when: String? = nil) {
		self.command = command
		self.key = key
		self.when = when
	}
}

public struct ExtensionThemeContribution: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var path: String

	public init(id: String, label: String, path: String) {
		self.id = id
		self.label = label
		self.path = path
	}
}

public struct ExtensionSnippetContribution: Codable, Equatable, Sendable {
	public var language: String
	public var path: String

	public init(language: String, path: String) {
		self.language = language
		self.path = path
	}
}

public struct ExtensionLanguageContribution: Codable, Equatable, Sendable {
	public var id: String
	public var aliases: [String]
	public var extensions: [String]

	public init(id: String, aliases: [String] = [], extensions: [String] = []) {
		self.id = id
		self.aliases = aliases
		self.extensions = extensions
	}
}

public struct ExtensionProblemMatcherContribution: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var pattern: String
	public var fileLocation: String?

	public init(id: String, label: String, pattern: String, fileLocation: String? = nil) {
		self.id = id
		self.label = label
		self.pattern = pattern
		self.fileLocation = fileLocation
	}
}

public enum ExtensionManifestError: Error, Equatable, Sendable {
	case unsupportedSchemaVersion(Int)
	case emptyIdentifier
	case emptyContributionID
	case emptyContributionTitle
	case emptyContributionCommand
	case emptyContributionKey
	case emptyContributionLanguage
	case emptyContributionPath
	case emptyContributionPattern
}

public enum ExtensionManifestLoader {
	public static func load(url: URL, decoder: JSONDecoder = JSONDecoder()) throws -> ExtensionManifest {
		let manifest = try decoder.decode(ExtensionManifest.self, from: try Data(contentsOf: url))
		try validate(manifest)
		return manifest
	}

	public static func discover(root: URL, fileManager: FileManager = .default) -> [ExtensionManifest] {
		let directory = root.appendingPathComponent(".itsy/extensions", isDirectory: true)
		guard let urls = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
			return []
		}
		return urls
			.filter { $0.pathExtension == "json" }
			.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
			.compactMap { try? load(url: $0) }
	}

	private static func validate(_ manifest: ExtensionManifest) throws {
		guard manifest.schemaVersion == 1 || manifest.schemaVersion == 2 else {
			throw ExtensionManifestError.unsupportedSchemaVersion(manifest.schemaVersion)
		}
		guard !manifest.identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw ExtensionManifestError.emptyIdentifier
		}
		for task in manifest.contributes.tasks {
			guard !task.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
				throw ExtensionManifestError.emptyContributionID
			}
		}
		for command in manifest.contributes.commands {
			try requireNonEmpty(command.id, error: .emptyContributionID)
			try requireNonEmpty(command.title, error: .emptyContributionTitle)
		}
		for keybinding in manifest.contributes.keybindings {
			try requireNonEmpty(keybinding.command, error: .emptyContributionCommand)
			try requireNonEmpty(keybinding.key, error: .emptyContributionKey)
		}
		for theme in manifest.contributes.themes {
			try requireNonEmpty(theme.id, error: .emptyContributionID)
			try requireNonEmpty(theme.path, error: .emptyContributionPath)
		}
		for snippet in manifest.contributes.snippets {
			try requireNonEmpty(snippet.language, error: .emptyContributionLanguage)
			try requireNonEmpty(snippet.path, error: .emptyContributionPath)
		}
		for language in manifest.contributes.languages {
			try requireNonEmpty(language.id, error: .emptyContributionLanguage)
		}
		for matcher in manifest.contributes.problemMatchers {
			try requireNonEmpty(matcher.id, error: .emptyContributionID)
			try requireNonEmpty(matcher.pattern, error: .emptyContributionPattern)
		}
	}

	private static func requireNonEmpty(_ value: String, error: ExtensionManifestError) throws {
		guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw error
		}
	}
}

public enum ExtensionTaskMapper {
	public static func tasks(from manifest: ExtensionManifest, root: URL) -> [WorkspaceTask] {
		manifest.contributes.tasks.map { task in
			WorkspaceTask(
				id: "extension:\(manifest.identifier):\(task.id)",
				label: task.label,
				source: .extensionManifest,
				command: task.command,
				arguments: task.arguments,
				workingDirectory: root
			)
		}
	}
}
