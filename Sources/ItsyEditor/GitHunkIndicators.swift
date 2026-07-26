import Foundation

public enum GitHunkIndicatorKind: Equatable, Sendable {
	case added
	case modified
	case deleted
}

public struct GitHunkIndicator: Equatable, Sendable {
	public var line: Int
	public var kind: GitHunkIndicatorKind

	public init(line: Int, kind: GitHunkIndicatorKind) {
		self.line = line
		self.kind = kind
	}
}

public enum GitHunkIndicatorBuilder {
	public static func indicators(files: [DiffFile]) -> [GitHunkIndicator] {
		files.flatMap { file in
			file.hunks.flatMap(indicators(in:))
		}
	}

	private static func indicators(in hunk: DiffHunk) -> [GitHunkIndicator] {
		var indicators: [GitHunkIndicator] = []
		var newLine = hunk.newCount == 0 ? hunk.newStart + 1 : hunk.newStart
		var index = 0
		while index < hunk.lines.count {
			switch hunk.lines[index] {
			case .context:
				newLine += 1
				index += 1
			case .add:
				let addStart = index
				while index < hunk.lines.count, hunk.lines[index].isAddition {
					indicators.append(GitHunkIndicator(line: max(0, newLine - 1), kind: .added))
					newLine += 1
					index += 1
				}
				if addStart == index {
					index += 1
				}
			case .remove:
				let removeStart = index
				while index < hunk.lines.count, hunk.lines[index].isRemoval {
					index += 1
				}
				let removeCount = index - removeStart
				let addStart = index
				while index < hunk.lines.count, hunk.lines[index].isAddition {
					index += 1
				}
				let addCount = index - addStart
				if addCount > 0 {
					let pairedCount = min(removeCount, addCount)
					for offset in 0 ..< pairedCount {
						indicators.append(GitHunkIndicator(line: max(0, newLine + offset - 1), kind: .modified))
					}
					if addCount > pairedCount {
						for offset in pairedCount ..< addCount {
							indicators.append(GitHunkIndicator(line: max(0, newLine + offset - 1), kind: .added))
						}
					}
					if removeCount > pairedCount {
						for _ in pairedCount ..< removeCount {
							indicators.append(GitHunkIndicator(line: max(0, newLine - 1), kind: .deleted))
						}
					}
				} else {
					for _ in 0 ..< removeCount {
						indicators.append(GitHunkIndicator(line: max(0, newLine - 1), kind: .deleted))
					}
				}
				newLine += addCount
			}
		}
		return indicators
	}
}

private extension DiffLine {
	var isAddition: Bool {
		if case .add = self {
			return true
		}
		return false
	}

	var isRemoval: Bool {
		if case .remove = self {
			return true
		}
		return false
	}
}
