import Foundation
import ollyCore
import ollyDSL
import ollyLayouts

actor RuntimeConfigStore {
    private static let defaultConfigFactories: [LayoutEngineID: () -> Any] = [
        FloatingLayoutEngine.engineID: { FloatingLayoutEngine.Config() },
        MasterStackLayoutEngine.engineID: { MasterStackLayoutEngine.Config() },
        ManualLayoutEngine.engineID: { ManualLayoutEngine.Config() },
        BSPLayoutEngine.engineID: { BSPLayoutEngine.Config() },
        NiriScrollLayoutEngine.engineID: { NiriScrollLayoutEngine.Config() },
        MonocleLayoutEngine.engineID: { MonocleLayoutEngine.Config() },
        SpiralLayoutEngine.engineID: { SpiralLayoutEngine.Config() },
        GridLayoutEngine.engineID: { GridLayoutEngine.Config() },
        ThreeColLayoutEngine.engineID: { ThreeColLayoutEngine.Config() },
        AccordionLayoutEngine.engineID: { AccordionLayoutEngine.Config() },
        TabbedLayoutEngine.engineID: { TabbedLayoutEngine.Config() },
        StackedLayoutEngine.engineID: { StackedLayoutEngine.Config() },
        TreeTabLayoutEngine.engineID: { TreeTabLayoutEngine.Config() },
        FrameLayoutEngine.engineID: { FrameLayoutEngine.Config() },
        PaperWMScrollLayoutEngine.engineID: { PaperWMScrollLayoutEngine.Config() },
        VerticalTileLayoutEngine.engineID: { VerticalTileLayoutEngine.Config() },
        RatioTileLayoutEngine.engineID: { RatioTileLayoutEngine.Config() }
    ]

    private var config: Config

    init(config: Config = Config()) {
        self.config = config
    }

    func current() -> Config {
        config
    }

    func replace(with config: Config) {
        self.config = config
    }

    func availableEngineIDs() -> [LayoutEngineID] {
        let ids = config.engines.engines.map(\.id)
        return ids.isEmpty ? [FloatingLayoutEngine.engineID] : ids
    }

    func config(for engineID: LayoutEngineID) -> Any? {
        if let declaration = config.engines.engines.first(where: { $0.id == engineID }),
           let typedConfig = declaration.config {
            return configPayload(typedConfig)
        }
        return defaultConfig(for: engineID)
    }

    private func configPayload(_ declaration: EngineConfigDeclaration) -> Any {
        switch declaration {
        case let .monocle(config):
            return config
        case let .spiral(config):
            return config
        case let .grid(config):
            return config
        case let .threeCol(config):
            return config
        case let .accordion(config):
            return config
        case let .tabbed(config):
            return config
        case let .stacked(config):
            return config
        case let .treeTab(config):
            return config
        }
    }

    private func defaultConfig(for engineID: LayoutEngineID) -> Any? {
        Self.defaultConfigFactories[engineID]?()
    }
}
