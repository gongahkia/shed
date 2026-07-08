import Foundation

public enum WorkspaceFileOperationError: Error, Equatable, Sendable {
	case emptyName
	case invalidName(String)
	case pathEscapesWorkspace(URL)
	case sourceMissing(URL)
	case targetExists(URL)
	case notDirectory(URL)
}

public struct WorkspaceFileOperations {
	public var roots: [URL]
	public var fileManager: FileManager

	public init(roots: [URL], fileManager: FileManager = .default) {
		self.roots = roots.map(\.standardizedFileURL)
		self.fileManager = fileManager
	}

	@discardableResult
	public func createFile(named name: String, in directory: URL) throws -> URL {
		let target = try childURL(named: name, in: directory)
		guard !fileManager.fileExists(atPath: target.path) else {
			throw WorkspaceFileOperationError.targetExists(target)
		}
		guard fileManager.createFile(atPath: target.path, contents: Data()) else {
			throw CocoaError(.fileWriteUnknown)
		}
		return target
	}

	@discardableResult
	public func createFolder(named name: String, in directory: URL) throws -> URL {
		let target = try childURL(named: name, in: directory)
		guard !fileManager.fileExists(atPath: target.path) else {
			throw WorkspaceFileOperationError.targetExists(target)
		}
		try fileManager.createDirectory(at: target, withIntermediateDirectories: false)
		return target
	}

	@discardableResult
	public func rename(_ source: URL, to name: String) throws -> URL {
		let source = try existingWorkspaceURL(source)
		let target = try childURL(named: name, in: source.deletingLastPathComponent())
		guard target != source else {
			return source
		}
		guard !fileManager.fileExists(atPath: target.path) else {
			throw WorkspaceFileOperationError.targetExists(target)
		}
		try fileManager.moveItem(at: source, to: target)
		return target
	}

	@discardableResult
	public func duplicate(_ source: URL) throws -> URL {
		let source = try existingWorkspaceURL(source)
		let target = uniqueCopyURL(for: source)
		try fileManager.copyItem(at: source, to: target)
		return target
	}

	public func delete(_ source: URL) throws {
		try fileManager.removeItem(at: existingWorkspaceURL(source))
	}

	@discardableResult
	public func moveToTrash(_ source: URL) throws -> URL {
		let source = try existingWorkspaceURL(source)
		var trashed: NSURL?
		try fileManager.trashItem(at: source, resultingItemURL: &trashed)
		return (trashed as URL?) ?? source
	}

	@discardableResult
	public func move(_ source: URL, toDirectory directory: URL) throws -> URL {
		let source = try existingWorkspaceURL(source)
		let directory = try existingDirectory(directory)
		let target = directory.appendingPathComponent(source.lastPathComponent).standardizedFileURL
		try ensureInsideWorkspace(target)
		guard target != source else {
			return source
		}
		guard !fileManager.fileExists(atPath: target.path) else {
			throw WorkspaceFileOperationError.targetExists(target)
		}
		try fileManager.moveItem(at: source, to: target)
		return target
	}

	private func childURL(named name: String, in directory: URL) throws -> URL {
		let directory = try existingDirectory(directory)
		let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else {
			throw WorkspaceFileOperationError.emptyName
		}
		guard trimmed.rangeOfCharacter(from: CharacterSet(charactersIn: "/:")) == nil else {
			throw WorkspaceFileOperationError.invalidName(name)
		}
		let target = directory.appendingPathComponent(trimmed).standardizedFileURL
		try ensureInsideWorkspace(target)
		return target
	}

	private func existingWorkspaceURL(_ url: URL) throws -> URL {
		let url = url.standardizedFileURL
		guard fileManager.fileExists(atPath: url.path) else {
			throw WorkspaceFileOperationError.sourceMissing(url)
		}
		try ensureInsideWorkspace(url)
		return url
	}

	private func existingDirectory(_ url: URL) throws -> URL {
		let url = try existingWorkspaceURL(url)
		var isDirectory: ObjCBool = false
		guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue else {
			throw WorkspaceFileOperationError.notDirectory(url)
		}
		return url
	}

	private func ensureInsideWorkspace(_ url: URL) throws {
		let path = url.standardizedFileURL.path
		for root in roots {
			let rootPath = root.standardizedFileURL.path
			if path == rootPath || path.hasPrefix(rootPath + "/") {
				return
			}
		}
		throw WorkspaceFileOperationError.pathEscapesWorkspace(url)
	}

	private func uniqueCopyURL(for source: URL) -> URL {
		let directory = source.deletingLastPathComponent()
		let base = source.deletingPathExtension().lastPathComponent
		let ext = source.pathExtension
		var index = 1
		while true {
			let suffix = index == 1 ? " copy" : " copy \(index)"
			var candidate = directory.appendingPathComponent(base + suffix)
			if !ext.isEmpty {
				candidate = candidate.appendingPathExtension(ext)
			}
			if !fileManager.fileExists(atPath: candidate.path) {
				return candidate.standardizedFileURL
			}
			index += 1
		}
	}
}
