import Foundation

public enum IndentationDetector {
	public static func indentationUnit(in text: String, fallback: String) -> String {
		var tabLines = 0
		var spaceWidths: [Int] = []
		for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
			let indentation = line.prefix { $0 == " " || $0 == "\t" }
			guard indentation.count < line.count else {
				continue
			}
			if indentation.contains("\t") {
				tabLines += 1
			} else if !indentation.isEmpty {
				spaceWidths.append(indentation.count)
			}
		}
		guard tabLines != spaceWidths.count else {
			return fallback
		}
		if tabLines > spaceWidths.count {
			return "\t"
		}
		guard let first = spaceWidths.first else {
			return fallback
		}
		let width = spaceWidths.dropFirst().reduce(first, greatestCommonDivisor)
		return String(repeating: " ", count: min(max(width, 1), 16))
	}

	private static func greatestCommonDivisor(_ lhs: Int, _ rhs: Int) -> Int {
		var lhs = abs(lhs)
		var rhs = abs(rhs)
		while rhs != 0 {
			(lhs, rhs) = (rhs, lhs % rhs)
		}
		return lhs
	}
}
