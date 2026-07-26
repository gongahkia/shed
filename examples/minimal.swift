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

        Telemetry {
            usageEndpoint(nil)
        }

        Keybinds {
            Keybind(KeyChord([.command, .option], .return), do: .cycleEngine)
        }
    }
}
