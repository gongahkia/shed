import XCTest
@testable import ollyDSL

final class EngineDSLTests: XCTestCase {
    func testEngineBuilderCollectsBuiltIns() {
        let engines = Engines {
            EngineDeclaration.floating
            EngineDeclaration.masterStack
            EngineDeclaration.manual
            EngineDeclaration.bsp
            EngineDeclaration.niriScroll
        }

        XCTAssertEqual(
            engines.engines,
            [.floating, .masterStack, .manual, .bsp, .niriScroll]
        )
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
