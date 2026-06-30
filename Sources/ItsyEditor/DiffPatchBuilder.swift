import Foundation

public enum DiffPatchBuilder {
	public static func patch(file: DiffFile, hunk: DiffHunk) -> String {
		var lines: [String] = []
		let oldPath = file.oldPath ?? file.newPath ?? "dev/null"
		let newPath = file.newPath ?? file.oldPath ?? "dev/null"
		lines.append("diff --git a/\(oldPath) b/\(newPath)")
		if file.isNewFile, let mode = file.newMode {
			lines.append("new file mode \(mode)")
		}
		if file.isDeletedFile, let mode = file.oldMode {
			lines.append("deleted file mode \(mode)")
		}
		if let indexLine = file.indexLine {
			lines.append(indexLine)
		}
		lines.append("--- \(file.oldPath.map { "a/\($0)" } ?? "/dev/null")")
		lines.append("+++ \(file.newPath.map { "b/\($0)" } ?? "/dev/null")")
		lines.append("@@ -\(rangeText(start: hunk.oldStart, count: hunk.oldCount)) +\(rangeText(start: hunk.newStart, count: hunk.newCount)) @@")
		for line in hunk.lines {
			switch line {
			case .context(let content):
				lines.append(" \(content)")
			case .add(let content):
				lines.append("+\(content)")
			case .remove(let content):
				lines.append("-\(content)")
			}
		}
		return lines.joined(separator: "\n") + "\n"
	}

	private static func rangeText(start: Int, count: Int) -> String {
		count == 1 ? "\(start)" : "\(start),\(count)"
	}
}
