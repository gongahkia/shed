import Foundation

struct TerminalPasteRisk: Equatable {
	var lineCount: Int
	var containsControlCharacters: Bool

	var requiresConfirmation: Bool {
		lineCount > 1 || containsControlCharacters
	}
}

enum TerminalPastePolicy {
	static func risk(for text: String) -> TerminalPasteRisk {
		let normalized = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
		let lineCount = 1 + normalized.unicodeScalars.filter { $0.value == 0x0A }.count
		let containsControlCharacters = text.unicodeScalars.contains { scalar in
			scalar.value < 0x20 && scalar.value != 0x09 && scalar.value != 0x0A && scalar.value != 0x0D || scalar.value == 0x7F
		}
		return TerminalPasteRisk(lineCount: lineCount, containsControlCharacters: containsControlCharacters)
	}
}

struct TerminalOpenLocation: Equatable {
	var url: URL
	var line: Int?
	var column: Int?

	var isFile: Bool {
		url.isFileURL
	}
}

enum TerminalLinkDetector {
	static func locations(in text: String, relativeTo directory: URL?) -> [TerminalOpenLocation] {
		candidates(in: text, relativeTo: directory).map(\.location)
	}

	static func location(in text: String, column: Int, relativeTo directory: URL?) -> TerminalOpenLocation? {
		let index = max(0, min(column, (text as NSString).length - 1))
		return candidates(in: text, relativeTo: directory).first { NSLocationInRange(index, $0.range) }?.location
	}

	private static func candidates(in text: String, relativeTo directory: URL?) -> [Candidate] {
		var values: [Candidate] = []
		let fullRange = NSRange(text.startIndex ..< text.endIndex, in: text)
		for match in urlExpression.matches(in: text, range: fullRange) {
			guard let range = Range(match.range, in: text) else {
				continue
			}
			let rawURL = String(text[range]).trimmingCharacters(in: CharacterSet(charactersIn: ".,;:"))
			guard let url = URL(string: rawURL), let range = rawURLRange(rawURL, in: text, matchRange: match.range) else {
				continue
			}
			values.append(Candidate(range: range, location: TerminalOpenLocation(url: url, line: nil, column: nil)))
		}
		for match in fileExpression.matches(in: text, range: fullRange) {
			guard let pathRange = Range(match.range(at: 1), in: text),
			      let lineRange = Range(match.range(at: 2), in: text),
			      let line = Int(text[lineRange]), line > 0
			else {
				continue
			}
			let path = String(text[pathRange])
			let url: URL
			if path.hasPrefix("/") {
				url = URL(fileURLWithPath: path)
			} else if let directory {
				url = directory.appendingPathComponent(path).standardizedFileURL
			} else {
				continue
			}
			let column = match.range(at: 3).location == NSNotFound ? nil : Range(match.range(at: 3), in: text).flatMap { Int(text[$0]) }
			values.append(Candidate(range: match.range(at: 1), location: TerminalOpenLocation(url: url, line: line, column: column)))
		}
		return values.sorted { $0.range.location < $1.range.location }
	}

	private static func rawURLRange(_ rawURL: String, in text: String, matchRange: NSRange) -> NSRange? {
		guard let range = Range(matchRange, in: text), let start = text.range(of: rawURL, range: range)?.lowerBound else {
			return nil
		}
		return NSRange(start ..< text.index(start, offsetBy: rawURL.count), in: text)
	}

	private struct Candidate {
		var range: NSRange
		var location: TerminalOpenLocation
	}

	private static let urlExpression = try! NSRegularExpression(pattern: "(?:https?|file)://[^\\s<>()]+")
	private static let fileExpression = try! NSRegularExpression(
		pattern: "(?:^|[\\s\\(\\[\\\"'])((?:/|\\./|\\.\\./)[^\\n]*?):(\\d+)(?::(\\d+))?(?=$|[\\s\\]\\)\\\"',])"
	)
}
