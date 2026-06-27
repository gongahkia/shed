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
            Keybind(KeyChord([.command, .option], .u), do: .setEngine("three-col"))
            Keybind(KeyChord([.command, .option], .g), do: .setEngine("grid"))
            Keybind(KeyChord([.option], .tab), do: .focus(.next))
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                apply: RuleApply(tags: tag(0), engine: "three-col", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.finder"),
                apply: RuleApply(tags: tag(1), engine: "grid", floating: false)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
