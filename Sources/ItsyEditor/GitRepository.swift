import Dispatch
import Foundation

public struct GitBranchStatus: Equatable, Sendable {
	public var oid: String?
	public var head: String?
	public var upstream: String?
	public var ahead: Int?
	public var behind: Int?

	public init(oid: String? = nil, head: String? = nil, upstream: String? = nil, ahead: Int? = nil, behind: Int? = nil) {
		self.oid = oid
		self.head = head
		self.upstream = upstream
		self.ahead = ahead
		self.behind = behind
	}
}

public enum GitBranchKind: Equatable, Sendable {
	case local
	case remote
}

public struct GitBranch: Equatable, Sendable {
	public var name: String
	public var upstream: String?
	public var isCurrent: Bool
	public var committerDateRelative: String
	public var refname: String
	public var kind: GitBranchKind

	public init(name: String, upstream: String? = nil, isCurrent: Bool = false, committerDateRelative: String = "", refname: String = "", kind: GitBranchKind = .local) {
		self.name = name
		self.upstream = upstream
		self.isCurrent = isCurrent
		self.committerDateRelative = committerDateRelative
		self.refname = refname
		self.kind = kind
	}
}

public enum GitStatusEntryKind: Equatable, Sendable {
	case ordinary
	case renamed
	case unmerged
	case untracked
	case ignored
	case unknown(String)
}

public struct GitStatusEntry: Equatable, Sendable {
	public var kind: GitStatusEntryKind
	public var indexStatus: Character?
	public var worktreeStatus: Character?
	public var path: String
	public var originalPath: String?

	public init(kind: GitStatusEntryKind, indexStatus: Character?, worktreeStatus: Character?, path: String, originalPath: String? = nil) {
		self.kind = kind
		self.indexStatus = indexStatus
		self.worktreeStatus = worktreeStatus
		self.path = path
		self.originalPath = originalPath
	}

	public var isStaged: Bool {
		guard kind != .untracked, kind != .ignored else {
			return false
		}
		return Self.isChanged(indexStatus)
	}

	public var isUnstaged: Bool {
		if kind == .untracked {
			return true
		}
		guard kind != .ignored else {
			return false
		}
		return Self.isChanged(worktreeStatus)
	}

	public var isConflict: Bool {
		guard kind == .unmerged, let indexStatus, let worktreeStatus else {
			return false
		}
		return ["UU", "AA", "DU", "UD"].contains("\(indexStatus)\(worktreeStatus)")
	}

	private static func isChanged(_ status: Character?) -> Bool {
		guard let status else {
			return false
		}
		return status != "." && status != " "
	}
}

public struct GitStatus: Equatable, Sendable {
	public var branch: GitBranchStatus
	public var entries: [GitStatusEntry]

	public init(branch: GitBranchStatus = GitBranchStatus(), entries: [GitStatusEntry] = []) {
		self.branch = branch
		self.entries = entries
	}

	public var stagedCount: Int {
		entries.filter(\.isStaged).count
	}

	public var unstagedCount: Int {
		entries.filter(\.isUnstaged).count
	}

	public var hasChanges: Bool {
		!entries.isEmpty
	}
}

public struct GitBlameLine: Equatable, Sendable {
	public var line: Int
	public var originalLine: Int
	public var oid: String
	public var summary: String
	public var author: String
	public var authorEmail: String
	public var time: Date?
	public var originalPath: String?

	public init(line: Int, originalLine: Int, oid: String, summary: String, author: String, authorEmail: String, time: Date? = nil, originalPath: String? = nil) {
		self.line = line
		self.originalLine = originalLine
		self.oid = oid
		self.summary = summary
		self.author = author
		self.authorEmail = authorEmail
		self.time = time
		self.originalPath = originalPath
	}
}

public struct GitHistoryEntry: Equatable, Sendable {
	public var oid: String
	public var author: String
	public var authorEmail: String
	public var date: Date?
	public var summary: String

	public init(oid: String, author: String, authorEmail: String, date: Date? = nil, summary: String) {
		self.oid = oid
		self.author = author
		self.authorEmail = authorEmail
		self.date = date
		self.summary = summary
	}
}

public struct GitBlameCache: Sendable {
	private var entries: [Key: [GitBlameLine]] = [:]

