import Carbon.HIToolbox
import Foundation

enum HotKeyNameMapper {
    static func keyCode(for rawName: String) -> UInt32? {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if name.hasPrefix("0x"), let value = UInt32(name.dropFirst(2), radix: 16) {
            return value
        }
        return keyCodes[name]
    }

    static func karabinerModifiers(_ names: [String]) -> UInt32? {
        modifiers(names.map(normalizedKarabinerModifier))
    }

    static func skhdModifiers(_ names: [String]) -> UInt32? {
        modifiers(names.map(normalizedSkhdModifier))
    }

    static func describe(chord: HotKeyChord) -> String {
        var parts: [String] = []
        let knownModifiers = [
            (UInt32(cmdKey), "cmd"),
            (UInt32(shiftKey), "shift"),
            (UInt32(optionKey), "option"),
            (UInt32(controlKey), "control")
        ]
        for (flag, name) in knownModifiers where chord.modifiers & flag != 0 {
            parts.append(name)
        }
        let extra = chord.modifiers & ~knownModifiers.reduce(UInt32(0)) { $0 | $1.0 }
        if extra != 0 {
            parts.append("modifiers:\(extra)")
        }
        parts.append(keyNames[chord.keyCode] ?? "keyCode:\(chord.keyCode)")
        return parts.joined(separator: "+")
    }

    private static func modifiers(_ names: [String?]) -> UInt32? {
        var result = UInt32(0)
        for name in names {
            switch name {
            case "command":
                result |= UInt32(cmdKey)
            case "shift":
                result |= UInt32(shiftKey)
            case "option":
                result |= UInt32(optionKey)
            case "control":
                result |= UInt32(controlKey)
            case "":
                continue
            default:
                return nil
            }
        }
        return result
    }

    private static func normalizedKarabinerModifier(_ name: String) -> String? {
        switch name.lowercased() {
        case "command", "left_command", "right_command":
            return "command"
        case "shift", "left_shift", "right_shift":
            return "shift"
        case "option", "left_option", "right_option":
            return "option"
        case "control", "left_control", "right_control":
            return "control"
        default:
            return nil
        }
    }

    private static func normalizedSkhdModifier(_ name: String) -> String? {
        switch name.lowercased() {
        case "cmd", "command", "lcmd", "rcmd":
            return "command"
        case "shift", "lshift", "rshift":
            return "shift"
        case "alt", "option", "lalt", "ralt":
            return "option"
        case "ctrl", "control", "lctrl", "rctrl":
            return "control"
        default:
            return nil
        }
    }

    private static let keyCodes: [String: UInt32] = [
        "a": UInt32(kVK_ANSI_A),
        "b": UInt32(kVK_ANSI_B),
        "c": UInt32(kVK_ANSI_C),
        "d": UInt32(kVK_ANSI_D),
        "e": UInt32(kVK_ANSI_E),
        "f": UInt32(kVK_ANSI_F),
        "g": UInt32(kVK_ANSI_G),
        "h": UInt32(kVK_ANSI_H),
        "i": UInt32(kVK_ANSI_I),
        "j": UInt32(kVK_ANSI_J),
        "k": UInt32(kVK_ANSI_K),
        "l": UInt32(kVK_ANSI_L),
        "m": UInt32(kVK_ANSI_M),
        "n": UInt32(kVK_ANSI_N),
        "o": UInt32(kVK_ANSI_O),
        "p": UInt32(kVK_ANSI_P),
        "q": UInt32(kVK_ANSI_Q),
        "r": UInt32(kVK_ANSI_R),
        "s": UInt32(kVK_ANSI_S),
        "t": UInt32(kVK_ANSI_T),
        "u": UInt32(kVK_ANSI_U),
        "v": UInt32(kVK_ANSI_V),
        "w": UInt32(kVK_ANSI_W),
        "x": UInt32(kVK_ANSI_X),
        "y": UInt32(kVK_ANSI_Y),
        "z": UInt32(kVK_ANSI_Z),
        "0": UInt32(kVK_ANSI_0),
        "1": UInt32(kVK_ANSI_1),
        "2": UInt32(kVK_ANSI_2),
        "3": UInt32(kVK_ANSI_3),
        "4": UInt32(kVK_ANSI_4),
        "5": UInt32(kVK_ANSI_5),
        "6": UInt32(kVK_ANSI_6),
        "7": UInt32(kVK_ANSI_7),
        "8": UInt32(kVK_ANSI_8),
        "9": UInt32(kVK_ANSI_9),
        "space": UInt32(kVK_Space),
        "spacebar": UInt32(kVK_Space),
        "tab": UInt32(kVK_Tab),
        "return": UInt32(kVK_Return),
        "return_or_enter": UInt32(kVK_Return),
        "enter": UInt32(kVK_Return),
        "escape": UInt32(kVK_Escape),
        "esc": UInt32(kVK_Escape),
        "left": UInt32(kVK_LeftArrow),
        "left_arrow": UInt32(kVK_LeftArrow),
        "right": UInt32(kVK_RightArrow),
        "right_arrow": UInt32(kVK_RightArrow),
        "up": UInt32(kVK_UpArrow),
        "up_arrow": UInt32(kVK_UpArrow),
        "down": UInt32(kVK_DownArrow),
        "down_arrow": UInt32(kVK_DownArrow)
    ]

    private static let keyNames: [UInt32: String] = [
        UInt32(kVK_ANSI_A): "a",
        UInt32(kVK_ANSI_B): "b",
        UInt32(kVK_ANSI_C): "c",
        UInt32(kVK_ANSI_D): "d",
        UInt32(kVK_ANSI_E): "e",
        UInt32(kVK_ANSI_F): "f",
        UInt32(kVK_ANSI_G): "g",
        UInt32(kVK_ANSI_H): "h",
        UInt32(kVK_ANSI_I): "i",
        UInt32(kVK_ANSI_J): "j",
        UInt32(kVK_ANSI_K): "k",
        UInt32(kVK_ANSI_L): "l",
        UInt32(kVK_ANSI_M): "m",
        UInt32(kVK_ANSI_N): "n",
        UInt32(kVK_ANSI_O): "o",
        UInt32(kVK_ANSI_P): "p",
        UInt32(kVK_ANSI_Q): "q",
        UInt32(kVK_ANSI_R): "r",
        UInt32(kVK_ANSI_S): "s",
        UInt32(kVK_ANSI_T): "t",
        UInt32(kVK_ANSI_U): "u",
        UInt32(kVK_ANSI_V): "v",
        UInt32(kVK_ANSI_W): "w",
        UInt32(kVK_ANSI_X): "x",
        UInt32(kVK_ANSI_Y): "y",
        UInt32(kVK_ANSI_Z): "z",
        UInt32(kVK_ANSI_0): "0",
        UInt32(kVK_ANSI_1): "1",
        UInt32(kVK_ANSI_2): "2",
        UInt32(kVK_ANSI_3): "3",
        UInt32(kVK_ANSI_4): "4",
        UInt32(kVK_ANSI_5): "5",
        UInt32(kVK_ANSI_6): "6",
        UInt32(kVK_ANSI_7): "7",
        UInt32(kVK_ANSI_8): "8",
        UInt32(kVK_ANSI_9): "9",
        UInt32(kVK_Space): "space",
        UInt32(kVK_Tab): "tab",
        UInt32(kVK_Return): "return",
        UInt32(kVK_Escape): "escape",
        UInt32(kVK_LeftArrow): "left",
        UInt32(kVK_RightArrow): "right",
        UInt32(kVK_UpArrow): "up",
        UInt32(kVK_DownArrow): "down"
    ]
}
