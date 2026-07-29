import Foundation
import ItsyDAP

public typealias SourceBreakpoint = DAPSourceBreakpoint

public actor BreakpointStore {
	private struct FileRecord: Codable, Equatable {
		var url: String
		var breakpoints: [SourceBreakpoint]
	}

	private struct BreakpointFile: Codable, Equatable {
		var files: [FileRecord]
	}

	private let fileURL: URL
	private let fileManager: FileManager
	private var breakpointsByURL: [URL: [SourceBreakpoint]] = [:]

	public init(fileURL: URL = BreakpointStore.defaultFileURL, fileManager: FileManager = .default) {
		self.fileURL = fileURL
		self.fileManager = fileManager
	}

	public static var defaultFileURL: URL {
		FileManager.default.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("breakpoints.json")
	}

	public func load() throws {
		guard fileManager.fileExists(atPath: fileURL.path) else {
			breakpointsByURL = [:]
			return
		}
		let data = try Data(contentsOf: fileURL)
		let file = try JSONDecoder().decode(BreakpointFile.self, from: data)
		var loaded: [URL: [SourceBreakpoint]] = [:]
		for record in file.files {
			guard let url = URL(string: record.url) else {
				continue
			}
			loaded[canonical(url)] = record.breakpoints.sorted(by: breakpointOrder)
		}
		breakpointsByURL = loaded
	}

	public func save() throws {
		let records = breakpointsByURL
			.filter { !$0.value.isEmpty }
			.map { url, breakpoints in
				FileRecord(url: url.absoluteString, breakpoints: breakpoints.sorted(by: breakpointOrder))
			}
			.sorted { $0.url < $1.url }
		let file = BreakpointFile(files: records)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		let data = try encoder.encode(file)
		try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
		try data.write(to: fileURL, options: .atomic)
	}

	public func breakpoints(for url: URL) -> [SourceBreakpoint] {
		breakpointsByURL[canonical(url), default: []]
	}

	public func replace(_ breakpoints: [SourceBreakpoint], for url: URL) {
		let key = canonical(url)
		let sorted = breakpoints.sorted(by: breakpointOrder)
		if sorted.isEmpty {
			breakpointsByURL.removeValue(forKey: key)
		} else {
			breakpointsByURL[key] = sorted
		}
	}

	@discardableResult
	public func toggle(line: Int, in url: URL) -> Bool {
		let key = canonical(url)
		var breakpoints = breakpointsByURL[key, default: []]
		if let index = breakpoints.firstIndex(where: { $0.line == line }) {
			breakpoints.remove(at: index)
			replace(breakpoints, for: key)
			return false
		}
		breakpoints.append(SourceBreakpoint(line: line))
		replace(breakpoints, for: key)
		return true
	}

	public func remove(line: Int, in url: URL) {
		let key = canonical(url)
		let filtered = breakpointsByURL[key, default: []].filter { $0.line != line }
		replace(filtered, for: key)
	}

	public func snapshot() -> [URL: [SourceBreakpoint]] {
		breakpointsByURL
	}

	private func canonical(_ url: URL) -> URL {
		url.standardizedFileURL
	}
}

private func breakpointOrder(_ lhs: SourceBreakpoint, _ rhs: SourceBreakpoint) -> Bool {
	if lhs.line != rhs.line {
		return lhs.line < rhs.line
	}
	return (lhs.column ?? 0) < (rhs.column ?? 0)
}
