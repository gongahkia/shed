import Foundation

public struct GitWorkspaceSnapshot: Equatable, Sendable {
	public var root: URL
	public var status: GitStatus
	private var entriesByPath: [String: GitStatusEntry]

	public init(root: URL, status: GitStatus) {
		self.root = root.standardizedFileURL
		self.status = status
		var entries: [String: GitStatusEntry] = [:]
		for entry in status.entries {
			entries[entry.path] = entry
			if let originalPath = entry.originalPath {
				entries[originalPath] = entry
			}
		}
		entriesByPath = entries
	}

	public func entry(for url: URL) -> GitStatusEntry? {
		guard let path = relativePath(for: url) else {
			return nil
		}
		return entriesByPath[path]
	}

	public func relativePath(for url: URL) -> String? {
		let rootPath = root.standardizedFileURL.path
		let filePath = url.standardizedFileURL.path
		if filePath == rootPath {
			return "."
		}
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		guard filePath.hasPrefix(prefix) else {
			return nil
		}
		return String(filePath.dropFirst(prefix.count))
	}

	public var branchLabel: String {
		status.branch.head ?? "detached"
	}
}
