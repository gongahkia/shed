import Foundation
import ollyCore
import ollyDSL
import ollyKit
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
    private var runtimeOverrides: [LayoutEngineID: Any] = [:]

    init(config: Config = Config()) {
        self.config = config
    }

    func current() -> Config {
        config
    }

    func replace(with config: Config) {
        self.config = config
        runtimeOverrides.removeAll()
    }

    func availableEngineIDs() -> [LayoutEngineID] {
        let ids = config.engines.engines.map(\.id)
        return ids.isEmpty ? [FloatingLayoutEngine.engineID] : ids
    }

    func config(for engineID: LayoutEngineID) -> Any? {
        if let override = runtimeOverrides[engineID] {
            return override
        }
        if let declaration = config.engines.engines.first(where: { $0.id == engineID }),
           let typedConfig = declaration.config {
            return configPayload(typedConfig)
        }
        return defaultConfig(for: engineID)
    }

    func updateManualTree(_ transform: (ManualLayoutTree) throws -> ManualLayoutTree) throws -> ManualLayoutTree {
        let current = (config(for: ManualLayoutEngine.engineID) as? ManualLayoutEngine.Config)?.tree
            ?? ManualLayoutTree()
        let updatedTree = try transform(current)
        runtimeOverrides[ManualLayoutEngine.engineID] = ManualLayoutEngine.Config(tree: updatedTree)
        return updatedTree
    }

    func updateBSPTree(_ transform: (BSPLayoutTree) throws -> BSPLayoutTree) throws -> BSPLayoutTree {
        let current = (config(for: BSPLayoutEngine.engineID) as? BSPLayoutEngine.Config)?.tree ?? BSPLayoutTree()
        let updatedTree = try transform(current)
        runtimeOverrides[BSPLayoutEngine.engineID] = BSPLayoutEngine.Config(tree: updatedTree)
        return updatedTree
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
