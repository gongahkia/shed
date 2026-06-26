import CoreGraphics
import XCTest
import ollyKit
@testable import ollyLayouts

final class LayoutEngineCapabilitiesTests: XCTestCase {
    func testCapabilityFlagsUseDistinctBits() {
        let capabilities: LayoutEngineCapabilities = [
            .supportsManualSplits,
            .supportsResizing,
            .supportsFloatingMix
        ]

        XCTAssertTrue(capabilities.contains(.supportsManualSplits))
        XCTAssertTrue(capabilities.contains(.supportsResizing))
        XCTAssertTrue(capabilities.contains(.supportsFloatingMix))
        XCTAssertEqual(capabilities.rawValue, 0b111)
    }

    func testDefaultLayoutEngineCapabilitiesAreEmpty() {
        let engine = CapabilityStubEngine()

        XCTAssertTrue(engine.capabilities.isEmpty)
    }

    func testFloatingEngineDeclaresFloatingAndResizeCapabilities() {
        let engine = FloatingLayoutEngine()

        XCTAssertTrue(engine.capabilities.contains(.supportsResizing))
        XCTAssertTrue(engine.capabilities.contains(.supportsFloatingMix))
        XCTAssertFalse(engine.capabilities.contains(.supportsManualSplits))
    }
}

private struct CapabilityStubEngine: LayoutEngine {
    struct Config {}

    let id = FloatingLayoutEngine.engineID
    let displayName = "Stub"
    let config = Config()

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        []
    }
}
