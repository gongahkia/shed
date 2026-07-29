import Darwin
import Foundation

public enum AtomicFileWriteError: Error, Equatable, CustomStringConvertible {
	case openTemporary(path: String, code: Int32)
	case writeFailed(path: String, code: Int32)
	case syncFailed(path: String, code: Int32)
	case closeFailed(path: String, code: Int32)
	case replaceFailed(path: String, code: Int32)
	case directorySyncFailed(path: String, code: Int32)

	public var description: String {
		switch self {
		case let .openTemporary(path, code): "cannot create temporary file for \(path): \(Self.message(code))"
		case let .writeFailed(path, code): "cannot write \(path): \(Self.message(code))"
		case let .syncFailed(path, code): "cannot sync \(path): \(Self.message(code))"
		case let .closeFailed(path, code): "cannot close \(path): \(Self.message(code))"
		case let .replaceFailed(path, code): "cannot replace \(path): \(Self.message(code))"
		case let .directorySyncFailed(path, code): "cannot sync directory for \(path): \(Self.message(code))"
		}
	}

	public var cocoaError: CocoaError {
		let code: CocoaError.Code = errnoCode == ENOSPC ? .fileWriteOutOfSpace : errnoCode == EACCES || errnoCode == EPERM ? .fileWriteNoPermission : .fileWriteUnknown
		return CocoaError(code, userInfo: [NSFilePathErrorKey: path, NSLocalizedDescriptionKey: description])
	}

	private var path: String {
		switch self {
		case let .openTemporary(path, _), let .writeFailed(path, _), let .syncFailed(path, _), let .closeFailed(path, _), let .replaceFailed(path, _), let .directorySyncFailed(path, _): path
		}
	}

	private var errnoCode: Int32 {
		switch self {
		case let .openTemporary(_, code), let .writeFailed(_, code), let .syncFailed(_, code), let .closeFailed(_, code), let .replaceFailed(_, code), let .directorySyncFailed(_, code): code
		}
	}

	private static func message(_ code: Int32) -> String {
		String(cString: strerror(code))
	}
}

public struct AtomicFileOperations {
	public var open: (String, Int32, mode_t) -> Int32
	public var write: (Int32, UnsafeRawPointer, Int) -> Int
	public var fsync: (Int32) -> Int32
	public var close: (Int32) -> Int32
	public var rename: (String, String) -> Int32
	public var unlink: (String) -> Int32

	public init(
		open: @escaping (String, Int32, mode_t) -> Int32,
		write: @escaping (Int32, UnsafeRawPointer, Int) -> Int,
		fsync: @escaping (Int32) -> Int32,
		close: @escaping (Int32) -> Int32,
		rename: @escaping (String, String) -> Int32,
		unlink: @escaping (String) -> Int32
	) {
		self.open = open
		self.write = write
		self.fsync = fsync
		self.close = close
		self.rename = rename
		self.unlink = unlink
	}

	public static let live = AtomicFileOperations(
		open: { path, flags, mode in Darwin.open(path, flags, mode) },
		write: { descriptor, buffer, count in Darwin.write(descriptor, buffer, count) },
		fsync: { Darwin.fsync($0) },
		close: { Darwin.close($0) },
		rename: { Darwin.rename($0, $1) },
		unlink: { Darwin.unlink($0) }
	)
}

public enum AtomicFileWriter {
	public static func write(data: Data, to destination: URL, operations: AtomicFileOperations = .live) throws {
		try write(to: destination, operations: operations) { descriptor in
			try writeAll(data, to: descriptor, path: destination.path, operations: operations)
		}
	}

	public static func write(
		to destination: URL,
		operations: AtomicFileOperations = .live,
		contents: (Int32) throws -> Void
	) throws {
		let destinationPath = destination.path
		let temporaryPath = destination.deletingLastPathComponent()
			.appendingPathComponent(".\(destination.lastPathComponent).itsy-\(UUID().uuidString).tmp")
			.path
		let descriptor = operations.open(temporaryPath, O_WRONLY | O_CREAT | O_EXCL, destinationMode(destinationPath))
		guard descriptor >= 0 else {
			throw AtomicFileWriteError.openTemporary(path: destinationPath, code: errno)
		}
		var needsCleanup = true
		var isOpen = true
		defer {
			if needsCleanup {
				if isOpen {
					_ = operations.close(descriptor)
				}
				_ = operations.unlink(temporaryPath)
			}
		}
		do {
			try contents(descriptor)
		} catch {
			throw error
		}
		guard operations.fsync(descriptor) == 0 else {
			throw AtomicFileWriteError.syncFailed(path: destinationPath, code: errno)
		}
		guard operations.close(descriptor) == 0 else {
			throw AtomicFileWriteError.closeFailed(path: destinationPath, code: errno)
		}
		isOpen = false
		guard operations.rename(temporaryPath, destinationPath) == 0 else {
			throw AtomicFileWriteError.replaceFailed(path: destinationPath, code: errno)
		}
		needsCleanup = false
		try syncDirectory(containing: destination, operations: operations)
	}

	private static func writeAll(_ data: Data, to descriptor: Int32, path: String, operations: AtomicFileOperations) throws {
		guard !data.isEmpty else {
			return
		}
		try data.withUnsafeBytes { buffer in
			guard let base = buffer.baseAddress else {
				return
			}
			var written = 0
			while written < buffer.count {
				let count = operations.write(descriptor, base.advanced(by: written), buffer.count - written)
				guard count > 0 else {
					throw AtomicFileWriteError.writeFailed(path: path, code: errno == 0 ? EIO : errno)
				}
				written += count
			}
		}
	}

	private static func syncDirectory(containing destination: URL, operations: AtomicFileOperations) throws {
		let path = destination.deletingLastPathComponent().path
		let descriptor = operations.open(path, O_RDONLY, 0)
		guard descriptor >= 0 else {
			throw AtomicFileWriteError.directorySyncFailed(path: destination.path, code: errno)
		}
		defer { _ = operations.close(descriptor) }
		guard operations.fsync(descriptor) == 0 else {
			throw AtomicFileWriteError.directorySyncFailed(path: destination.path, code: errno)
		}
	}

	private static func destinationMode(_ path: String) -> mode_t {
		guard let attributes = try? FileManager.default.attributesOfItem(atPath: path),
		      let permissions = attributes[.posixPermissions] as? NSNumber
		else {
			return mode_t(S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH)
		}
		return mode_t(permissions.uint16Value)
	}
}
