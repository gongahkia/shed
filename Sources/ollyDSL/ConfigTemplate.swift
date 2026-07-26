import Foundation

public enum ConfigTemplateError: Error, CustomStringConvertible, Equatable {
    case unknownProfile(String)

    public var description: String {
        switch self {
        case let .unknownProfile(profile):
            return "unknown config profile: \(profile)"
        }
    }
}

public enum ConfigTemplateProfile: String, CaseIterable, Codable, Equatable, Sendable {
    case starter
    case minimal
    case niri
    case bsp
    case ultrawide

    public static let defaultProfile: ConfigTemplateProfile = .starter

    public init(name: String) throws {
        guard let profile = Self(rawValue: name) else {
            throw ConfigTemplateError.unknownProfile(name)
        }
        self = profile
    }

    public var displayName: String {
        switch self {
        case .starter:
            return "Starter"
        case .minimal:
            return "Minimal"
        case .niri:
            return "Niri Scroll"
        case .bsp:
            return "BSP"
        case .ultrawide:
            return "Ultrawide"
        }
    }

    public var summary: String {
        switch self {
        case .starter:
            return "balanced tags, BSP, scrolling columns, and floating fallbacks"
        case .minimal:
            return "one tag and one floating engine"
        case .niri:
            return "scrolling-column workflow for browser/code/chat"
        case .bsp:
            return "keyboard-first binary split workflow"
        case .ultrawide:
            return "centered master and grid workflow for wide displays"
        }
    }

    public var source: String {
        switch self {
        case .starter:
            return Self.starterSource
        case .minimal:
            return Self.minimalSource
        case .niri:
            return Self.niriSource
        case .bsp:
            return Self.bspSource
        case .ultrawide:
            return Self.ultrawideSource
        }
    }
}

private extension ConfigTemplateProfile {
    static let starterSource = """
    import ollyCore
    import ollyDSL

    public func ollyConfig() -> Config {
        Config(version: .v1) {
            Workspaces {
                Tag.named("code")
                Tag.named("web")
                Tag.named("chat")
                Tag.named("scratch")
            }

            Engines {
                EngineDeclaration.bsp
                EngineDeclaration.niriScroll
                EngineDeclaration.masterStack
                EngineDeclaration.floating
            }

            Keybinds {
                Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
                Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
                Keybind(KeyChord([.command, .option], .three), do: .switchTag(2))
                Keybind(KeyChord([.command, .shift], .one), do: .moveWindowToTag(0))
                Keybind(KeyChord([.command, .shift], .two), do: .moveWindowToTag(1))
                Keybind(KeyChord([.command, .option], .space), do: .cycleEngine)
                Keybind(KeyChord([.command, .shift], .r), do: .reload)
                Keybind(KeyChord([.option], .h), do: .focus(.left))
                Keybind(KeyChord([.option], .j), do: .focus(.down))
                Keybind(KeyChord([.option], .k), do: .focus(.up))
                Keybind(KeyChord([.option], .l), do: .focus(.right))
                Keybind(KeyChord([.option, .shift], .h), do: .swap(.left))
                Keybind(KeyChord([.option, .shift], .j), do: .swap(.down))
                Keybind(KeyChord([.option, .shift], .k), do: .swap(.up))
                Keybind(KeyChord([.option, .shift], .l), do: .swap(.right))
            }

            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.apple.Safari"),
                    apply: RuleApply(tags: tag(1), engine: .niriScroll, floating: false)
                )
                Rule(
                    match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                    apply: RuleApply(tags: tag(0), engine: .bsp, floating: false)
                )
                Rule(
                    match: RuleMatch(subrole: "AXDialog"),
                    apply: RuleApply(tags: tag(3), engine: .floating, floating: true)
                )
            }
        }
    }

    private func tag(_ index: UInt64) -> TagSet {
        TagSet(rawValue: 1 << index)
    }
    """

