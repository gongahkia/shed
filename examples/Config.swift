import ollyCore
import ollyDSL

public func ollyConfig() -> Config {
    Config {
        Workspaces {
            Tag.named("comms")
            Tag.named("code")
            Tag.named("web")
            Tag.named("docs")
            Tag.named("media")
            Tag.named("ops")
            Tag.named("meet")
            Tag.named("scratch")
        }

        Engines {
            EngineDeclaration.floating
            EngineDeclaration.masterStack
            EngineDeclaration.manual
            EngineDeclaration.bsp
            EngineDeclaration.niriScroll
        }

        CooperativeApps {
            CooperativeApp("com.example.CustomOverlay")
        }

        Keybinds {
            Keybind(KeyChord([.command, .option], .one), do: .switchTag(0))
            Keybind(KeyChord([.command, .option], .two), do: .switchTag(1))
            Keybind(KeyChord([.command, .option], .three), do: .switchTag(2))
            Keybind(KeyChord([.command, .option], .four), do: .switchTag(3))
            Keybind(KeyChord([.command, .option], .five), do: .switchTag(4))
            Keybind(KeyChord([.command, .option], .six), do: .switchTag(5))
            Keybind(KeyChord([.command, .option], .seven), do: .switchTag(6))
            Keybind(KeyChord([.command, .option], .eight), do: .switchTag(7))

            Keybind(KeyChord([.command, .shift], .one), do: .moveWindowToTag(0))
            Keybind(KeyChord([.command, .shift], .two), do: .moveWindowToTag(1))
            Keybind(KeyChord([.command, .shift], .three), do: .moveWindowToTag(2))
            Keybind(KeyChord([.command, .shift], .four), do: .moveWindowToTag(3))
            Keybind(KeyChord([.command, .shift], .five), do: .moveWindowToTag(4))
            Keybind(KeyChord([.command, .shift], .six), do: .moveWindowToTag(5))
            Keybind(KeyChord([.command, .shift], .seven), do: .moveWindowToTag(6))
            Keybind(KeyChord([.command, .shift], .eight), do: .moveWindowToTag(7))

            Keybind(KeyChord([.command, .option], .f), do: .setEngine("floating"))
            Keybind(KeyChord([.command, .option], .m), do: .setEngine("master-stack"))
            Keybind(KeyChord([.command, .option], .u), do: .setEngine("manual"))
            Keybind(KeyChord([.command, .option], .b), do: .setEngine("bsp"))
            Keybind(KeyChord([.command, .option], .n), do: .setEngine("niri-scroll"))
            Keybind(KeyChord([.command, .option], .space), do: .cycleEngine)
            Keybind(KeyChord([.command, .shift], .r), do: .reload)

            Keybind(KeyChord([.option], .h), do: .focus(.left))
            Keybind(KeyChord([.option], .j), do: .focus(.down))
            Keybind(KeyChord([.option], .k), do: .focus(.up))
            Keybind(KeyChord([.option], .l), do: .focus(.right))
            Keybind(KeyChord([.option], .tab), do: .focus(.next))

            Keybind(KeyChord([.option, .shift], .h), do: .swap(.left))
            Keybind(KeyChord([.option, .shift], .j), do: .swap(.down))
            Keybind(KeyChord([.option, .shift], .k), do: .swap(.up))
            Keybind(KeyChord([.option, .shift], .l), do: .swap(.right))

            Keybind(KeyChord([.control, .option], .h), do: .move(.left))
            Keybind(KeyChord([.control, .option], .j), do: .move(.down))
            Keybind(KeyChord([.control, .option], .k), do: .move(.up))
            Keybind(KeyChord([.control, .option], .l), do: .move(.right))
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.tinyspeck.slackmacgap"),
                apply: RuleApply(tags: tag(0), engine: "floating", floating: true)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.MobileSMS"),
                apply: RuleApply(tags: tag(0), engine: "floating", floating: true)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.Terminal", role: "AXWindow"),
                apply: RuleApply(tags: tag(1), engine: "bsp", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.dt.Xcode"),
                apply: RuleApply(tags: tag(1), engine: "niri-scroll", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.Safari"),
                apply: RuleApply(tags: tag(2), engine: "niri-scroll", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.finder", titleRegex: "^Downloads"),
                apply: RuleApply(tags: tag(3), engine: "master-stack", floating: false)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.Music"),
                apply: RuleApply(tags: tag(4), engine: "floating", floating: true)
            )
            Rule(
                match: RuleMatch(bundleID: "us.zoom.xos"),
                apply: RuleApply(tags: tag(6), engine: "floating", floating: true)
            )
            Rule(
                match: RuleMatch(bundleID: "com.apple.iCal"),
                apply: RuleApply(tags: tag(5), engine: "master-stack", floating: false)
            )
            Rule(
                match: RuleMatch(subrole: "AXDialog"),
                apply: RuleApply(tags: tag(7), engine: "floating", floating: true)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
