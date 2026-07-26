import Foundation

public struct WorkspaceSupportSnapshot: Equatable, Sendable {
	public var root: URL
	public var languageIDs: [String]
	public var componentIDs: [String]

	public init(root: URL, languageIDs: [String], componentIDs: [String]) {
		self.root = root.standardizedFileURL
		self.languageIDs = languageIDs.sorted()
		self.componentIDs = componentIDs.sorted()
	}
}

public enum WorkspaceSupportScanner {
	public static let ignoredDirectoryNames: Set<String> = [
		".git", ".build", ".swiftpm", ".venv", "build", "coverage", "deriveddata", "node_modules", "pods", "vendor",
	]

	public static func scan(
		root: URL,
		registry: LSPServerRegistry = LSPServerRegistry(),
		catalog: ManagedSupportCatalog = .bundled,
		fileManager: FileManager = .default
	) -> WorkspaceSupportSnapshot {
		let root = root.standardizedFileURL
		guard let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
			return WorkspaceSupportSnapshot(root: root, languageIDs: [], componentIDs: [])
		}
		var languages: Set<String> = []
		for case let url as URL in enumerator {
			let name = url.lastPathComponent.lowercased()
			if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true, ignoredDirectoryNames.contains(name) {
				enumerator.skipDescendants()
				continue
			}
			if let languageID = registry.languageID(for: url) {
				languages.insert(languageID)
			}
		}
		let componentIDs = Set(languages.compactMap { catalog.component(languageID: $0, kind: .languageServer)?.id })
		return WorkspaceSupportSnapshot(root: root, languageIDs: Array(languages), componentIDs: Array(componentIDs))
	}
}

public struct GitHubRepositoryReference: Equatable, Sendable {
	public var owner: String
	public var name: String

	public init(owner: String, name: String) {
		self.owner = owner
		self.name = name
	}
}

public enum GitHubRepositoryLocator {
	public static func repository(remoteURL: String) -> GitHubRepositoryReference? {
		let value = remoteURL.trimmingCharacters(in: .whitespacesAndNewlines)
		let path: String
		if let url = URL(string: value), url.host?.lowercased() == "github.com" {
			path = url.path
		} else if value.lowercased().hasPrefix("git@github.com:") {
			path = String(value.dropFirst("git@github.com:".count))
		} else {
			return nil
		}
		let parts = path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).split(separator: "/")
		guard parts.count == 2 else {
			return nil
		}
		let owner = String(parts[0])
		let name = String(parts[1]).replacingOccurrences(of: ".git", with: "", options: [.anchored, .backwards])
		guard !owner.isEmpty, !name.isEmpty else {
			return nil
		}
		return GitHubRepositoryReference(owner: owner, name: name)
	}
}

public enum GitHubRepositoryLanguageClient {
	public static func endpoint(for repository: GitHubRepositoryReference) -> URL {
		URL(string: "https://api.github.com/repos/\(repository.owner)/\(repository.name)/languages")!
	}

	public static func fetchPublicLanguages(
		for repository: GitHubRepositoryReference,
		session: URLSession = .shared
	) async throws -> [String: Int] {
		var request = URLRequest(url: endpoint(for: repository))
		request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
		let (data, response) = try await session.data(for: request)
		guard let response = response as? HTTPURLResponse, response.statusCode == 200 else {
			throw URLError(.badServerResponse)
		}
		return try JSONDecoder().decode([String: Int].self, from: data)
	}
}