	public init() {}

	public mutating func blame(path: String, repository: GitRepository) throws -> [GitBlameLine] {
		let key = Key(root: repository.root.standardizedFileURL.path, path: path)
		if let cached = entries[key] {
			return cached
		}
		let lines = try repository.blame(path: path)
		entries[key] = lines
		return lines
	}

	public mutating func invalidate() {
		entries.removeAll()
	}

	private struct Key: Hashable, Sendable {
		var root: String
		var path: String
	}
}

public enum GitStatusParseError: Error, Equatable, Sendable {
	case malformedLine(String)
}

public enum GitStatusParser {
	public static func parse(_ output: String) throws -> GitStatus {
		var branch = GitBranchStatus()
		var entries: [GitStatusEntry] = []
		for rawLine in output.split(whereSeparator: \.isNewline).map(String.init) {
			if rawLine.hasPrefix("# ") {
				parseHeader(rawLine, branch: &branch)
			} else if !rawLine.isEmpty {
				entries.append(try parseEntry(rawLine))
			}
		}
		return GitStatus(branch: branch, entries: entries)
	}

	private static func parseHeader(_ line: String, branch: inout GitBranchStatus) {
		let fields = line.split(separator: " ", maxSplits: 2).map(String.init)
		guard fields.count >= 3 else {
			return
		}
		switch fields[1] {
		case "branch.oid":
			branch.oid = fields[2]
		case "branch.head":
			branch.head = fields[2] == "(detached)" || fields[2] == "detached" ? nil : fields[2]
		case "branch.upstream":
			branch.upstream = fields[2]
		case "branch.ab":
			let counts = fields[2].split(separator: " ")
			for count in counts {
				if count.hasPrefix("+") {
					branch.ahead = Int(count.dropFirst())
				} else if count.hasPrefix("-") {
					branch.behind = Int(count.dropFirst())
				}
			}
		default:
			break
		}
	}

	private static func parseEntry(_ line: String) throws -> GitStatusEntry {
		if line.hasPrefix("? ") {
			return GitStatusEntry(kind: .untracked, indexStatus: "?", worktreeStatus: "?", path: String(line.dropFirst(2)))
		}
		if line.hasPrefix("! ") {
			return GitStatusEntry(kind: .ignored, indexStatus: "!", worktreeStatus: "!", path: String(line.dropFirst(2)))
		}
		if line.hasPrefix("1 ") {
			let fields = line.split(separator: " ", maxSplits: 8, omittingEmptySubsequences: false).map(String.init)
			guard fields.count == 9 else {
				throw GitStatusParseError.malformedLine(line)
			}
			let status = fields[1]
			return GitStatusEntry(kind: .ordinary, indexStatus: status.first, worktreeStatus: status.dropFirst().first, path: fields[8])
		}
		if line.hasPrefix("2 ") {
			let fields = line.split(separator: " ", maxSplits: 9, omittingEmptySubsequences: false).map(String.init)
			guard fields.count == 10 else {
				throw GitStatusParseError.malformedLine(line)
			}
			let status = fields[1]
			let paths = fields[9].split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
			return GitStatusEntry(
				kind: .renamed,
				indexStatus: status.first,
				worktreeStatus: status.dropFirst().first,
				path: paths.first ?? "",
				originalPath: paths.count > 1 ? paths[1] : nil
			)
		}
		if line.hasPrefix("u ") {
			let fields = line.split(separator: " ", maxSplits: 10, omittingEmptySubsequences: false).map(String.init)
			guard fields.count == 11 else {
				throw GitStatusParseError.malformedLine(line)
			}
			let status = fields[1]
			return GitStatusEntry(kind: .unmerged, indexStatus: status.first, worktreeStatus: status.dropFirst().first, path: fields[10])
		}
		return GitStatusEntry(kind: .unknown(String(line.prefix(1))), indexStatus: nil, worktreeStatus: nil, path: line)
	}
}

public protocol GitCommandRunning: Sendable {
	func runGit(arguments: [String], root: URL) throws -> String
	func runGit(arguments: [String], input: String, root: URL) throws -> String
}

public enum GitCommandError: Error, Equatable, Sendable {
	case failed(status: Int32, stderr: String)
	case invalidOutput
	case stdinUnsupported
}

