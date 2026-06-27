import ollyCore
import ollyDSL

public func ollyConfig() -> Config {
    Config(version: .v1) {
        Workspaces {
            Tag.named("left-code")
            Tag.named("left-docs")
            Tag.named("right-web")
            Tag.named("right-chat")
            Tag.named("shared")
            Tag.named("scratch")
        }

        Engines {
            EngineDeclaration.niriScroll
            EngineDeclaration.bsp
            EngineDeclaration.floating
            Grid(.squareish)
        }

        Keybinds {
            Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
            Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
            Keybind(KeyChord([.command, .option], .three), do: .switchTag(2))
            Keybind(KeyChord([.command, .shift], .one), do: .moveWindowToTag(0))
            Keybind(KeyChord([.command, .shift], .three), do: .moveWindowToTag(2))
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.apple.Safari"),
                apply: RuleApply(tags: tag(2), engine: "niri-scroll", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.tinyspeck.slackmacgap"),
                apply: RuleApply(tags: tag(3), engine: "floating", floating: true)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.Terminal"),
                apply: RuleApply(tags: tag(0).union(tag(4)), engine: "bsp", floating: false)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
