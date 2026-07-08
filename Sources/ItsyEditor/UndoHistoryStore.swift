import Foundation

public struct UndoHistoryStore: Sendable {
	public var maxNodeCount: Int
	public var maxTotalTextBytes: Int

	public init(maxNodeCount: Int = 256, maxTotalTextBytes: Int = 8 * 1024 * 1024) {
		self.maxNodeCount = maxNodeCount
		self.maxTotalTextBytes = maxTotalTextBytes
	}

	public func undoDirectory(workspaceRoot: URL) -> URL {
		workspaceRoot.appendingPathComponent(".itsy/undo", isDirectory: true)
	}

	public func historyURL(fileURL: URL, workspaceRoot: URL) -> URL {
		undoDirectory(workspaceRoot: workspaceRoot).appendingPathComponent(Self.fileHash(for: fileURL) + ".bin")
	}

	public func load(fileURL: URL, workspaceRoot: URL) -> UndoTree? {
		let url = historyURL(fileURL: fileURL, workspaceRoot: workspaceRoot)
		guard let data = try? Data(contentsOf: url) else {
			return nil
		}
		return try? JSONDecoder().decode(UndoTree.self, from: data)
	}

	public func save(_ tree: UndoTree, fileURL: URL, workspaceRoot: URL) throws {
		let directory = undoDirectory(workspaceRoot: workspaceRoot)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		let limited = tree.limited(maxNodeCount: maxNodeCount, maxTotalTextBytes: maxTotalTextBytes)
		let data = try JSONEncoder().encode(limited)
		try data.write(to: historyURL(fileURL: fileURL, workspaceRoot: workspaceRoot), options: .atomic)
	}

	public static func fileHash(for fileURL: URL) -> String {
		let path = fileURL.standardizedFileURL.path
		var hash: UInt64 = 0xCBF2_9CE4_8422_2325
		for byte in path.utf8 {
			hash ^= UInt64(byte)
			hash &*= 0x0000_0100_0000_01B3
		}
		return String(hash, radix: 16)
	}
}
