// swiftlint:disable identifier_name
import Carbon.HIToolbox
import Foundation
import ollyCore

/// Purpose: Represents command, shift, option, and control modifiers for DSL key chords.
/// Parameters: Use predefined flags or initialize from a raw Carbon-compatible bit mask.
/// Example: `KeyModifiers([.command, .option])`
/// See also: `KeyChord`, `Keybind`.
public struct KeyModifiers: Codable, Equatable, Hashable, OptionSet, Sendable {
    public let rawValue: UInt32

    public init(rawValue: UInt32) {
        self.rawValue = rawValue
    }

    public static let command = KeyModifiers(rawValue: 1 << 0)
    public static let shift = KeyModifiers(rawValue: 1 << 1)
    public static let option = KeyModifiers(rawValue: 1 << 2)
    public static let control = KeyModifiers(rawValue: 1 << 3)

    var carbonFlags: UInt32 {
        var flags: UInt32 = 0
        if contains(.command) {
            flags |= UInt32(cmdKey)
        }
        if contains(.shift) {
            flags |= UInt32(shiftKey)
        }
        if contains(.option) {
            flags |= UInt32(optionKey)
        }
        if contains(.control) {
            flags |= UInt32(controlKey)
        }
        return flags
    }
}

/// Purpose: Represents one physical keyboard key in a DSL key chord.
/// Parameters: Use predefined static keys or initialize from a Carbon virtual key code.
/// Example: `Key.a`
/// See also: `KeyModifiers`, `KeyChord`.
public struct Key: Codable, Equatable, Hashable, RawRepresentable, Sendable {
    public let rawValue: UInt32

    public init(rawValue: UInt32) {
        self.rawValue = rawValue
    }
}

public extension Key {
    static let a = Key(rawValue: UInt32(kVK_ANSI_A))
    static let b = Key(rawValue: UInt32(kVK_ANSI_B))
    static let c = Key(rawValue: UInt32(kVK_ANSI_C))
    static let d = Key(rawValue: UInt32(kVK_ANSI_D))
    static let e = Key(rawValue: UInt32(kVK_ANSI_E))
    static let f = Key(rawValue: UInt32(kVK_ANSI_F))
    static let g = Key(rawValue: UInt32(kVK_ANSI_G))
    static let h = Key(rawValue: UInt32(kVK_ANSI_H))
    static let i = Key(rawValue: UInt32(kVK_ANSI_I))
    static let j = Key(rawValue: UInt32(kVK_ANSI_J))
    static let k = Key(rawValue: UInt32(kVK_ANSI_K))
    static let l = Key(rawValue: UInt32(kVK_ANSI_L))
    static let m = Key(rawValue: UInt32(kVK_ANSI_M))
    static let n = Key(rawValue: UInt32(kVK_ANSI_N))
    static let o = Key(rawValue: UInt32(kVK_ANSI_O))
    static let p = Key(rawValue: UInt32(kVK_ANSI_P))
    static let q = Key(rawValue: UInt32(kVK_ANSI_Q))
    static let r = Key(rawValue: UInt32(kVK_ANSI_R))
    static let s = Key(rawValue: UInt32(kVK_ANSI_S))
    static let t = Key(rawValue: UInt32(kVK_ANSI_T))
    static let u = Key(rawValue: UInt32(kVK_ANSI_U))
    static let v = Key(rawValue: UInt32(kVK_ANSI_V))
    static let w = Key(rawValue: UInt32(kVK_ANSI_W))
    static let x = Key(rawValue: UInt32(kVK_ANSI_X))
    static let y = Key(rawValue: UInt32(kVK_ANSI_Y))
    static let z = Key(rawValue: UInt32(kVK_ANSI_Z))
    static let zero = Key(rawValue: UInt32(kVK_ANSI_0))
    static let one = Key(rawValue: UInt32(kVK_ANSI_1))
    static let two = Key(rawValue: UInt32(kVK_ANSI_2))
    static let three = Key(rawValue: UInt32(kVK_ANSI_3))
    static let four = Key(rawValue: UInt32(kVK_ANSI_4))
    static let five = Key(rawValue: UInt32(kVK_ANSI_5))
    static let six = Key(rawValue: UInt32(kVK_ANSI_6))
    static let seven = Key(rawValue: UInt32(kVK_ANSI_7))
    static let eight = Key(rawValue: UInt32(kVK_ANSI_8))
    static let nine = Key(rawValue: UInt32(kVK_ANSI_9))
    static let space = Key(rawValue: UInt32(kVK_Space))
    static let tab = Key(rawValue: UInt32(kVK_Tab))
    static let `return` = Key(rawValue: UInt32(kVK_Return))
    static let escape = Key(rawValue: UInt32(kVK_Escape))
    static let leftArrow = Key(rawValue: UInt32(kVK_LeftArrow))
    static let rightArrow = Key(rawValue: UInt32(kVK_RightArrow))
    static let upArrow = Key(rawValue: UInt32(kVK_UpArrow))
    static let downArrow = Key(rawValue: UInt32(kVK_DownArrow))
}

