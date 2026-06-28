import Foundation

enum ConfigSourcePreflight {
    static func compilerOutput(sourceURL: URL, source: String) -> String? {
        let findings = duplicateTagFindings(in: source) + duplicateChordFindings(in: source)
        guard !findings.isEmpty else {
            return nil
        }
        return findings
            .map { "\(sourceURL.path):\($0.line):\($0.column): error: \($0.message)" }
            .joined(separator: "\n")
    }

    private static func duplicateTagFindings(in source: String) -> [Finding] {
        let patterns = [
            #"Tag\.named\s*\(\s*"((?:\\.|[^"\\])*)"\s*\)"#,
            #"NamedTagDeclaration\.raw\s*\(\s*"((?:\\.|[^"\\])*)""#
        ]
        let occurrences = patterns.flatMap { matches(pattern: $0, in: source, value: 1) }
        return duplicateFindings(occurrences: occurrences) { value, firstLine in
            "duplicate-tag-name: \"\(value)\" was already declared at line \(firstLine)"
        }
    }

    private static func duplicateChordFindings(in source: String) -> [Finding] {
        let pattern = #"Keybind(?:\.raw)?\s*\(\s*KeyChord\s*\(\s*\[([^\]]*)\]\s*,\s*\.([A-Za-z_][A-Za-z0-9_]*)\s*\)"#
        let occurrences = matches(pattern: pattern, in: source, value: 0).compactMap { occurrence -> Occurrence? in
            guard let match = occurrence.match,
                  let modifiersRange = Range(match.range(at: 1), in: source),
                  let keyRange = Range(match.range(at: 2), in: source) else {
                return nil
            }
            let modifiers = String(source[modifiersRange])
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).trimmingPrefix(".") }
                .filter { !$0.isEmpty }
                .sorted()
            let key = String(source[keyRange])
            let value = (modifiers + [key]).joined(separator: "+")
            return Occurrence(value: value, line: occurrence.line, column: occurrence.column, match: nil)
        }
        return duplicateFindings(occurrences: occurrences) { value, firstLine in
            "duplicate-chord: \(value) was already declared at line \(firstLine)"
        }
    }

    private static func duplicateFindings(
        occurrences: [Occurrence],
        message: (String, Int) -> String
    ) -> [Finding] {
        var firstByValue: [String: Occurrence] = [:]
        var findings: [Finding] = []
        for occurrence in occurrences {
            if let first = firstByValue[occurrence.value] {
                findings.append(Finding(
                    line: occurrence.line,
                    column: occurrence.column,
                    message: message(occurrence.value, first.line)
                ))
            } else {
                firstByValue[occurrence.value] = occurrence
            }
        }
        return findings
    }

    private static func matches(pattern: String, in source: String, value group: Int) -> [Occurrence] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else {
            return []
        }
        let range = NSRange(source.startIndex..<source.endIndex, in: source)
        return regex.matches(in: source, range: range).compactMap { match in
            guard let matchRange = Range(match.range(at: 0), in: source),
                  let valueRange = Range(match.range(at: group), in: source) else {
                return nil
            }
            let location = location(of: matchRange.lowerBound, in: source)
            return Occurrence(
                value: String(source[valueRange]),
                line: location.line,
                column: location.column,
                match: match
            )
        }
    }

    private static func location(of index: String.Index, in source: String) -> SourceLocation {
        let prefix = source[..<index]
        let line = prefix.reduce(1) { count, character in
            character == "\n" ? count + 1 : count
        }
        let lineStart = prefix.lastIndex(of: "\n").map { source.index(after: $0) } ?? source.startIndex
        return SourceLocation(line: line, column: source.distance(from: lineStart, to: index) + 1)
    }
}

private struct Finding {
    let line: Int
    let column: Int
    let message: String
}

private struct Occurrence {
    let value: String
    let line: Int
    let column: Int
    let match: NSTextCheckingResult?
}

private struct SourceLocation {
    let line: Int
    let column: Int
}

private extension String {
    func trimmingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }
}
