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

	public init(tasks: [ExtensionTaskContribution] = []) {
		self.tasks = tasks
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

public enum ExtensionManifestError: Error, Equatable, Sendable {
	case unsupportedSchemaVersion(Int)
	case emptyIdentifier
	case emptyContributionID
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
		guard manifest.schemaVersion == 1 else {
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
