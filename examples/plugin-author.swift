import ollyCore
import ollyDSL

public func ollyConfig() -> Config {
    Config(version: .v1) {
        Workspaces {
            Tag.named("plugin-lab")
            Tag.named("fallback")
        }

        Engines {
            EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.example.hello"))
            EngineDeclaration.raw(LayoutEngineID(rawValue: "dev.olly.example.dynamic")) { context in
                _ = context.engineID
            }
            EngineDeclaration.floating
        }

        Hooks {
            .raw("plugin-author.trace") { context in
                _ = context.event
            }
        }

        Rules {
            Rule(
                match: RuleMatch(bundleID: "com.example.PluginPreview"),
                apply: RuleApply(tags: tag(0), engine: "dev.olly.example.hello", floating: false)
            )
        }
    }
}

private func tag(_ index: UInt64) -> TagSet {
    TagSet(rawValue: 1 << index)
}
