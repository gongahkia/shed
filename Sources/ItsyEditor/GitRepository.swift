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
}

public enum GitCommandError: Error, Equatable, Sendable {
	case failed(status: Int32, stderr: String)
	case invalidOutput
}

public struct ProcessGitCommandRunner: GitCommandRunning {
	public var executableURL: URL

	public init(executableURL: URL = URL(fileURLWithPath: "/usr/bin/git")) {
		self.executableURL = executableURL
	}

	public func runGit(arguments: [String], root: URL) throws -> String {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		process.currentDirectoryURL = root
		let stdout = Pipe()
		let stderr = Pipe()
		process.standardOutput = stdout
		process.standardError = stderr
		try process.run()
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
		DispatchQueue.global(qos: .utility).async {
			box.data = handle.readDataToEndOfFile()
			group.leave()
		}
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
		let output = try runner.runGit(arguments: ["status", "--porcelain=v2", "--branch", "--untracked-files=all"], root: root)
		return try GitStatusParser.parse(output)
	}

	public func diff(path: String, staged: Bool = false) throws -> String {
		var arguments = ["diff"]
		if staged {
			arguments.append("--cached")
		}
		arguments += ["--", path]
		return try runner.runGit(arguments: arguments, root: root)
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
}
