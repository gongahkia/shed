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