    static let minimalSource = """
    import ollyCore
    import ollyDSL

    public func ollyConfig() -> Config {
        Config(version: .v1) {
            Workspaces {
                Tag.named("main")
            }

            Engines {
                EngineDeclaration.floating
            }

            Keybinds {
                Keybind(KeyChord([.command, .option], .return), do: .cycleEngine)
            }
        }
    }
    """

    static let niriSource = """
    import ollyCore
    import ollyDSL

    public func ollyConfig() -> Config {
        Config(version: .v1) {
            Workspaces {
                Tag.named("web")
                Tag.named("code")
                Tag.named("chat")
            }

            Engines {
                EngineDeclaration.niriScroll
                EngineDeclaration.floating
            }

            Keybinds {
                Keybind(KeyChord([.option], .h), do: .focus(.left))
                Keybind(KeyChord([.option], .l), do: .focus(.right))
                Keybind(KeyChord([.option], .tab), do: .focus(.next))
                Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
                Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
                Keybind(KeyChord([.command, .shift], .r), do: .reload)
            }

            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.apple.Safari"),
                    apply: RuleApply(tags: tag(0), engine: .niriScroll, floating: false)
                )
                Rule(
                    match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                    apply: RuleApply(tags: tag(1), engine: .niriScroll, floating: false)
                )
            }
        }
    }

    private func tag(_ index: UInt64) -> TagSet {
        TagSet(rawValue: 1 << index)
    }
    """

    static let bspSource = """
    import ollyCore
    import ollyDSL

    public func ollyConfig() -> Config {
        Config(version: .v1) {
            Workspaces {
                Tag.named("term")
                Tag.named("code")
                Tag.named("web")
            }

            Engines {
                EngineDeclaration.bsp
                EngineDeclaration.manual
                EngineDeclaration.floating
            }

            Keybinds {
                Keybind(KeyChord([.command, .option], .b), do: .setEngine(.bsp))
                Keybind(KeyChord([.command, .option], .u), do: .setEngine(.manual))
                Keybind(KeyChord([.command, .option], .f), do: .setEngine(.floating))
                Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
                Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
                Keybind(KeyChord([.option], .h), do: .focus(.left))
                Keybind(KeyChord([.option], .j), do: .focus(.down))
                Keybind(KeyChord([.option], .k), do: .focus(.up))
                Keybind(KeyChord([.option], .l), do: .focus(.right))
                Keybind(KeyChord([.option, .shift], .h), do: .swap(.left))
                Keybind(KeyChord([.option, .shift], .j), do: .swap(.down))
                Keybind(KeyChord([.option, .shift], .k), do: .swap(.up))
                Keybind(KeyChord([.option, .shift], .l), do: .swap(.right))
            }

            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.apple.Terminal"),
                    apply: RuleApply(tags: tag(0), engine: .bsp, floating: false)
                )
                Rule(
                    match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                    apply: RuleApply(tags: tag(1), engine: .bsp, floating: false)
                )
            }
        }
    }

    private func tag(_ index: UInt64) -> TagSet {
        TagSet(rawValue: 1 << index)
    }
    """

    static let ultrawideSource = """
    import ollyCore
    import ollyDSL

    public func ollyConfig() -> Config {
        Config(version: .v1) {
            Workspaces {
                Tag.named("wide")
                Tag.named("review")
                Tag.named("scratch")
            }

            Engines {
                ThreeCol(masterRatio: 0.42)
                Grid(.fixedCols(3))
                EngineDeclaration.floating
            }

            Keybinds {
                Keybind(KeyChord([.command, .option], .u), do: .setEngine(.threeCol))
                Keybind(KeyChord([.command, .option], .g), do: .setEngine(.grid))
                Keybind(KeyChord([.option], .tab), do: .focus(.next))
                Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
                Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
            }

            Rules {
                Rule(
                    match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                    apply: RuleApply(tags: tag(0), engine: .threeCol, floating: false)
                )
                Rule(
                    match: RuleMatch(bundleID: "com.apple.finder"),
                    apply: RuleApply(tags: tag(1), engine: .grid, floating: false)
                )
            }
        }
    }

    private func tag(_ index: UInt64) -> TagSet {
        TagSet(rawValue: 1 << index)
    }
    """
}
