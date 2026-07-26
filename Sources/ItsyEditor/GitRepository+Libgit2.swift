import CLibgit2
import Foundation

public extension GitRepository {
	enum Libgit2 {
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

			public static func discover(from path: URL) throws -> Repository {
				try Runtime.retain()
				var buffer = git_buf()
				defer {
					git_buf_dispose(&buffer)
					Runtime.release()
				}
				let result = path.withUnsafeFileSystemRepresentation { fileSystemPath in
					git_repository_discover(&buffer, fileSystemPath, 0, nil)
				}
				try check(result)
				guard let discoveredPath = buffer.ptr else {
					throw Failure(code: -1, message: "git_repository_discover returned nil")
				}
				return try open(at: URL(fileURLWithPath: String(cString: discoveredPath), isDirectory: true))
			}

			public var isBare: Bool {
				git_repository_is_bare(raw) == 1
			}

			public var worktreeURL: URL? {
				guard let path = git_repository_workdir(raw) else {
					return nil
				}
				return URL(fileURLWithPath: String(cString: path), isDirectory: true).standardizedFileURL
			}

			public func status(pathspec: [String] = []) throws -> StatusList {
				var options = git_status_options()
				try Libgit2.check(git_status_options_init(&options, 1))
				options.flags = Libgit2StatusOption.defaults
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

			func gitStatus() throws -> GitStatus {
				try GitStatus(branch: branchStatus(), entries: status().entries)
			}

			public func diff(cached: Bool, pathspec: [String] = []) throws -> Diff {
				var options = git_diff_options()
				try Libgit2.check(git_diff_options_init(&options, 1))
				return try withGitStrarray(pathspec) { pathspecArray in
					if let pathspecArray {
						options.pathspec = pathspecArray
					}
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

			public func blame(path: String) throws -> [GitBlameLine] {
				var options = git_blame_options()
				try Libgit2.check(git_blame_options_init(&options, 1))
				var rawBlame: OpaquePointer?
				try path.withCString { value in
					try Libgit2.check(git_blame_file(&rawBlame, self.raw, value, &options))
				}
				guard let rawBlame else {
					throw Failure(code: -1, message: "git_blame_file returned nil")
				}
				defer {
					git_blame_free(rawBlame)
				}
				let lineCount = Int(git_blame_linecount(rawBlame))
				guard lineCount > 0 else {
					return []
				}
				var lines: [GitBlameLine] = []
				lines.reserveCapacity(lineCount)
				for line in 1 ... lineCount {
					guard let pointer = git_blame_hunk_byline(rawBlame, line) else {
						continue
					}
					let hunk = pointer.pointee
					let finalStart = Int(hunk.final_start_line_number)
					let originalStart = Int(hunk.orig_start_line_number)
					lines.append(GitBlameLine(
						line: line,
						originalLine: originalStart + max(0, line - finalStart),
						oid: oidString(hunk.final_commit_id),
						summary: hunk.summary.map(String.init(cString:)) ?? "",
						author: hunk.final_signature.map { String(cString: $0.pointee.name) } ?? "",
						authorEmail: hunk.final_signature.map { String(cString: $0.pointee.email) } ?? "",
						time: hunk.final_signature.map { Date(timeIntervalSince1970: TimeInterval($0.pointee.when.time)) },
						originalPath: hunk.orig_path.map(String.init(cString:))
					))
				}
				return lines
			}

			public func commit(message: String) throws -> String {
				var oid = git_oid()
				try message.withCString { value in
					try Libgit2.check(git_commit_create_from_stage(&oid, raw, value, nil))
				}
				return oidString(oid)
			}

			public func conflicts() throws -> [GitConflictEntry] {
				let index = try repositoryIndex()
				defer {
					git_index_free(index)
				}
				var iterator: OpaquePointer?
				try Libgit2.check(git_index_conflict_iterator_new(&iterator, index))
				guard let iterator else {
					return []
				}
				defer {
					git_index_conflict_iterator_free(iterator)
				}
				var conflicts: [GitConflictEntry] = []
				while true {
					var ancestor: UnsafePointer<git_index_entry>?
					var ours: UnsafePointer<git_index_entry>?
					var theirs: UnsafePointer<git_index_entry>?
					let result = git_index_conflict_next(&ancestor, &ours, &theirs, iterator)
					if result == Libgit2ErrorCode.iterOver {
						break
					}
					try Libgit2.check(result)
					let path = Self.path(for: ours) ?? Self.path(for: theirs) ?? Self.path(for: ancestor) ?? ""
					guard !path.isEmpty else {
						continue
					}
					conflicts.append(GitConflictEntry(
						path: path,
						ancestorPath: Self.path(for: ancestor),
						oursPath: Self.path(for: ours),
						theirsPath: Self.path(for: theirs)
					))
				}
				return conflicts
			}

			public func conflictBlob(path: String, stage: Int) throws -> String {
				let entry = try conflictIndexEntry(path: path, stage: stage)
				var rawBlob: OpaquePointer?
				var oid = entry.id
				try Libgit2.check(git_blob_lookup(&rawBlob, raw, &oid))
				guard let rawBlob else {
					throw Failure(code: -1, message: "git_blob_lookup returned nil")
				}
				defer {
					git_blob_free(rawBlob)
				}
				let size = Int(git_blob_rawsize(rawBlob))
				guard let content = git_blob_rawcontent(rawBlob), size > 0 else {
					return ""
				}
				return String(decoding: Data(bytes: content, count: size), as: UTF8.self)
			}

			public func applyCachedPatch(_ patch: String) throws {
				let diff = try Self.diff(from: patch)
				defer {
					git_diff_free(diff)
				}
				var options = git_apply_options()
				try Libgit2.check(git_apply_options_init(&options, 1))
				try Libgit2.check(git_apply(raw, diff, GIT_APPLY_LOCATION_INDEX, &options))
			}

			private func conflictIndexEntry(path: String, stage: Int) throws -> git_index_entry {
				let index = try repositoryIndex()
				defer {
					git_index_free(index)
				}
				var iterator: OpaquePointer?
				try Libgit2.check(git_index_conflict_iterator_new(&iterator, index))
				guard let iterator else {
					throw Failure(code: Libgit2ErrorCode.notFound, message: "conflict iterator returned nil")
				}
				defer {
					git_index_conflict_iterator_free(iterator)
				}
				while true {
					var ancestor: UnsafePointer<git_index_entry>?
					var ours: UnsafePointer<git_index_entry>?
					var theirs: UnsafePointer<git_index_entry>?
					let result = git_index_conflict_next(&ancestor, &ours, &theirs, iterator)
					if result == Libgit2ErrorCode.iterOver {
						break
					}
					try Libgit2.check(result)
					let entry: UnsafePointer<git_index_entry>? = switch stage {
					case 1:
						ancestor
					case 2:
						ours
					case 3:
						theirs
					default:
						nil
					}
					guard let entry, Self.path(for: entry) == path else {
						continue
					}
					return entry.pointee
				}
				throw Failure(code: Libgit2ErrorCode.notFound, message: "conflict stage \(stage) not found for \(path)")
			}

			private func repositoryIndex() throws -> OpaquePointer {
				var index: OpaquePointer?
				try Libgit2.check(git_repository_index(&index, raw))
				guard let index else {
					throw Failure(code: -1, message: "git_repository_index returned nil")
				}
				return index
			}

			private static func path(for entry: UnsafePointer<git_index_entry>?) -> String? {
				entry?.pointee.path.map { String(cString: $0) }
			}

			private static func diff(from patch: String) throws -> OpaquePointer {
				var diff: OpaquePointer?
				try patch.withCString { pointer in
					try Libgit2.check(git_diff_from_buffer(&diff, pointer, strlen(pointer)))
				}
				guard let diff else {
					throw Failure(code: -1, message: "git_diff_from_buffer returned nil")
				}
				return diff
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

			var entries: [GitStatusEntry] {
				(0 ..< count).compactMap { index in
					guard let pointer = git_status_byindex(raw, index) else {
						return nil
					}
					return GitStatusEntry(libgit2: pointer.pointee)
				}
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

			public func patchText() throws -> String {
				var buffer = git_buf()
				try GitRepository.Libgit2.check(git_diff_to_buf(&buffer, raw, GIT_DIFF_FORMAT_PATCH))
				defer {
					git_buf_dispose(&buffer)
				}
				guard let pointer = buffer.ptr, buffer.size > 0 else {
					return ""
				}
				let bytes = UnsafeRawPointer(pointer).assumingMemoryBound(to: UInt8.self)
				let data = Data(bytes: bytes, count: buffer.size)
				return String(decoding: data, as: UTF8.self)
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
	private static let state = Libgit2RuntimeState()

	static func retain() throws {
		try state.retain()
	}

	static func release() {
		state.release()
	}
}

private final class Libgit2RuntimeState: @unchecked Sendable {
	private let lock = NSLock()
	private var count = 0

	func retain() throws {
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

	func release() {
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
	static let includeIgnored: UInt32 = 1 << 1
	static let recurseUntrackedDirs: UInt32 = 1 << 4
	static let renamesHeadToIndex: UInt32 = 1 << 7
	static let renamesIndexToWorkdir: UInt32 = 1 << 8
	static let defaults = includeUntracked | includeIgnored | recurseUntrackedDirs | renamesHeadToIndex | renamesIndexToWorkdir
}

private enum Libgit2StatusFlag {
	static let indexNew: UInt32 = 1 << 0
	static let indexModified: UInt32 = 1 << 1
	static let indexDeleted: UInt32 = 1 << 2
	static let indexRenamed: UInt32 = 1 << 3
	static let indexTypeChange: UInt32 = 1 << 4
	static let worktreeNew: UInt32 = 1 << 7
	static let worktreeModified: UInt32 = 1 << 8
	static let worktreeDeleted: UInt32 = 1 << 9
	static let worktreeTypeChange: UInt32 = 1 << 10
	static let worktreeRenamed: UInt32 = 1 << 11
	static let ignored: UInt32 = 1 << 14
	static let conflicted: UInt32 = 1 << 15
}

private enum Libgit2ErrorCode {
	static let notFound: Int32 = -3
	static let unbornBranch: Int32 = -9
	static let iterOver: Int32 = -31
}

private extension GitRepository.Libgit2.Repository {
	func branchStatus() throws -> GitBranchStatus {
		let unborn = git_repository_head_unborn(raw) == 1
		var head: OpaquePointer?
		let result = git_repository_head(&head, raw)
		if result == Libgit2ErrorCode.unbornBranch || unborn {
			return try unbornBranchStatus()
		}
		if result == Libgit2ErrorCode.notFound {
			return GitBranchStatus()
		}
		try GitRepository.Libgit2.check(result)
		guard let head else {
			return GitBranchStatus()
		}
		defer {
			git_reference_free(head)
		}
		let detached = git_repository_head_detached(raw) == 1
		let oid = git_reference_target(head).map { oidString($0) }
		var status = GitBranchStatus(oid: oid, head: detached ? nil : shorthand(head))
		guard !detached else {
			return status
		}
		var upstream: OpaquePointer?
		let upstreamResult = git_branch_upstream(&upstream, head)
		if upstreamResult == Libgit2ErrorCode.notFound {
			return status
		}
		try GitRepository.Libgit2.check(upstreamResult)
		guard let upstream else {
			return status
		}
		defer {
			git_reference_free(upstream)
		}
		status.upstream = shorthand(upstream)
		if let localOID = git_reference_target(head), let upstreamOID = git_reference_target(upstream) {
			var ahead = 0
			var behind = 0
			try GitRepository.Libgit2.check(git_graph_ahead_behind(&ahead, &behind, raw, localOID, upstreamOID))
			status.ahead = ahead
			status.behind = behind
		}
		return status
	}

	func unbornBranchStatus() throws -> GitBranchStatus {
		var head: OpaquePointer?
		let result = "HEAD".withCString { name in
			git_reference_lookup(&head, raw, name)
		}
		if result == Libgit2ErrorCode.notFound {
			return GitBranchStatus(oid: "(initial)")
		}
		try GitRepository.Libgit2.check(result)
		defer {
			if let head {
				git_reference_free(head)
			}
		}
		guard let target = head.flatMap(git_reference_symbolic_target) else {
			return GitBranchStatus(oid: "(initial)")
		}
		let name = String(cString: target)
		return GitBranchStatus(oid: "(initial)", head: name.removingPrefix("refs/heads/"))
	}
}

private extension GitStatusEntry {
	init(libgit2 entry: git_status_entry) {
		let flags = UInt32(entry.status.rawValue)
		if flags.has(Libgit2StatusFlag.conflicted) {
			self.init(kind: .unmerged, indexStatus: "U", worktreeStatus: "U", path: entry.bestPath)
			return
		}
		if flags.has(Libgit2StatusFlag.ignored) {
			self.init(kind: .ignored, indexStatus: "!", worktreeStatus: "!", path: entry.bestPath)
			return
		}
		if flags.has(Libgit2StatusFlag.worktreeNew), !flags.hasIndexChange {
			self.init(kind: .untracked, indexStatus: "?", worktreeStatus: "?", path: entry.bestPath)
			return
		}
		let indexStatus = flags.indexStatus
		let worktreeStatus = flags.worktreeStatus
		if indexStatus == "R" || worktreeStatus == "R" {
			let delta = entry.head_to_index ?? entry.index_to_workdir
			self.init(
				kind: .renamed,
				indexStatus: indexStatus,
				worktreeStatus: worktreeStatus,
				path: delta?.pointee.newPath ?? entry.bestPath,
				originalPath: delta?.pointee.oldPath
			)
			return
		}
		self.init(kind: .ordinary, indexStatus: indexStatus, worktreeStatus: worktreeStatus, path: entry.bestPath)
	}
}

private extension git_status_entry {
	var bestPath: String {
		head_to_index?.pointee.newPath
			?? head_to_index?.pointee.oldPath
			?? index_to_workdir?.pointee.newPath
			?? index_to_workdir?.pointee.oldPath
			?? ""
	}
}

private extension git_diff_delta {
	var oldPath: String? {
		old_file.path.map(String.init(cString:))
	}

	var newPath: String? {
		new_file.path.map(String.init(cString:))
	}
}

private extension UInt32 {
	func has(_ flag: UInt32) -> Bool {
		self & flag != 0
	}

	var hasIndexChange: Bool {
		has(Libgit2StatusFlag.indexNew)
			|| has(Libgit2StatusFlag.indexModified)
			|| has(Libgit2StatusFlag.indexDeleted)
			|| has(Libgit2StatusFlag.indexRenamed)
			|| has(Libgit2StatusFlag.indexTypeChange)
	}

	var indexStatus: Character {
		if has(Libgit2StatusFlag.indexRenamed) {
			return "R"
		}
		if has(Libgit2StatusFlag.indexNew) {
			return "A"
		}
		if has(Libgit2StatusFlag.indexModified) {
			return "M"
		}
		if has(Libgit2StatusFlag.indexDeleted) {
			return "D"
		}
		if has(Libgit2StatusFlag.indexTypeChange) {
			return "T"
		}
		return "."
	}

	var worktreeStatus: Character {
		if has(Libgit2StatusFlag.worktreeRenamed) {
			return "R"
		}
		if has(Libgit2StatusFlag.worktreeNew) {
			return "?"
		}
		if has(Libgit2StatusFlag.worktreeModified) {
			return "M"
		}
		if has(Libgit2StatusFlag.worktreeDeleted) {
			return "D"
		}
		if has(Libgit2StatusFlag.worktreeTypeChange) {
			return "T"
		}
		return "."
	}
}

private func oidString(_ oid: UnsafePointer<git_oid>) -> String {
	guard let value = git_oid_tostr_s(oid) else {
		return ""
	}
	return String(cString: value)
}

private func oidString(_ oid: git_oid) -> String {
	var oid = oid
	return withUnsafePointer(to: &oid) { oidString($0) }
}

private func shorthand(_ ref: OpaquePointer) -> String? {
	git_reference_shorthand(ref).map(String.init(cString:))
}

private extension String {
	func removingPrefix(_ prefix: String) -> String {
		hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
	}
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
