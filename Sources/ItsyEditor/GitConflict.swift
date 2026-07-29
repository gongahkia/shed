import Foundation

public struct GitConflictRegion: Equatable, Sendable {
	public var startLine: Int
	public var endLine: Int
	public var oursLines: [String]
	public var theirsLines: [String]

	public init(startLine: Int, endLine: Int, oursLines: [String], theirsLines: [String]) {
		self.startLine = startLine
		self.endLine = endLine
		self.oursLines = oursLines
		self.theirsLines = theirsLines
	}

	public var oursText: String {
		oursLines.joined(separator: "\n")
	}

	public var theirsText: String {
		theirsLines.joined(separator: "\n")
	}
}

public enum GitConflictResolution: Equatable, Sendable {
	case ours
	case theirs
	case both
}

public enum GitConflictParser {
	public static func hasUnresolvedMarkers(_ text: String) -> Bool {
		let markerPrefixes = ["<<<<<<<", "|||||||", "=======", ">>>>>>>"]
		return splitLines(text).contains { line in
			markerPrefixes.contains { line.hasPrefix($0) }
		}
	}

	public static func parse(_ text: String) -> [GitConflictRegion] {
		let lines = splitLines(text)
		var regions: [GitConflictRegion] = []
		var index = 0
		while index < lines.count {
			guard lines[index].hasPrefix("<<<<<<<") else {
				index += 1
				continue
			}
			let startLine = index
			index += 1
			let oursStart = index
			while index < lines.count, !lines[index].hasPrefix("|||||||"), !lines[index].hasPrefix("=======") {
				index += 1
			}
			let oursEnd = index
			if index < lines.count, lines[index].hasPrefix("|||||||") {
				index += 1
				while index < lines.count, !lines[index].hasPrefix("=======") {
					index += 1
				}
			}
			guard index < lines.count, lines[index].hasPrefix("=======") else {
				continue
			}
			index += 1
			let theirsStart = index
			while index < lines.count, !lines[index].hasPrefix(">>>>>>>") {
				index += 1
			}
			guard index < lines.count else {
				continue
			}
			let theirsEnd = index
			let endLine = index + 1
			regions.append(GitConflictRegion(
				startLine: startLine,
				endLine: endLine,
				oursLines: Array(lines[oursStart ..< oursEnd]),
				theirsLines: Array(lines[theirsStart ..< theirsEnd])
			))
			index = endLine
		}
		return regions
	}

	public static func resolvedText(_ text: String, regionIndex: Int, resolution: GitConflictResolution) -> String {
		var lines = splitLines(text)
		let regions = parse(text)
		guard regionIndex >= 0, regionIndex < regions.count else {
			return text
		}
		let region = regions[regionIndex]
		let replacement: [String]
		switch resolution {
		case .ours:
			replacement = region.oursLines
		case .theirs:
			replacement = region.theirsLines
		case .both:
			replacement = region.oursLines + region.theirsLines
		}
		lines.replaceSubrange(region.startLine ..< region.endLine, with: replacement)
		return lines.joined(separator: "\n")
	}

	private static func splitLines(_ text: String) -> [String] {
		text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
	}
}
