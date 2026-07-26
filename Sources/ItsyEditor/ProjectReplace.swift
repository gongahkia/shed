import Foundation

public struct ProjectReplaceOptions: Sendable, Equatable {
	public var query: String
	public var replacement: String
	public var matchCase: Bool
	public var maxFileBytes: Int

	public init(query: String, replacement: String, matchCase: Bool = false, maxFileBytes: Int = 1_000_000) {
		self.query = query
		self.replacement = replacement
		self.matchCase = matchCase
		self.maxFileBytes = maxFileBytes
	}
}

public struct ProjectReplaceFilePreview: Equatable, Sendable {
	public let url: URL
	public let relativePath: String
	public let replacementCount: Int
	fileprivate let originalData: Data
	fileprivate let replacementData: Data
}

public struct ProjectReplacePreview: Equatable, Sendable {
	public let root: URL
	public let options: ProjectReplaceOptions
	public let files: [ProjectReplaceFilePreview]

	public var replacementCount: Int {
		files.reduce(into: 0) { $0 += $1.replacementCount }
	}
}

public struct ProjectReplaceApplyResult: Equatable, Sendable {
	public let replacementCount: Int
	public let fileCount: Int
	public let recoveryJournalURL: URL?
}

public enum ProjectReplaceError: Error, Equatable {
	case stalePreview(URL)
	case failedToCreateJournal(URL)
	case writeFailed(URL)
	case recoveryJournal(URL)
}

public struct ProjectReplaceIO {
	public var read: (URL) throws -> Data
	public var write: (Data, URL) throws -> Void
	public var createDirectory: (URL) throws -> Void
	public var remove: (URL) throws -> Void

	public init(
		read: @escaping (URL) throws -> Data,
		write: @escaping (Data, URL) throws -> Void,
		createDirectory: @escaping (URL) throws -> Void,
		remove: @escaping (URL) throws -> Void
	) {
		self.read = read
		self.write = write
		self.createDirectory = createDirectory
		self.remove = remove
	}

	public static let live = ProjectReplaceIO(
		read: { try Data(contentsOf: $0, options: .mappedIfSafe) },
		write: { try $0.write(to: $1, options: .atomic) },
		createDirectory: { try FileManager.default.createDirectory(at: $0, withIntermediateDirectories: true) },
		remove: { try FileManager.default.removeItem(at: $0) }
	)
}

public enum ProjectReplace {
	public static func preview(root: URL, options: ProjectReplaceOptions, fileManager: FileManager = .default) -> ProjectReplacePreview {
		guard !options.query.isEmpty else {
			return ProjectReplacePreview(root: root, options: options, files: [])
		}
		let matcher = GitIgnoreMatcher(root: root, fileManager: fileManager)
		let files = ProjectFind.searchableFiles(root: root, matcher: matcher, maxFileBytes: options.maxFileBytes, fileManager: fileManager)
		let previews = files.compactMap { previewFile($0, root: root, options: options) }
		return ProjectReplacePreview(root: root, options: options, files: previews)
	}

	public static func apply(
		_ preview: ProjectReplacePreview,
		journalDirectory: URL? = nil,
		io: ProjectReplaceIO = .live
	) throws -> ProjectReplaceApplyResult {
		guard !preview.files.isEmpty else {
			return ProjectReplaceApplyResult(replacementCount: 0, fileCount: 0, recoveryJournalURL: nil)
		}
		for file in preview.files {
			guard try io.read(file.url) == file.originalData else {
				throw ProjectReplaceError.stalePreview(file.url)
			}
		}
		let directory = journalDirectory ?? preview.root.appendingPathComponent(".itsy", isDirectory: true)
		let journalURL = directory.appendingPathComponent("project-replace-\(UUID().uuidString).json")
		let journal = ProjectReplaceJournal(files: preview.files.map(ProjectReplaceJournal.File.init))
		do {
			try io.createDirectory(directory)
			try io.write(try JSONEncoder().encode(journal), journalURL)
		} catch {
			throw ProjectReplaceError.failedToCreateJournal(journalURL)
		}
		var written: [ProjectReplaceFilePreview] = []
		for file in preview.files {
			do {
				try io.write(file.replacementData, file.url)
				written.append(file)
			} catch {
				let rollbackSucceeded = written.reversed().allSatisfy { changed in
					(try? io.write(changed.originalData, changed.url)) != nil
				}
				guard rollbackSucceeded else {
					throw ProjectReplaceError.recoveryJournal(journalURL)
				}
				try? io.remove(journalURL)
				throw ProjectReplaceError.writeFailed(file.url)
			}
		}
		do {
			try io.remove(journalURL)
			return ProjectReplaceApplyResult(
				replacementCount: preview.replacementCount,
				fileCount: preview.files.count,
				recoveryJournalURL: nil
			)
		} catch {
			return ProjectReplaceApplyResult(
				replacementCount: preview.replacementCount,
				fileCount: preview.files.count,
				recoveryJournalURL: journalURL
			)
		}
	}

	public static func recover(journalURL: URL, io: ProjectReplaceIO = .live) throws {
		let journal = try JSONDecoder().decode(ProjectReplaceJournal.self, from: io.read(journalURL))
		for file in journal.files {
			try io.write(file.originalData, URL(fileURLWithPath: file.path))
		}
		try io.remove(journalURL)
	}

	private static func previewFile(_ url: URL, root: URL, options: ProjectReplaceOptions) -> ProjectReplaceFilePreview? {
		guard let originalData = try? Data(contentsOf: url, options: .mappedIfSafe),
		      !originalData.contains(0),
		      let original = String(data: originalData, encoding: .utf8)
		else {
			return nil
		}
		let compareOptions: String.CompareOptions = options.matchCase ? [] : [.caseInsensitive]
		let count = matchCount(in: original, query: options.query, options: compareOptions)
		guard count > 0 else {
			return nil
		}
		let replacement = original.replacingOccurrences(of: options.query, with: options.replacement, options: compareOptions)
		return ProjectReplaceFilePreview(
			url: url,
			relativePath: ProjectFind.relativePath(for: url, root: root),
			replacementCount: count,
			originalData: originalData,
			replacementData: Data(replacement.utf8)
		)
	}

	private static func matchCount(in text: String, query: String, options: String.CompareOptions) -> Int {
		var count = 0
		var start = text.startIndex
		while start < text.endIndex, let range = text.range(of: query, options: options, range: start ..< text.endIndex) {
			count += 1
			start = range.upperBound
		}
		return count
	}
}

private struct ProjectReplaceJournal: Codable {
	struct File: Codable {
		let path: String
		let originalData: Data

		init(_ preview: ProjectReplaceFilePreview) {
			path = preview.url.path
			originalData = preview.originalData
		}
	}

	let files: [File]
}
