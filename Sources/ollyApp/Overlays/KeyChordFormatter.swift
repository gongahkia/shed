import Carbon.HIToolbox
import ollyDSL

enum KeyChordFormatter {
    static func string(for chord: KeyChord) -> String {
        (modifierLabels(chord.modifiers) + [keyLabel(chord.key)]).joined(separator: "+")
    }

    private static func modifierLabels(_ modifiers: KeyModifiers) -> [String] {
        [
            modifiers.contains(.command) ? L10n.s("cmd", "key chord command modifier") : nil,
            modifiers.contains(.shift) ? L10n.s("shift", "key chord shift modifier") : nil,
            modifiers.contains(.option) ? L10n.s("opt", "key chord option modifier") : nil,
            modifiers.contains(.control) ? L10n.s("ctrl", "key chord control modifier") : nil
        ].compactMap { $0 }
    }

    private static func keyLabel(_ key: Key) -> String {
        switch Int(key.rawValue) {
        case kVK_Space:
            return L10n.s("space", "key chord space key")
        case kVK_Tab:
            return L10n.s("tab", "key chord tab key")
        case kVK_Return:
            return L10n.s("return", "key chord return key")
        case kVK_Escape:
            return L10n.s("esc", "key chord escape key")
        case kVK_LeftArrow:
            return L10n.s("left", "key chord left arrow")
        case kVK_RightArrow:
            return L10n.s("right", "key chord right arrow")
        case kVK_UpArrow:
            return L10n.s("up", "key chord up arrow")
        case kVK_DownArrow:
            return L10n.s("down", "key chord down arrow")
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