public enum GitCommitError: Error, Equatable, Sendable {
	case emptySummary
}

public enum GitBranchError: Error, Equatable, Sendable {
	case emptyName
}

public struct GitStashEntry: Equatable, Sendable {
	public var ref: String
	public var date: String
	public var message: String

	public init(ref: String, date: String, message: String) {
		self.ref = ref
		self.date = date
		self.message = message
	}
}

public enum GitStashError: Error, Equatable, Sendable {
	case emptyMessage
	case emptyRef
}

public enum GitStashParser {
	public static func parse(_ output: String) -> [GitStashEntry] {
		output.split(whereSeparator: \.isNewline).compactMap { rawLine in
			let fields = rawLine.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
			guard fields.count == 3, !fields[0].isEmpty else {
				return nil
			}
			return GitStashEntry(ref: fields[0], date: fields[1], message: fields[2])
		}
	}
}

public enum GitBranchParser {
	public static func parse(_ output: String) -> [GitBranch] {
		output.split(separator: "\0", omittingEmptySubsequences: true).compactMap { rawRecord in
			let record = rawRecord.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !record.isEmpty else {
				return nil
			}
			let fields = record.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
			guard fields.count >= 5 else {
				return nil
			}
			let refname = fields[4]
			if refname.hasSuffix("/HEAD") {
				return nil
			}
			return GitBranch(
				name: fields[0],
				upstream: fields[1].isEmpty ? nil : fields[1],
				isCurrent: fields[2] == "*",
				committerDateRelative: fields[3],
				refname: refname,
				kind: refname.hasPrefix("refs/remotes/") ? .remote : .local
			)
		}
	}
}

public enum GitBlameParser {
	public static func parse(_ output: String) -> [GitBlameLine] {
		var lines: [GitBlameLine] = []
		var currentOID = ""
		var currentOriginalLine = 0
		var currentFinalLine = 0
		var author = ""
		var authorEmail = ""
		var authorTime: Date?
		var summary = ""
		var originalPath: String?
		for rawLine in output.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
			if rawLine.hasPrefix("\t") {
				lines.append(GitBlameLine(
					line: currentFinalLine,
					originalLine: currentOriginalLine,
					oid: currentOID,
					summary: summary,
					author: author,
					authorEmail: authorEmail,
					time: authorTime,
					originalPath: originalPath
				))
				continue
			}
			let fields = rawLine.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
			if fields.count >= 3, fields[0].count >= 8, fields[0].allSatisfy(\.isHexDigit) {
				currentOID = fields[0]
				currentOriginalLine = Int(fields[1]) ?? 0
				currentFinalLine = Int(fields[2]) ?? 0
			} else if rawLine.hasPrefix("author ") {
				author = String(rawLine.dropFirst("author ".count))
			} else if rawLine.hasPrefix("author-mail ") {
				authorEmail = String(rawLine.dropFirst("author-mail ".count)).trimmingCharacters(in: CharacterSet(charactersIn: "<>"))
			} else if rawLine.hasPrefix("author-time ") {
				authorTime = Double(rawLine.dropFirst("author-time ".count)).map { Date(timeIntervalSince1970: $0) }
			} else if rawLine.hasPrefix("summary ") {
				summary = String(rawLine.dropFirst("summary ".count))
			} else if rawLine.hasPrefix("filename ") {
				originalPath = String(rawLine.dropFirst("filename ".count))
			}
		}
		return lines
	}
}

public enum GitHistoryParser {
	public static func parse(_ output: String) -> [GitHistoryEntry] {
		output.split(separator: "\0", omittingEmptySubsequences: true).compactMap { rawRecord in
			let record = rawRecord.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !record.isEmpty else {
				return nil
			}
			let fields = record.split(separator: "\u{1f}", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
			guard fields.count == 5 else {
				return nil
			}
			return GitHistoryEntry(
				oid: fields[0],
				author: fields[1],
				authorEmail: fields[2],
				date: Double(fields[3]).map { Date(timeIntervalSince1970: $0) },
				summary: fields[4]
			)
		}
	}
}

public enum GitPullMode: Equatable, Sendable {
	case ffOnly
	case rebase
}

public struct ProcessGitCommandRunner: GitCommandRunning {
	public var executableURL: URL