/// Purpose: Combines modifiers and a key into one bindable shortcut chord.
/// Parameters: Pass `KeyModifiers` and `Key` values in order.
/// Example: `KeyChord([.command, .option], .return)`
/// See also: `Keybind`, `Keybinds`.
public struct KeyChord: Codable, Equatable, Hashable, Sendable {
    public let modifiers: KeyModifiers
    public let key: Key

    public init(_ modifiers: KeyModifiers, _ key: Key) {
        self.modifiers = modifiers
        self.key = key
    }
}

/// Purpose: Names directional movement targets for focus, swap, and move actions.
/// Parameters: Use a case such as `.left`, `.next`, or `.previous`.
/// Example: `Action.focus(.next)`
/// See also: `Action`, `Keybind`.
public enum Direction: String, Codable, Equatable, Sendable {
    case up
    case down
    case left
    case right
    case next
    case previous
}

/// Purpose: Declares the command performed when a keybind fires.
/// Parameters: Select a case and provide its associated direction, tag, engine, or raw command.
/// Example: `Action.setEngine(BSPLayoutEngine.engineID)`
/// See also: `Keybind`, `Direction`.
public enum Action: Codable, Equatable, Sendable {
    case focus(Direction)
    case swap(Direction)
    case move(Direction)
    case switchTag(Int)
    case toggleTag(Int)
    case moveWindowToTag(Int)
    case setEngine(LayoutEngineID)
    case cycleEngine
    case reload
    case noop
    case raw(String)
}

public extension Action {
    @available(*, unavailable, message: "unknown-engine-id: use a typed LayoutEngineID such as .bsp")
    static func setEngine(_ engine: String) -> Action {
        fatalError()
    }
}

/// Purpose: Maps one `KeyChord` to one olly action.
/// Parameters: Pass the chord and the action to execute.
/// Example: `Keybind(KeyChord([.command], .return), do: .cycleEngine)`
/// See also: `Keybinds`, `Action`.
public struct Keybind: Codable, Equatable, Sendable {
    public let chord: KeyChord
    public let action: Action
    public let rawHandler: RawDSLBlock<Void>?

    public init(_ chord: KeyChord, do action: Action, rawHandler: RawDSLBlock<Void>? = nil) {
        self.chord = chord
        self.action = action
        self.rawHandler = rawHandler
    }

    public static func raw(
        _ chord: KeyChord,
        label: String = "raw",
        _ body: @escaping RawDSLHandler
    ) -> Keybind {
        Keybind(chord, do: .raw(label), rawHandler: RawDSLBlock(label, body))
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public static func == (lhs: Keybind, rhs: Keybind) -> Bool {
        lhs.chord == rhs.chord && lhs.action == rhs.action
    }

    enum CodingKeys: String, CodingKey {
        case chord
        case action
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try container.decode(KeyChord.self, forKey: .chord),
            do: try container.decode(Action.self, forKey: .action)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(chord, forKey: .chord)
        try container.encode(action, forKey: .action)
    }
}

/// Purpose: Groups keybind declarations for the top-level config.
/// Parameters: Pass an array of `Keybind` values or use `@KeybindBuilder`.
/// Example: `Keybinds { Keybind(KeyChord([.command], .j), do: .focus(.next)) }`
/// See also: `Keybind`, `KeybindBuilder`.
public struct Keybinds: Codable, Equatable, Sendable {
    public let bindings: [Keybind]

    public init(_ bindings: [Keybind] = []) {
        self.bindings = bindings
    }

    public init(@KeybindBuilder _ build: () -> [Keybind]) {
        self.bindings = build()
    }
}

/// Purpose: Builds keybind declarations inside `Keybinds { ... }`.
/// Parameters: Accepts `Keybind` expressions, arrays, and conditionals.
/// Example: `Keybinds { Keybind(KeyChord([.command], .space), do: .noop) }`
/// See also: `Keybinds`, `Keybind`.
@resultBuilder
public enum KeybindBuilder {
    public static func buildBlock(_ components: [Keybind]...) -> [Keybind] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [Keybind]?) -> [Keybind] {
        component ?? []
    }

    public static func buildEither(first component: [Keybind]) -> [Keybind] {
        component
    }

    public static func buildEither(second component: [Keybind]) -> [Keybind] {
        component
    }

    public static func buildArray(_ components: [[Keybind]]) -> [Keybind] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: Keybind) -> [Keybind] {
        [expression]
    }
}
// swiftlint:enable identifier_name
