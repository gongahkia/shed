import Foundation

public struct SnippetDefinition: Equatable, Sendable {
	public var name: String
	public var prefixes: [String]
	public var body: String
	public var description: String?
	public var scope: [String]

	public init(name: String, prefixes: [String], body: String, description: String? = nil, scope: [String] = []) {
		self.name = name
		self.prefixes = prefixes
		self.body = body
		self.description = description
		self.scope = scope
	}
}

public enum SnippetRegistry {
	public static func discover(
		languageID: String,
		workspaceRoot: URL?,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default
	) -> [SnippetDefinition] {
		let globalURL = homeDirectory
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("snippets", isDirectory: true)
			.appendingPathComponent("\(languageID).json")
		var urls = [globalURL]
		if let workspaceRoot {
			urls.append(workspaceRoot.appendingPathComponent(".itsy/snippets/\(languageID).json"))
		}
		urls.append(contentsOf: installedExtensionSnippetURLs(languageID: languageID, homeDirectory: homeDirectory, fileManager: fileManager))
		if let workspaceRoot {
			urls.append(contentsOf: workspaceExtensionSnippetURLs(languageID: languageID, workspaceRoot: workspaceRoot, fileManager: fileManager))
		}
		return urls.flatMap { url in
			(try? load(url: url, languageID: languageID, fileManager: fileManager)) ?? []
		}
	}

	public static func load(url: URL, languageID: String, fileManager: FileManager = .default) throws -> [SnippetDefinition] {
		guard fileManager.fileExists(atPath: url.path) else {
			return []
		}
		let data = try Data(contentsOf: url)
		let file = try JSONDecoder().decode(VSCodeSnippetFile.self, from: data)
		return file.snippets.compactMap { name, snippet in
			let prefixes = snippet.prefix.values.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
			guard !prefixes.isEmpty else {
				return nil
			}
			let scope = snippet.scope?.values.flatMap(splitScope(_:)) ?? []
			guard scope.isEmpty || scope.contains(languageID) else {
				return nil
			}
			let description = snippet.description?.trimmingCharacters(in: .whitespacesAndNewlines)
			return SnippetDefinition(
				name: name,
				prefixes: prefixes,
				body: snippet.body.text,
				description: description?.nilIfEmpty,
				scope: scope
			)
		}
	}

	private static func workspaceExtensionSnippetURLs(languageID: String, workspaceRoot: URL, fileManager: FileManager) -> [URL] {
		let directory = workspaceRoot.appendingPathComponent(".itsy/extensions", isDirectory: true)
		guard let urls = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
			return []
		}
		return urls
			.filter { $0.pathExtension == "json" }
			.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
			.flatMap { manifestURL in
				manifestSnippetURLs(languageID: languageID, manifestURL: manifestURL, root: manifestURL.deletingLastPathComponent(), fileManager: fileManager)
			}
	}

	private static func installedExtensionSnippetURLs(languageID: String, homeDirectory: URL, fileManager: FileManager) -> [URL] {
		let directory = homeDirectory
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("extensions", isDirectory: true)
		guard let identifiers = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
			return []
		}
		return identifiers
			.filter { isDirectory($0, fileManager: fileManager) }
			.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
			.flatMap { identifierURL -> [URL] in
				let versions = ((try? fileManager.contentsOfDirectory(at: identifierURL, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? [])
					.filter { isDirectory($0, fileManager: fileManager) }
					.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
				return versions.flatMap { versionURL in
					manifestSnippetURLs(
						languageID: languageID,
						manifestURL: versionURL.appendingPathComponent("extension.json"),
						root: versionURL,
						fileManager: fileManager
					)
				}
			}
	}

	private static func manifestSnippetURLs(languageID: String, manifestURL: URL, root: URL, fileManager: FileManager) -> [URL] {
		guard let manifest = try? ExtensionManifestLoader.load(url: manifestURL) else {
			return []
		}
		let localLanguageIDs = Set(manifest.contributes.languages.map(\.id))
		return manifest.contributes.snippets.compactMap { snippet in
			let snippetLanguage = localLanguageIDs.contains(snippet.language)
				? ExtensionContributionRegistry.scopedID(manifest: manifest, localID: snippet.language)
				: snippet.language
			guard snippetLanguage == languageID else {
				return nil
			}
			return try? ExtensionContributionRegistry.resolveContributionFile(path: snippet.path, root: root, fileManager: fileManager)
		}
	}

	private static func isDirectory(_ url: URL, fileManager: FileManager) -> Bool {
		var isDirectory: ObjCBool = false
		return fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
	}

	private static func splitScope(_ value: String) -> [String] {
		value.split(separator: ",")
			.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
			.filter { !$0.isEmpty }
	}
}

private struct VSCodeSnippetFile: Decodable {
	var snippets: [String: VSCodeSnippet]

	init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		snippets = try container.decode([String: VSCodeSnippet].self)
	}
}

private struct VSCodeSnippet: Decodable {
	var prefix: StringList
	var body: SnippetBody
	var description: String?
	var scope: StringList?
}

private struct StringList: Decodable {
	var values: [String]

	init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(String.self) {
			values = [value]
		} else {
			values = try container.decode([String].self)
		}
	}
}

private struct SnippetBody: Decodable {
	var text: String

	init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(String.self) {
			text = value
		} else {
			text = try container.decode([String].self).joined(separator: "\n")
		}
	}
}

private extension String {
	var nilIfEmpty: String? {
		isEmpty ? nil : self
	}
}
