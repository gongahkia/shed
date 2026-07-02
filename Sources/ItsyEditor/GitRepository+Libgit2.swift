import CLibgit2
import Foundation

extension GitRepository {
	public enum Libgit2 {
		public struct Failure: Error, Equatable, Sendable, CustomStringConvertible {
			public var code: Int32
			public var message: String

			public var description: String {
				"libgit2 error \(code): \(message)"
			}
		}

		public final class Repository {
			public let path: URL
			let raw: OpaquePointer

			private init(raw: OpaquePointer, path: URL) {
				self.raw = raw
				self.path = path
			}

			deinit {
				git_repository_free(raw)
				Runtime.release()
			}

			public static func open(at path: URL) throws -> Repository {
				try Runtime.retain()
				var raw: OpaquePointer?
				let result = path.withUnsafeFileSystemRepresentation { fileSystemPath in
					git_repository_open(&raw, fileSystemPath)
				}
				do {
					try check(result)
				} catch {
					Runtime.release()
					throw error
				}
				guard let raw else {
					Runtime.release()
					throw Failure(code: -1, message: "git_repository_open returned nil")
				}
				return Repository(raw: raw, path: path)
			}

			public func status(pathspec: [String] = []) throws -> StatusList {
				var options = git_status_options()
				try Libgit2.check(git_status_options_init(&options, 1))
				options.flags = Libgit2StatusOption.includeUntracked | Libgit2StatusOption.recurseUntrackedDirs
				let rawStatus = try withGitStrarray(pathspec) { pathspecArray in
					if let pathspecArray {
						options.pathspec = pathspecArray
					}
					var raw: OpaquePointer?
					try Libgit2.check(git_status_list_new(&raw, self.raw, &options))
					guard let raw else {
						throw Failure(code: -1, message: "git_status_list_new returned nil")
					}
					return raw
				}
				return StatusList(raw: rawStatus)
			}

			public func diff(cached: Bool) throws -> Diff {
				var options = git_diff_options()
				try Libgit2.check(git_diff_options_init(&options, 1))
				var raw: OpaquePointer?
				if cached {
					var headTree: OpaquePointer?
					let headResult = "HEAD^{tree}".withCString { spec in
						git_revparse_single(&headTree, self.raw, spec)
					}
					if headResult < 0, headResult != -3, headResult != -9 {
						try Libgit2.check(headResult)
					}
					defer {
						if let headTree {
							git_object_free(headTree)
						}
					}
					try Libgit2.check(git_diff_tree_to_index(&raw, self.raw, headTree, nil, &options))
				} else {
					try Libgit2.check(git_diff_index_to_workdir(&raw, self.raw, nil, &options))
				}
				guard let raw else {
					throw Failure(code: -1, message: "git_diff returned nil")
				}
				return Diff(raw: raw)
			}

			public func blob(at oid: String) throws -> Blob {
				var gitOID = git_oid()
				try oid.withCString { value in
					try Libgit2.check(git_oid_fromstr(&gitOID, value))
				}
				var raw: OpaquePointer?
				try Libgit2.check(git_blob_lookup(&raw, self.raw, &gitOID))
				guard let raw else {
					throw Failure(code: -1, message: "git_blob_lookup returned nil")
				}
				return Blob(raw: raw)
			}
		}

		public final class StatusList {
			let raw: OpaquePointer

			fileprivate init(raw: OpaquePointer) {
				self.raw = raw
			}

			deinit {
				git_status_list_free(raw)
			}

			public var count: Int {
				Int(git_status_list_entrycount(raw))
			}
		}

		public final class Diff {
			let raw: OpaquePointer

			fileprivate init(raw: OpaquePointer) {
				self.raw = raw
			}

			deinit {
				git_diff_free(raw)
			}

			public var count: Int {
				Int(git_diff_num_deltas(raw))
			}
		}

		public final class Blob {
			let raw: OpaquePointer

			fileprivate init(raw: OpaquePointer) {
				self.raw = raw
			}

			deinit {
				git_blob_free(raw)
			}

			public var size: Int {
				Int(git_blob_rawsize(raw))
			}

			public var data: Data {
				guard let content = git_blob_rawcontent(raw), size > 0 else {
					return Data()
				}
				return Data(bytes: content, count: size)
			}
		}

		fileprivate static func check(_ code: Int32) throws {
			guard code < 0 else {
				return
			}
			throw Failure(code: code, message: lastErrorMessage())
		}

		private static func lastErrorMessage() -> String {
			guard let error = git_error_last(), let message = error.pointee.message else {
				return "unknown libgit2 error"
			}
			return String(cString: message)
		}
	}
}

private enum Runtime {
	private static let lock = NSLock()
	private static var count = 0

	static func retain() throws {
		lock.lock()
		if count == 0 {
			do {
				try GitRepository.Libgit2.check(git_libgit2_init())
			} catch {
				lock.unlock()
				throw error
			}
		}
		count += 1
		lock.unlock()
	}

	static func release() {
		lock.lock()
		guard count > 0 else {
			lock.unlock()
			return
		}
		count -= 1
		if count == 0 {
			_ = git_libgit2_shutdown()
		}
		lock.unlock()
	}
}

private enum Libgit2StatusOption {
	static let includeUntracked: UInt32 = 1 << 0
	static let recurseUntrackedDirs: UInt32 = 1 << 4
}

private func withGitStrarray<T>(_ values: [String], _ body: (git_strarray?) throws -> T) throws -> T {
	guard !values.isEmpty else {
		return try body(nil)
	}
	var cStrings: [UnsafeMutablePointer<CChar>?] = []
	for value in values {
		guard let cString = strdup(value) else {
			throw GitRepository.Libgit2.Failure(code: -1, message: "strdup failed")
		}
		cStrings.append(cString)
	}
	defer {
		for value in cStrings {
			free(value)
		}
	}
	return try cStrings.withUnsafeMutableBufferPointer { buffer in
		let array = git_strarray(strings: buffer.baseAddress, count: values.count)
		return try body(array)
	}
}
