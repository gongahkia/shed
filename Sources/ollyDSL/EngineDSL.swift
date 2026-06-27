// swiftlint:disable identifier_name inclusive_language
import CoreGraphics
import Foundation
import ollyCore
import ollyLayouts

public enum EngineConfigDeclaration: Codable, Equatable, Sendable {
    case monocle(MonocleLayoutEngine.Config)
    case spiral(SpiralLayoutEngine.Config)
    case grid(GridLayoutEngine.Config)
    case threeCol(ThreeColLayoutEngine.Config)
    case accordion(AccordionLayoutEngine.Config)
}

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

/// Declares the Monocle layout engine.
///
/// Monocle expands the focused window to the display bounds and hides sibling tiled windows offscreen.
/// - Returns: An engine declaration for `MonocleLayoutEngine`.
public func Monocle() -> EngineDeclaration {
    EngineDeclaration(
        MonocleLayoutEngine.engineID,
        config: .monocle(MonocleLayoutEngine.Config())
    )
}

/// Declares the Spiral layout engine.
///
/// Spiral recursively splits the remaining rectangle using a configurable ratio.
/// - Parameter splitRatio: Ratio used for each split. Defaults to the golden ratio.
/// - Returns: An engine declaration for `SpiralLayoutEngine`.
public func Spiral(splitRatio: CGFloat = SpiralLayoutEngine.Config.goldenRatio) -> EngineDeclaration {
    EngineDeclaration(
        SpiralLayoutEngine.engineID,
        config: .spiral(SpiralLayoutEngine.Config(splitRatio: splitRatio))
    )
}

/// Declares the Grid layout engine.
///
/// Grid packs windows row-major after sorting by AX window ID.
/// - Parameter policy: Grid sizing policy, such as `.squareish`, `.fixedRows(_:)`, or `.fixedCols(_:)`.
/// - Returns: An engine declaration for `GridLayoutEngine`.
public func Grid(_ policy: GridLayoutPolicy = .squareish) -> EngineDeclaration {
    EngineDeclaration(
        GridLayoutEngine.engineID,
        config: .grid(GridLayoutEngine.Config(policy: policy))
    )
}

/// Declares the ThreeCol layout engine.
///
/// ThreeCol places the first tiled window in a centered master column and balances siblings on both sides.
/// - Parameter masterRatio: Fraction of display width reserved for the centered master column.
/// - Returns: An engine declaration for `ThreeColLayoutEngine`.
public func ThreeCol(masterRatio: CGFloat = 0.5) -> EngineDeclaration {
    EngineDeclaration(
        ThreeColLayoutEngine.engineID,
        config: .threeCol(ThreeColLayoutEngine.Config(masterRatio: masterRatio))
    )
}

/// Declares the Accordion layout engine.
///
/// Accordion expands the focused window and collapses siblings into top and bottom strips.
/// - Parameter stripHeight: Height in points for each collapsed strip.
/// - Returns: An engine declaration for `AccordionLayoutEngine`.
public func Accordion(stripHeight: CGFloat = 48) -> EngineDeclaration {
    EngineDeclaration(
        AccordionLayoutEngine.engineID,
        config: .accordion(AccordionLayoutEngine.Config(stripHeight: stripHeight))
    )
}

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