	public init(executableURL: URL = URL(fileURLWithPath: "/usr/bin/git")) {
		self.executableURL = executableURL
	}

	public func runGit(arguments: [String], root: URL) throws -> String {
		try runGitProcess(arguments: arguments, input: nil, root: root)
	}

	public func runGit(arguments: [String], input: String, root: URL) throws -> String {
		try runGitProcess(arguments: arguments, input: input, root: root)
	}

	private func runGitProcess(arguments: [String], input: String?, root: URL) throws -> String {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		process.currentDirectoryURL = root
		let stdout = Pipe()
		let stderr = Pipe()
		let stdin = input.map { _ in Pipe() }
		process.standardOutput = stdout
		process.standardError = stderr
		if let stdin {
			process.standardInput = stdin
		}
		try process.run()
		if let input, let stdin {
			try stdin.fileHandleForWriting.write(contentsOf: Data(input.utf8))
			try stdin.fileHandleForWriting.close()
		}
		let stdoutBox = GitCommandDataBox()
		let stderrBox = GitCommandDataBox()
		let readers = DispatchGroup()
		read(stdout.fileHandleForReading, into: stdoutBox, group: readers)
		read(stderr.fileHandleForReading, into: stderrBox, group: readers)
		process.waitUntilExit()
		readers.wait()
		let stdoutData = stdoutBox.data
		let stderrData = stderrBox.data
		guard process.terminationStatus == 0 else {
			throw GitCommandError.failed(status: process.terminationStatus, stderr: String(data: stderrData, encoding: .utf8) ?? "")
		}
		guard let output = String(data: stdoutData, encoding: .utf8) else {
			throw GitCommandError.invalidOutput
		}
		return output
	}

	private func read(_ handle: FileHandle, into box: GitCommandDataBox, group: DispatchGroup) {
		group.enter()
		#if DEBUG
		Thread.detachNewThread {
			box.data = handle.readDataToEndOfFile()
			group.leave()
		}
		#else
		DispatchQueue.global(qos: .utility).async {
			box.data = handle.readDataToEndOfFile()
			group.leave()
		}
		#endif
	}
}

private final class GitCommandDataBox: @unchecked Sendable {
	private let lock = NSLock()
	private var storage = Data()

	var data: Data {
		get {
			lock.lock()
			let data = storage
			lock.unlock()
			return data
		}
		set {
			lock.lock()
			storage = newValue
			lock.unlock()
		}
	}
}

public struct GitRepository: Sendable {
	public var root: URL
	public var runner: any GitCommandRunning

	public init(root: URL, runner: any GitCommandRunning = ProcessGitCommandRunner()) {
		self.root = root
		self.runner = runner
	}

	public func status() throws -> GitStatus {
		guard runner is ProcessGitCommandRunner else {
			return try shellStatus()
		}
		return try Libgit2.Repository.open(at: root).gitStatus()
	}

	private func shellStatus() throws -> GitStatus {
		let output = try runner.runGit(arguments: ["status", "--porcelain=v2", "--branch", "--untracked-files=all"], root: root)
		return try GitStatusParser.parse(output)
	}

	public func snapshot() throws -> GitWorkspaceSnapshot {
		GitWorkspaceSnapshot(root: root, status: try status())
	}

	public func branches() throws -> [GitBranch] {
		let output = try runner.runGit(arguments: [
			"for-each-ref",
			"--format=%(refname:short)%09%(upstream:short)%09%(HEAD)%09%(committerdate:relative)%09%(refname)%00",
			"refs/heads",
			"refs/remotes",
		], root: root)
		return GitBranchParser.parse(output)
	}

	public func diff(path: String, staged: Bool = false) throws -> String {
		if runner is ProcessGitCommandRunner {
			let repository = try Libgit2.Repository.open(at: root)
			let diff = try repository.diff(cached: staged, pathspec: [path])
			return try diff.patchText()
		}
		var arguments = ["diff", "--no-color"]
		if staged {
			arguments.append("--cached")
		}
		arguments += ["--", path]
		return try runner.runGit(arguments: arguments, root: root)
	}

