import ollyCore
import ollyDSL

public func ollyConfig() -> Config {
    Config(version: .v1) {
        Workspaces {
            Tag.named("term")
            Tag.named("docs")
            Tag.named("ops")
        }

        Engines {
            EngineDeclaration.masterStack
            EngineDeclaration.floating
        }

        Keybinds {
            Keybind(KeyChord([.command, .option], .m), do: .setEngine(.masterStack))
            Keybind(KeyChord([.option], .j), do: .focus(.next))
            Keybind(KeyChord([.option, .shift], .j), do: .swap(.next))
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.apple.Terminal"),
                apply: RuleApply(tags: tag(0), engine: .masterStack, floating: false)
            )
            Rule(
                match: RuleMatch(subrole: "AXDialog"),
                apply: RuleApply(engine: .floating, floating: true)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
