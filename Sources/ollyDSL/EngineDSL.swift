// swiftlint:disable identifier_name inclusive_language
import CoreGraphics
import Foundation
import ollyCore
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
}

/// Purpose: Declares one layout engine and its optional typed configuration.
/// Parameters: Pass a `LayoutEngineID` and optional `EngineConfigDeclaration`.
/// Example: `EngineDeclaration(BSPLayoutEngine.engineID)`
/// See also: `Engines`, `EngineConfigDeclaration`.
public struct EngineDeclaration: Codable, Equatable, Sendable {
    public let id: LayoutEngineID
    public let config: EngineConfigDeclaration?

    public init(_ id: LayoutEngineID, config: EngineConfigDeclaration? = nil) {
        self.id = id
        self.config = config
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

@resultBuilder
/// Purpose: Builds engine declarations inside `Engines { ... }`.
/// Parameters: Accepts engine expressions, arrays, and conditional branches.
/// Example: `Engines { .floating; .bsp; Monocle() }`
/// See also: `Engines`, `EngineDeclaration`.
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