	public func diffAgainstHead(path: String) throws -> String {
		try runner.runGit(arguments: ["diff", "--no-color", "HEAD", "--", path], root: root)
	}

	public func diffFiles(path: String, staged: Bool = false) throws -> [DiffFile] {
		try UnifiedDiffParser.parse(diff(path: path, staged: staged))
	}

	public func diffFilesAgainstHead(path: String) throws -> [DiffFile] {
		try UnifiedDiffParser.parse(diffAgainstHead(path: path))
	}

	public func conflictBlob(path: String, stage: Int) throws -> String {
		try runner.runGit(arguments: ["show", ":\(stage):\(path)"], root: root)
	}

	public func stage(paths: [String]) throws {
		guard !paths.isEmpty else {
			return
		}
		_ = try runner.runGit(arguments: ["add", "--"] + paths, root: root)
	}

	public func unstage(paths: [String]) throws {
		guard !paths.isEmpty else {
			return
		}
		_ = try runner.runGit(arguments: ["restore", "--staged", "--"] + paths, root: root)
	}

	public func stage(hunk: DiffHunk, in file: DiffFile) throws {
		try applyCachedPatch(DiffPatchBuilder.patch(file: file, hunk: hunk), reverse: false)
	}

	public func unstage(hunk: DiffHunk, in file: DiffFile) throws {
		try applyCachedPatch(DiffPatchBuilder.patch(file: file, hunk: hunk), reverse: true)
	}

	public func stage(lineIndexes: IndexSet, in hunk: DiffHunk, file: DiffFile) throws {
		let patch = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: lineIndexes, operation: .stage)
		try applyCachedPatch(patch, reverse: false)
	}

	public func unstage(lineIndexes: IndexSet, in hunk: DiffHunk, file: DiffFile) throws {
		let patch = try DiffPatchBuilder.patch(file: file, hunk: hunk, selectedLineIndexes: lineIndexes, operation: .unstage)
		try applyCachedPatch(patch, reverse: true)
	}

