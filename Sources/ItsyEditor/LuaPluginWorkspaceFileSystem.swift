import Darwin
import Foundation

public enum LuaPluginWorkspaceFileSystemError: Error, Equatable, Sendable {
	case invalidPath(String)
	case workspaceUnavailable(URL)
	case missing(String)
	case symbolicLinkRejected(String)
	case notRegularFile(String)
	case invalidUTF8(String)
	case ioFailure(path: String, code: Int32)
}

public struct LuaPluginWorkspaceFileSystem: Sendable {
	public let workspaceRoot: URL

	public init(workspaceRoot: URL) throws {
		let root = workspaceRoot.resolvingSymlinksInPath().standardizedFileURL
		var metadata = stat()
		guard lstat(root.path, &metadata) == 0, (metadata.st_mode & S_IFMT) == S_IFDIR else {
			throw LuaPluginWorkspaceFileSystemError.workspaceUnavailable(root)
		}
		self.workspaceRoot = root
	}

	public func read(_ relativePath: String) throws -> String {
		try withParentDirectory(for: relativePath) { directory, name in
			let descriptor = name.withCString { openat(directory, $0, O_RDONLY | O_NOFOLLOW) }
			guard descriptor >= 0 else { throw failure(for: relativePath) }
			defer { close(descriptor) }
			var metadata = stat()
			guard fstat(descriptor, &metadata) == 0 else { throw failure(for: relativePath) }
			guard (metadata.st_mode & S_IFMT) == S_IFREG else {
				throw LuaPluginWorkspaceFileSystemError.notRegularFile(relativePath)
			}
			let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
			let data = try handle.readToEnd() ?? Data()
			guard let contents = String(data: data, encoding: .utf8) else {
				throw LuaPluginWorkspaceFileSystemError.invalidUTF8(relativePath)
			}
			return contents
		}
	}

	public func write(_ relativePath: String, contents: String) throws {
		let data = Data(contents.utf8)
		try withParentDirectory(for: relativePath) { directory, name in
			var existing = stat()
			let existingStatus = name.withCString { fstatat(directory, $0, &existing, AT_SYMLINK_NOFOLLOW) }
			if existingStatus == 0 {
				let type = existing.st_mode & S_IFMT
				if type == S_IFLNK {
					throw LuaPluginWorkspaceFileSystemError.symbolicLinkRejected(relativePath)
				}
				guard type == S_IFREG else {
					throw LuaPluginWorkspaceFileSystemError.notRegularFile(relativePath)
				}
			} else if errno != ENOENT {
				throw failure(for: relativePath)
			}
			let temporary = ".itsy-lua-\(UUID().uuidString)"
			let descriptor = temporary.withCString { openat(directory, $0, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600) }
			guard descriptor >= 0 else { throw failure(for: relativePath) }
			defer {
				close(descriptor)
				temporary.withCString { _ = unlinkat(directory, $0, 0) }
			}
			if existingStatus == 0 {
				guard fchmod(descriptor, existing.st_mode & 0o777) == 0 else { throw failure(for: relativePath) }
			}
			let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
			try handle.write(contentsOf: data)
			try handle.synchronize()
			let renamed = temporary.withCString { temporaryName in
				name.withCString { targetName in renameat(directory, temporaryName, directory, targetName) }
			}
			guard renamed == 0 else { throw failure(for: relativePath) }
		}
	}

	private func withParentDirectory<Result>(for relativePath: String, body: (Int32, String) throws -> Result) throws -> Result {
		let components = try pathComponents(relativePath)
		let root = workspaceRoot.path.withCString { open($0, O_RDONLY | O_DIRECTORY | O_NOFOLLOW) }
		guard root >= 0 else { throw failure(for: ".") }
		var directory = root
		defer { close(directory) }
		for component in components.dropLast() {
			let next = component.withCString { openat(directory, $0, O_RDONLY | O_DIRECTORY | O_NOFOLLOW) }
			guard next >= 0 else { throw failure(for: relativePath) }
			close(directory)
			directory = next
		}
		return try body(directory, components[components.count - 1])
	}

	private func pathComponents(_ relativePath: String) throws -> [String] {
		guard !relativePath.isEmpty, !relativePath.hasPrefix("/"), !relativePath.contains("\0") else {
			throw LuaPluginWorkspaceFileSystemError.invalidPath(relativePath)
		}
		let components = relativePath.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
		guard !components.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
			throw LuaPluginWorkspaceFileSystemError.invalidPath(relativePath)
		}
		return components
	}

	private func failure(for path: String) -> LuaPluginWorkspaceFileSystemError {
		switch errno {
		case ENOENT: .missing(path)
		case ELOOP: .symbolicLinkRejected(path)
		default: .ioFailure(path: path, code: errno)
		}
	}
}
