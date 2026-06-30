import Foundation

public enum RenderedDiffLineKind: Equatable, Sendable {
	case header
	case context
	case addition
	case removal
	case blank
}

public struct RenderedDiffLine: Equatable, Sendable {
	public var kind: RenderedDiffLineKind
	public var fullRange: Range<Int>
	public var contentRange: Range<Int>?
	public var content: String?

	public init(kind: RenderedDiffLineKind, fullRange: Range<Int>, contentRange: Range<Int>? = nil, content: String? = nil) {
		self.kind = kind
		self.fullRange = fullRange
		self.contentRange = contentRange
		self.content = content
	}
}

public struct RenderedDiffDocument: Equatable, Sendable {
	public var text: String
	public var lines: [RenderedDiffLine]

	public init(text: String, lines: [RenderedDiffLine]) {
		self.text = text
		self.lines = lines
	}
}

public struct RenderedSideBySideDiff: Equatable, Sendable {
	public var old: RenderedDiffDocument
	public var new: RenderedDiffDocument

	public init(old: RenderedDiffDocument, new: RenderedDiffDocument) {
		self.old = old
		self.new = new
	}
}

public enum DiffTextRenderer {
	public static func unified(files: [DiffFile]) -> RenderedDiffDocument {
		var builder = DiffDocumentBuilder()
		for (fileIndex, file) in files.enumerated() {
			if fileIndex > 0 {
				builder.append(kind: .blank, line: "")
			}
			let oldPath = file.oldPath ?? "/dev/null"
			let newPath = file.newPath ?? "/dev/null"
			builder.append(kind: .header, line: "diff --git a/\(oldPath) b/\(newPath)")
			if file.isNewFile, let mode = file.newMode {
				builder.append(kind: .header, line: "new file mode \(mode)")
			}
			if file.isDeletedFile, let mode = file.oldMode {
				builder.append(kind: .header, line: "deleted file mode \(mode)")
			}
			builder.append(kind: .header, line: "--- \(file.oldPath.map { "a/\($0)" } ?? "/dev/null")")
			builder.append(kind: .header, line: "+++ \(file.newPath.map { "b/\($0)" } ?? "/dev/null")")
			for hunk in file.hunks {
				builder.append(kind: .header, line: hunkHeader(hunk))
				for line in hunk.lines {
					switch line {
					case .context(let content):
						builder.append(kind: .context, prefix: " ", content: content)
					case .add(let content):
						builder.append(kind: .addition, prefix: "+", content: content)
					case .remove(let content):
						builder.append(kind: .removal, prefix: "-", content: content)
					}
				}
			}
		}
		return builder.finish()
	}

	public static func sideBySide(files: [DiffFile]) -> RenderedSideBySideDiff {
		var oldBuilder = DiffDocumentBuilder()
		var newBuilder = DiffDocumentBuilder()
		let width = lineNumberWidth(files: files)
		for (fileIndex, file) in files.enumerated() {
			if fileIndex > 0 {
				oldBuilder.append(kind: .blank, line: "")
				newBuilder.append(kind: .blank, line: "")
			}
			oldBuilder.append(kind: .header, line: "--- \(file.oldPath ?? "/dev/null")")
			newBuilder.append(kind: .header, line: "+++ \(file.newPath ?? "/dev/null")")
			for hunk in file.hunks {
				let header = hunkHeader(hunk)
				oldBuilder.append(kind: .header, line: header)
				newBuilder.append(kind: .header, line: header)
				appendSideBySide(hunk: hunk, width: width, old: &oldBuilder, new: &newBuilder)
			}
		}
		return RenderedSideBySideDiff(old: oldBuilder.finish(), new: newBuilder.finish())
	}

	public static func newFile(path: String, contents: String) -> DiffFile {
		var lines = contents.components(separatedBy: "\n")
		if contents.hasSuffix("\n") {
			lines.removeLast()
		}
		let hunk = DiffHunk(
			oldStart: 0,
			oldCount: 0,
			newStart: 1,
			newCount: lines.count,
			lines: lines.map(DiffLine.add)
		)
		return DiffFile(oldPath: nil, newPath: path, newMode: "100644", isNewFile: true, hunks: lines.isEmpty ? [] : [hunk])
	}

