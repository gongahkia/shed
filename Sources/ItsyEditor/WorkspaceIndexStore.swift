import Foundation

public enum WorkspaceIndexStoreError: Error, Equatable {
	case unsupportedVersion(Int)
}

public struct WorkspaceIndexStore {
	private struct IndexFile: Codable, Equatable {
		var version: Int
		var rootPath: String
		var files: [WorkspaceIndexedFile]
	}

	public var directory: URL
	public var fileManager: FileManager

	public init(directory: URL = Self.defaultDirectory(), fileManager: FileManager = .default) {
		self.directory = directory
		self.fileManager = fileManager
	}

	public static func defaultDirectory() -> URL {
		let home = FileManager.default.homeDirectoryForCurrentUser
		return home
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("index", isDirectory: true)
	}

	public func load(for root: URL) throws -> WorkspaceIndex? {
		let url = storageURL(for: root)
		guard fileManager.fileExists(atPath: url.path) else {
			return nil
		}
		let file = try JSONDecoder().decode(IndexFile.self, from: Data(contentsOf: url))
		guard file.version == 1 else {
			throw WorkspaceIndexStoreError.unsupportedVersion(file.version)
		}
		guard file.rootPath == root.standardizedFileURL.path else {
			return nil
		}
		return WorkspaceIndex(root: root, files: file.files)
	}

	public func save(_ index: WorkspaceIndex) throws {
		let file = IndexFile(
			version: 1,
			rootPath: index.root.standardizedFileURL.path,
			files: Self.normalized(index.files)
		)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		let data = try encoder.encode(file)
		try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
		try data.write(to: storageURL(for: index.root), options: .atomic)
	}

	func storageURL(for workspace: URL) -> URL {
		directory.appendingPathComponent(Self.workspaceKey(for: workspace) + ".json")
	}

	static func workspaceKey(for workspace: URL) -> String {
		let path = workspace.standardizedFileURL.path
		var hash: UInt64 = 0xcbf29ce484222325
		for byte in path.utf8 {
			hash ^= UInt64(byte)
			hash &*= 0x100000001b3
		}
		return String(hash, radix: 16)
	}

	private static func normalized(_ files: [WorkspaceIndexedFile]) -> [WorkspaceIndexedFile] {
		files
			.map { file in
				WorkspaceIndexedFile(
					relativePath: file.relativePath,
					symbols: file.symbols.sorted(by: symbolOrder)
				)
			}
			.sorted { $0.relativePath < $1.relativePath }
	}
}

private func symbolOrder(_ lhs: WorkspaceSymbol, _ rhs: WorkspaceSymbol) -> Bool {
	if lhs.line != rhs.line {
		return lhs.line < rhs.line
	}
	if lhs.column != rhs.column {
		return lhs.column < rhs.column
	}
	if lhs.name != rhs.name {
		return lhs.name < rhs.name
	}
	return lhs.kind.rawValue < rhs.kind.rawValue
}
