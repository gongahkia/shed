// swiftlint:disable identifier_name inclusive_language
import CoreGraphics
import Foundation
import ollyCore
import ollyKit
import ollyLayouts

/// Purpose: Stores typed built-in engine configuration payloads for DSL-declared engines.
/// Parameters: Choose the case matching the engine primitive being configured.
/// Example: `EngineConfigDeclaration.grid(GridLayoutEngine.Config(policy: .squareish))`
/// See also: `EngineDeclaration`, `Engines`.
public enum EngineConfigDeclaration: Codable, Equatable, Sendable {
    case monocle(MonocleLayoutEngine.Config)
    case spiral(SpiralLayoutEngine.Config)
    case grid(GridLayoutEngine.Config)
    case threeCol(ThreeColLayoutEngine.Config)
    case accordion(AccordionLayoutEngine.Config)
    case tabbed(TabbedLayoutEngine.Config)
    case stacked(StackedLayoutEngine.Config)
    case treeTab(TreeTabLayoutEngine.Config)
}

/// Purpose: Declares one layout engine and its optional typed configuration.
/// Parameters: Pass a `LayoutEngineID` and optional `EngineConfigDeclaration`.
/// Example: `EngineDeclaration(BSPLayoutEngine.engineID)`
/// See also: `Engines`, `EngineConfigDeclaration`.
public struct EngineDeclaration: Codable, Equatable, Sendable {
    public let id: LayoutEngineID
    public let config: EngineConfigDeclaration?
    public let animation: Animation?
    public let rawHandler: RawDSLBlock<Void>?

    public init(
        _ id: LayoutEngineID,
        config: EngineConfigDeclaration? = nil,
        animation: Animation? = nil,
        rawHandler: RawDSLBlock<Void>? = nil
    ) {
        self.id = id
        self.config = config
        self.animation = animation
        self.rawHandler = rawHandler
    }