	public func switchBranch(_ name: String, stashingDirtyChanges: Bool = false) throws {
		let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			throw GitBranchError.emptyName
		}
		if stashingDirtyChanges {
			try stashForBranch(name)
		}
		_ = try runner.runGit(arguments: ["switch", name], root: root)
		if stashingDirtyChanges {
			try popStash()
		}
	}

	public func createBranch(named name: String, from startPoint: String? = nil, stashingDirtyChanges: Bool = false) throws {
		let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			throw GitBranchError.emptyName
		}
		if stashingDirtyChanges {
			try stashForBranch(name)
		}
		var arguments = ["switch", "-c", name]
		if let startPoint, !startPoint.isEmpty {
			arguments.append(startPoint)
		}
		_ = try runner.runGit(arguments: arguments, root: root)
		if stashingDirtyChanges {
			try popStash()
		}
	}

	public func deleteBranch(_ name: String, force: Bool = false) throws {
		let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			throw GitBranchError.emptyName
		}
		_ = try runner.runGit(arguments: ["branch", force ? "-D" : "-d", name], root: root)
	}

	public func fetchArguments() -> [String] {
		["fetch", "--all", "--prune"]
	}

	public func pullArguments(mode: GitPullMode = .ffOnly) -> [String] {
		switch mode {
		case .ffOnly:
			return ["pull", "--ff-only"]
		case .rebase:
			return ["pull", "--rebase"]
		}
	}

	public func pushArguments() throws -> [String] {
		let branch = try status().branch
		if let head = branch.head, branch.upstream == nil {
			return ["push", "--set-upstream", "origin", head]
		}
		return ["push"]
	}

	public func stashForBranch(_ name: String) throws {
		let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			throw GitBranchError.emptyName
		}
		_ = try runner.runGit(arguments: ["stash", "push", "-u", "-m", "itsy-autostash-\(name)"], root: root)
	}

	public func stashes() throws -> [GitStashEntry] {
		let output = try runner.runGit(arguments: ["stash", "list", "--format=%gd|%ai|%s"], root: root)
		return GitStashParser.parse(output)
	}

	public func stash(message: String) throws {
		let message = message.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !message.isEmpty else {
			throw GitStashError.emptyMessage
		}
		_ = try runner.runGit(arguments: ["stash", "push", "-u", "-m", message], root: root)
	}

	public func applyStash(_ ref: String) throws {
		let ref = try validatedStashRef(ref)
		_ = try runner.runGit(arguments: ["stash", "apply", ref], root: root)
	}

	public func popStash() throws {
		_ = try runner.runGit(arguments: ["stash", "pop"], root: root)
	}

	public func popStash(_ ref: String) throws {
		let ref = try validatedStashRef(ref)
		_ = try runner.runGit(arguments: ["stash", "pop", ref], root: root)
	}

	public func dropStash(_ ref: String) throws {
		let ref = try validatedStashRef(ref)
		_ = try runner.runGit(arguments: ["stash", "drop", ref], root: root)
	}

	public func commit(summary: String, body: String = "", signoff: Bool = false, amend: Bool = false) throws {
		let summary = summary.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !summary.isEmpty else {
			throw GitCommitError.emptySummary
		}
		let body = body.trimmingCharacters(in: .whitespacesAndNewlines)
		if runner is ProcessGitCommandRunner, !signoff, !amend {
			let message = body.isEmpty ? summary : "\(summary)\n\n\(body)"
			_ = try Libgit2.Repository.open(at: root).commit(message: message)
			return
		}
		var arguments = ["commit"]
		if signoff {
			arguments.append("--signoff")
		}
		if amend {
			arguments.append("--amend")
		}
		arguments += ["-m", summary]
		if !body.isEmpty {
			arguments += ["-m", body]
		}
		_ = try runner.runGit(arguments: arguments, root: root)
	}

	public func recentCommitMessages(limit: Int = 10) throws -> [String] {
		let output = try runner.runGit(arguments: ["log", "-\(max(1, limit))", "--format=%B%x00"], root: root)
		return output.split(separator: "\0", omittingEmptySubsequences: true)
			.map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
			.filter { !$0.isEmpty }
	}

	public func blame(path: String) throws -> [GitBlameLine] {
		if runner is ProcessGitCommandRunner {
			return try Libgit2.Repository.open(at: root).blame(path: path)
		}
		let output = try runner.runGit(arguments: ["blame", "--line-porcelain", "--", path], root: root)
		return GitBlameParser.parse(output)
	}

	public func fileHistory(path: String, limit: Int = 50) throws -> [GitHistoryEntry] {
		let output = try runner.runGit(arguments: ["log", "-\(max(1, limit))", "--format=\(Self.historyFormat)", "--", path], root: root)
		return GitHistoryParser.parse(output)
	}

	public func lineHistory(path: String, line: Int, limit: Int = 50) throws -> [GitHistoryEntry] {
		let line = max(1, line)
		let output = try runner.runGit(arguments: [
			"log",
			"-\(max(1, limit))",
			"--format=\(Self.historyFormat)",
			"--no-patch",
			"-L",
			"\(line),\(line):\(path)",
		], root: root)
		return GitHistoryParser.parse(output)
	}

	private func validatedStashRef(_ ref: String) throws -> String {
		let ref = ref.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !ref.isEmpty else {
			throw GitStashError.emptyRef
		}
		return ref
	}

	private func applyCachedPatch(_ patch: String, reverse: Bool) throws {
		var checkArguments = ["apply", "--cached", "--check"]
		if reverse {
			checkArguments.append("--reverse")
		}
		checkArguments.append("-")
		_ = try runner.runGit(arguments: checkArguments, input: patch, root: root)
		var applyArguments = ["apply", "--cached"]
		if reverse {
			applyArguments.append("--reverse")
		}
		applyArguments.append("-")
		_ = try runner.runGit(arguments: applyArguments, input: patch, root: root)
	}

	public static func discoverRoot(containing url: URL, runner: any GitCommandRunning = ProcessGitCommandRunner()) throws -> URL {
		let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
		let root = values?.isDirectory == true ? url : url.deletingLastPathComponent()
		let output = try runner.runGit(arguments: ["rev-parse", "--show-toplevel"], root: root)
		return URL(fileURLWithPath: output.trimmingCharacters(in: .whitespacesAndNewlines), isDirectory: true)
	}

	private static let historyFormat = "%H%x1f%an%x1f%ae%x1f%at%x1f%s%x00"
}
