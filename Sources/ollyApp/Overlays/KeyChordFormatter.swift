import Carbon.HIToolbox
import ollyDSL

enum KeyChordFormatter {
    static func string(for chord: KeyChord) -> String {
        (modifierLabels(chord.modifiers) + [keyLabel(chord.key)]).joined(separator: "+")
    }

    private static func modifierLabels(_ modifiers: KeyModifiers) -> [String] {
        [
            modifiers.contains(.command) ? "cmd" : nil,
            modifiers.contains(.shift) ? "shift" : nil,
            modifiers.contains(.option) ? "opt" : nil,
            modifiers.contains(.control) ? "ctrl" : nil
        ].compactMap { $0 }
    }

    private static func keyLabel(_ key: Key) -> String {
        switch Int(key.rawValue) {
        case kVK_Space:
            return "space"
        case kVK_Tab:
            return "tab"
        case kVK_Return:
            return "return"
        case kVK_Escape:
            return "esc"
        case kVK_LeftArrow:
            return "left"
        case kVK_RightArrow:
            return "right"
        case kVK_UpArrow:
            return "up"
        case kVK_DownArrow:
            return "down"
        case kVK_ANSI_Slash:
            return "/"
        default:
            return ansiLabel(for: key) ?? "#\(key.rawValue)"
        }
    }

    private static func ansiLabel(for key: Key) -> String? {
        letterLabel(for: key) ?? numberLabel(for: key)
    }

    private static func letterLabel(for key: Key) -> String? {
        let letters = "abcdefghijklmnopqrstuvwxyz"
        let letterCodes = [
            kVK_ANSI_A, kVK_ANSI_B, kVK_ANSI_C, kVK_ANSI_D, kVK_ANSI_E, kVK_ANSI_F,
            kVK_ANSI_G, kVK_ANSI_H, kVK_ANSI_I, kVK_ANSI_J, kVK_ANSI_K, kVK_ANSI_L,
            kVK_ANSI_M, kVK_ANSI_N, kVK_ANSI_O, kVK_ANSI_P, kVK_ANSI_Q, kVK_ANSI_R,
            kVK_ANSI_S, kVK_ANSI_T, kVK_ANSI_U, kVK_ANSI_V, kVK_ANSI_W, kVK_ANSI_X,
            kVK_ANSI_Y, kVK_ANSI_Z
        ]
        guard let index = letterCodes.firstIndex(of: Int(key.rawValue)) else {
            return nil
        }
        return String(letters[letters.index(letters.startIndex, offsetBy: index)])
    }

    private static func numberLabel(for key: Key) -> String? {
        let numberCodes = [
            kVK_ANSI_0, kVK_ANSI_1, kVK_ANSI_2, kVK_ANSI_3, kVK_ANSI_4,
            kVK_ANSI_5, kVK_ANSI_6, kVK_ANSI_7, kVK_ANSI_8, kVK_ANSI_9
        ]
        return numberCodes.firstIndex(of: Int(key.rawValue)).map(String.init)
    }
}