    public static func raw(
        _ id: LayoutEngineID,
        label: String? = nil,
        _ body: @escaping RawDSLHandler
    ) -> EngineDeclaration {
        let resolvedLabel = label ?? id.rawValue
        return EngineDeclaration(id, rawHandler: RawDSLBlock(resolvedLabel, body))
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public static func == (lhs: EngineDeclaration, rhs: EngineDeclaration) -> Bool {
        lhs.id == rhs.id && lhs.config == rhs.config && lhs.animation == rhs.animation
    }

    enum CodingKeys: String, CodingKey {
        case id
        case config
        case animation
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try container.decode(LayoutEngineID.self, forKey: .id),
            config: try container.decodeIfPresent(EngineConfigDeclaration.self, forKey: .config),
            animation: try container.decodeIfPresent(Animation.self, forKey: .animation)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(config, forKey: .config)
        try container.encodeIfPresent(animation, forKey: .animation)
    }
}

public extension EngineDeclaration {
    static let floating = EngineDeclaration(FloatingLayoutEngine.engineID)
    static let masterStack = EngineDeclaration(MasterStackLayoutEngine.engineID)
    static let manual = EngineDeclaration(ManualLayoutEngine.engineID)
    static let bsp = EngineDeclaration(BSPLayoutEngine.engineID)
    static let niriScroll = EngineDeclaration(NiriScrollLayoutEngine.engineID)
    static let monocle = Monocle()
    static let spiral = Spiral()
    static let grid = Grid()
    static let threeCol = ThreeCol()
    static let accordion = Accordion()
    static let tabbed = Tabbed()
    static let stacked = Stacked()
    static let treeTab = TreeTab()
}

public extension LayoutEngineID {
    static let floating = FloatingLayoutEngine.engineID
    static let masterStack = MasterStackLayoutEngine.engineID
    static let manual = ManualLayoutEngine.engineID
    static let bsp = BSPLayoutEngine.engineID
    static let niriScroll = NiriScrollLayoutEngine.engineID
    static let monocle = MonocleLayoutEngine.engineID
    static let spiral = SpiralLayoutEngine.engineID
    static let grid = GridLayoutEngine.engineID
    static let threeCol = ThreeColLayoutEngine.engineID
    static let accordion = AccordionLayoutEngine.engineID
    static let tabbed = TabbedLayoutEngine.engineID
    static let stacked = StackedLayoutEngine.engineID
    static let treeTab = TreeTabLayoutEngine.engineID
    static let frame = FrameLayoutEngine.engineID
    static let paperWMScroll = PaperWMScrollLayoutEngine.engineID
    static let verticalTile = VerticalTileLayoutEngine.engineID
    static let ratioTile = RatioTileLayoutEngine.engineID
}

/// Purpose: Declares the Monocle layout engine.
/// Parameters: No parameters; Monocle expands focus to display bounds and hides siblings.
/// Example: `Engines { Monocle() }`
/// See also: `Spiral()`, `Grid(_:)`.
public func Monocle() -> EngineDeclaration {
    EngineDeclaration(
        MonocleLayoutEngine.engineID,
        config: .monocle(MonocleLayoutEngine.Config())
    )
}

/// Purpose: Declares the Spiral layout engine.
/// Parameters: `splitRatio` controls each recursive split and defaults to the golden ratio.
/// Example: `Engines { Spiral(splitRatio: 0.6) }`
/// See also: `Monocle()`, `Grid(_:)`.
public func Spiral(splitRatio: CGFloat = SpiralLayoutEngine.Config.goldenRatio) -> EngineDeclaration {
    EngineDeclaration(
        SpiralLayoutEngine.engineID,
        config: .spiral(SpiralLayoutEngine.Config(splitRatio: splitRatio))
    )
}

/// Purpose: Declares the Grid layout engine.
/// Parameters: `policy` selects square-ish, fixed-row, or fixed-column grid sizing.
/// Example: `Engines { Grid(.fixedCols(3)) }`
/// See also: `Spiral(splitRatio:)`, `ThreeCol(masterRatio:)`.
public func Grid(_ policy: GridLayoutPolicy = .squareish) -> EngineDeclaration {
    EngineDeclaration(
        GridLayoutEngine.engineID,
        config: .grid(GridLayoutEngine.Config(policy: policy))
    )
}

/// Purpose: Declares the ThreeCol layout engine.
/// Parameters: `masterRatio` sets the centered master column width fraction.
/// Example: `Engines { ThreeCol(masterRatio: 0.45) }`
/// See also: `Grid(_:)`, `Accordion(stripHeight:)`.
public func ThreeCol(masterRatio: CGFloat = 0.5) -> EngineDeclaration {
    EngineDeclaration(
        ThreeColLayoutEngine.engineID,
        config: .threeCol(ThreeColLayoutEngine.Config(masterRatio: masterRatio))
    )
}

/// Purpose: Declares the Accordion layout engine.
/// Parameters: `stripHeight` sets the collapsed sibling strip height in points.
/// Example: `Engines { Accordion(stripHeight: 40) }`
/// See also: `ThreeCol(masterRatio:)`, `Monocle()`.
public func Accordion(stripHeight: CGFloat = 48) -> EngineDeclaration {
    EngineDeclaration(
        AccordionLayoutEngine.engineID,
        config: .accordion(AccordionLayoutEngine.Config(stripHeight: stripHeight))
    )
}

/// Purpose: Declares the Tabbed layout engine.
/// Parameters: `tabBarHeight` reserves space for the app-rendered top tab strip.
/// Example: `Engines { Tabbed(tabBarHeight: 30) }`
/// See also: `Accordion(stripHeight:)`, `Monocle()`.
public func Tabbed(tabBarHeight: CGFloat = 28) -> EngineDeclaration {
    EngineDeclaration(
        TabbedLayoutEngine.engineID,
        config: .tabbed(TabbedLayoutEngine.Config(tabBarHeight: tabBarHeight))
    )
}

/// Purpose: Declares the Stacked layout engine.
/// Parameters: `railWidth` reserves space for the app-rendered left title rail.
/// Example: `Engines { Stacked(railWidth: 180) }`
/// See also: `Tabbed(tabBarHeight:)`, `Accordion(stripHeight:)`.
public func Stacked(railWidth: CGFloat = 160) -> EngineDeclaration {
    EngineDeclaration(
        StackedLayoutEngine.engineID,
        config: .stacked(StackedLayoutEngine.Config(railWidth: railWidth))
    )
}

/// Purpose: Declares the TreeTab layout engine.
/// Parameters: `railWidth` reserves space for the app-rendered side tree; `side` chooses left or right.
/// Example: `Engines { TreeTab(railWidth: 180, side: .right) }`
/// See also: `Tabbed(tabBarHeight:)`, `Stacked(railWidth:)`.
public func TreeTab(railWidth: CGFloat = 150, side: TreeTabSide = .left) -> EngineDeclaration {
    EngineDeclaration(
        TreeTabLayoutEngine.engineID,
        config: .treeTab(TreeTabLayoutEngine.Config(railWidth: railWidth, side: side))
    )
}

/// Purpose: Groups engine declarations available to workspace and tag bindings.
/// Parameters: Pass an array or use `@EngineBuilder` with `EngineDeclaration` expressions.
/// Example: `Engines { .niriScroll; Grid() }`
/// See also: `EngineDeclaration`, `EngineBuilder`.
public struct Engines: Codable, Equatable, Sendable {
    public let engines: [EngineDeclaration]

    public init(_ engines: [EngineDeclaration] = []) {
        self.engines = engines
    }

    public init(@EngineBuilder _ build: () -> [EngineDeclaration]) {
        self.engines = build()
    }
}

/// Purpose: Builds engine declarations inside `Engines { ... }`.
/// Parameters: Accepts engine expressions, arrays, and conditional branches.
/// Example: `Engines { .floating; .bsp; Monocle() }`
/// See also: `Engines`, `EngineDeclaration`.
@resultBuilder
public enum EngineBuilder {
    public static func buildBlock(_ components: [EngineDeclaration]...) -> [EngineDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [EngineDeclaration]?) -> [EngineDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [EngineDeclaration]) -> [EngineDeclaration] {
        component
    }

    public static func buildEither(second component: [EngineDeclaration]) -> [EngineDeclaration] {
        component
    }

    public static func buildArray(_ components: [[EngineDeclaration]]) -> [EngineDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: EngineDeclaration) -> [EngineDeclaration] {
        [expression]
    }
}
// swiftlint:enable identifier_name inclusive_language
