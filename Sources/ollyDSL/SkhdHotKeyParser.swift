import Foundation

enum SkhdHotKeyParser {
    static func parse(source: String, sourceURL: URL) -> [ExternalHotKey] {
        source.components(separatedBy: .newlines).enumerated().compactMap { lineNumber, line in
            parse(line: line, lineNumber: lineNumber + 1, sourceURL: sourceURL)
        }
    }

    private static func parse(line: String, lineNumber: Int, sourceURL: URL) -> ExternalHotKey? {
        let stripped = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
        guard let colon = stripped.firstIndex(of: ":") else {
            return nil
        }
        var hotKey = String(stripped[..<colon]).trimmingCharacters(in: .whitespacesAndNewlines)
        if let mode = hotKey.lastIndex(of: "<") {
            hotKey = String(hotKey[hotKey.index(after: mode)...])
        }
        hotKey = hotKey.replacingOccurrences(of: "->", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let separator = hotKey.lastIndex(of: "-") else {
            return hotKeyFromKeyOnly(hotKey, lineNumber: lineNumber, sourceURL: sourceURL)
        }

        let modifierSource = String(hotKey[..<separator])
        let keySource = String(hotKey[hotKey.index(after: separator)...])
        let modifierNames = modifierSource.split(separator: "+").map {
            String($0).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard
            let modifiers = HotKeyNameMapper.skhdModifiers(modifierNames),
            let keyCode = HotKeyNameMapper.keyCode(for: keySource)
        else {
            return nil
        }
        return ExternalHotKey(
            owner: .skhd,
            chord: HotKeyChord(keyCode: keyCode, modifiers: modifiers),
            detail: "\(sourceURL.lastPathComponent):\(lineNumber)"
        )
    }

    private static func hotKeyFromKeyOnly(_ key: String, lineNumber: Int, sourceURL: URL) -> ExternalHotKey? {
        guard let keyCode = HotKeyNameMapper.keyCode(for: key) else {
            return nil
        }
        return ExternalHotKey(
            owner: .skhd,
            chord: HotKeyChord(keyCode: keyCode, modifiers: 0),
            detail: "\(sourceURL.lastPathComponent):\(lineNumber)"
        )
    }
}