	private static func appendSideBySide(hunk: DiffHunk, width: Int, old: inout DiffDocumentBuilder, new: inout DiffDocumentBuilder) {
		var index = 0
		var oldLine = hunk.oldStart
		var newLine = hunk.newStart
		while index < hunk.lines.count {
			switch hunk.lines[index] {
			case .context(let content):
				old.append(kind: .context, prefix: sidePrefix(number: oldLine, width: width, marker: " "), content: content)
				new.append(kind: .context, prefix: sidePrefix(number: newLine, width: width, marker: " "), content: content)
				oldLine += 1
				newLine += 1
				index += 1
			case .remove:
				let start = index
				while index < hunk.lines.count, case .remove = hunk.lines[index] {
					index += 1
				}
				let removes = hunk.lines[start ..< index].compactMap { line -> String? in
					if case .remove(let content) = line {
						return content
					}
					return nil
				}
				let addStart = index
				while index < hunk.lines.count, case .add = hunk.lines[index] {
					index += 1
				}
				let adds = hunk.lines[addStart ..< index].compactMap { line -> String? in
					if case .add(let content) = line {
						return content
					}
					return nil
				}
				let rowCount = max(removes.count, adds.count)
				for row in 0 ..< rowCount {
					if row < removes.count {
						old.append(kind: .removal, prefix: sidePrefix(number: oldLine, width: width, marker: "-"), content: removes[row])
						oldLine += 1
					} else {
						old.append(kind: .blank, line: sidePrefix(number: nil, width: width, marker: " "))
					}
					if row < adds.count {
						new.append(kind: .addition, prefix: sidePrefix(number: newLine, width: width, marker: "+"), content: adds[row])
						newLine += 1
					} else {
						new.append(kind: .blank, line: sidePrefix(number: nil, width: width, marker: " "))
					}
				}
			case .add(let content):
				old.append(kind: .blank, line: sidePrefix(number: nil, width: width, marker: " "))
				new.append(kind: .addition, prefix: sidePrefix(number: newLine, width: width, marker: "+"), content: content)
				newLine += 1
				index += 1
			}
		}
	}

	private static func hunkHeader(_ hunk: DiffHunk) -> String {
		"@@ -\(rangeText(start: hunk.oldStart, count: hunk.oldCount)) +\(rangeText(start: hunk.newStart, count: hunk.newCount)) @@"
	}

	private static func rangeText(start: Int, count: Int) -> String {
		count == 1 ? "\(start)" : "\(start),\(count)"
	}

	private static func sidePrefix(number: Int?, width: Int, marker: String) -> String {
		let value = number.map(String.init) ?? ""
		return "\(String(repeating: " ", count: max(0, width - value.count)))\(value) \(marker) "
	}

	private static func lineNumberWidth(files: [DiffFile]) -> Int {
		let maximum = files.flatMap(\.hunks).reduce(0) { current, hunk in
			max(current, hunk.oldStart + max(0, hunk.oldCount - 1), hunk.newStart + max(0, hunk.newCount - 1))
		}
		return max(1, String(maximum).count)
	}
}

private struct DiffDocumentBuilder {
	private var text = ""
	private var lines: [RenderedDiffLine] = []

	mutating func append(kind: RenderedDiffLineKind, line: String) {
		let start = text.utf8.count
		text += line
		let end = text.utf8.count
		lines.append(RenderedDiffLine(kind: kind, fullRange: start ..< end))
		text += "\n"
	}

	mutating func append(kind: RenderedDiffLineKind, prefix: String, content: String) {
		let start = text.utf8.count
		text += prefix
		let contentStart = text.utf8.count
		text += content
		let end = text.utf8.count
		lines.append(RenderedDiffLine(kind: kind, fullRange: start ..< end, contentRange: contentStart ..< end, content: content))
		text += "\n"
	}

	func finish() -> RenderedDiffDocument {
		RenderedDiffDocument(text: text, lines: lines)
	}
}
