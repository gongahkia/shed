import Carbon.HIToolbox
import Foundation

enum SymbolicHotKeyReaderError: Error, Equatable {
    case copyFailed(OSStatus)
}

enum SymbolicHotKeyReader {
    static func read() throws -> [ExternalHotKey] {
        var rawArray: Unmanaged<CFArray>?
        let status = CopySymbolicHotKeys(&rawArray)
        guard status == noErr else {
            throw SymbolicHotKeyReaderError.copyFailed(status)
        }
        guard let entries = rawArray?.takeRetainedValue() as? [[String: Any]] else {
            return []
        }
        return entries.enumerated().compactMap { index, entry in
            guard
                bool(entry[kHISymbolicHotKeyEnabled as String]) == true,
                let keyCode = number(entry[kHISymbolicHotKeyCode as String]),
                let modifiers = number(entry[kHISymbolicHotKeyModifiers as String])
            else {
                return nil
            }
            return ExternalHotKey(
                owner: .macOSSymbolicHotKey,
                chord: HotKeyChord(keyCode: keyCode, modifiers: modifiers),
                detail: "symbolic hotkey #\(index)"
            )
        }
    }

    private static func number(_ value: Any?) -> UInt32? {
        if let number = value as? NSNumber {
            return number.uint32Value
        }
        if let value = value as? UInt32 {
            return value
        }
        if let value = value as? Int, value >= 0 {
            return UInt32(value)
        }
        return nil
    }

    private static func bool(_ value: Any?) -> Bool? {
        value as? Bool ?? (value as? NSNumber)?.boolValue
    }
}
