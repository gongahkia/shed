import XCTest
import ollyLayouts
@testable import ollyDSL

final class EngineDSLTests: XCTestCase {
    func testEngineBuilderCollectsBuiltIns() {
        let engines = Engines {
            EngineDeclaration.floating
            EngineDeclaration.masterStack
            EngineDeclaration.manual
            EngineDeclaration.bsp
            EngineDeclaration.niriScroll
            EngineDeclaration.monocle
            EngineDeclaration.spiral
            EngineDeclaration.grid
            EngineDeclaration.threeCol
            EngineDeclaration.accordion
            EngineDeclaration.tabbed
        }

        XCTAssertEqual(
            engines.engines,
            [
                .floating,
                .masterStack,
                .manual,
                .bsp,
                .niriScroll,
                .monocle,
                .spiral,
                .grid,
                .threeCol,
                .accordion,
                .tabbed
            ]
        )
    }

    func testEngineBuilderCollectsTierOneFunctionBindings() {
        let engines = Engines {
            Monocle()
            Spiral()
            Grid(.squareish)
            ThreeCol(masterRatio: 0.5)
            Accordion()
            Tabbed(tabBarHeight: 30)
        }

        XCTAssertEqual(
            engines.engines,
            [
                EngineDeclaration(
                    MonocleLayoutEngine.engineID,
                    config: .monocle(MonocleLayoutEngine.Config())
                ),
                EngineDeclaration(
                    SpiralLayoutEngine.engineID,
                    config: .spiral(SpiralLayoutEngine.Config())
                ),
                EngineDeclaration(
                    GridLayoutEngine.engineID,
                    config: .grid(GridLayoutEngine.Config(policy: .squareish))
                ),
                EngineDeclaration(
                    ThreeColLayoutEngine.engineID,
                    config: .threeCol(ThreeColLayoutEngine.Config(masterRatio: 0.5))
                ),
                EngineDeclaration(
                    AccordionLayoutEngine.engineID,
                    config: .accordion(AccordionLayoutEngine.Config())
                ),
                EngineDeclaration(
                    TabbedLayoutEngine.engineID,
                    config: .tabbed(TabbedLayoutEngine.Config(tabBarHeight: 30))
                )
            ]
        )
    }

    func testParameterizedTierOneBindingsStoreConfigs() {
        let engines = Engines {
            Spiral(splitRatio: 0.5)
            Grid(.fixedRows(2))
            ThreeCol(masterRatio: 0.6)
            Accordion(stripHeight: 36)
        }

        XCTAssertEqual(engines.engines[0].config, .spiral(SpiralLayoutEngine.Config(splitRatio: 0.5)))
        XCTAssertEqual(engines.engines[1].config, .grid(GridLayoutEngine.Config(policy: .fixedRows(2))))
        XCTAssertEqual(engines.engines[2].config, .threeCol(ThreeColLayoutEngine.Config(masterRatio: 0.6)))
        XCTAssertEqual(engines.engines[3].config, .accordion(AccordionLayoutEngine.Config(stripHeight: 36)))
    }

    func testEngineDeclarationCodablePreservesTypedConfig() throws {
        let declaration = Grid(.fixedCols(3))

        let data = try JSONEncoder().encode(declaration)
        let decoded = try JSONDecoder().decode(EngineDeclaration.self, from: data)

        XCTAssertEqual(decoded, declaration)
    }

    func testConfigStoresEngineSection() {
        let config = Config {
            Engines {
                EngineDeclaration.masterStack
                EngineDeclaration.bsp
            }
        }

        XCTAssertEqual(config.engines.engines, [.masterStack, .bsp])
    }
}
