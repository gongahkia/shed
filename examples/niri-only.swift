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
        }

        Keybinds {
            Keybind(KeyChord([.option], .h), do: .focus(.left))
            Keybind(KeyChord([.option], .l), do: .focus(.right))
            Keybind(KeyChord([.option], .tab), do: .focus(.next))
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.apple.Safari"),
                apply: RuleApply(tags: tag(0), engine: "niri-scroll", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                apply: RuleApply(tags: tag(1), engine: "niri-scroll", floating: false)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
