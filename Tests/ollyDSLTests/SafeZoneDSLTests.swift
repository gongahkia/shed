import CoreGraphics
import XCTest
import ollyKit
@testable import ollyDSL

final class SafeZoneDSLTests: XCTestCase {
    func testSafeZonesCollectNotchPaddingAndReserves() {
        let rect = CGRect(x: 0, y: 900, width: 1512, height: 82)
        let safeZones = SafeZones {
            notchPadding(16)
            reserve(rect: rect, on: 42)
        }

        XCTAssertEqual(safeZones.notchPadding, 16)
        XCTAssertEqual(safeZones.reserves, [SafeZoneReservation(rect: rect, displayID: 42)])
    }

    func testConfigStoresSafeZoneSection() {
        let rect = CGRect(x: 0, y: 500, width: 800, height: 100)
        let config = Config {
            SafeZones {
                reserve(rect: rect, on: 1)
            }
        }

        XCTAssertEqual(config.safeZones.reserves, [SafeZoneReservation(rect: rect, displayID: 1)])
    }

    func testSafeZonesBuildCalculator() {
        let display = Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 800, height: 600),
            visibleFrame: CGRect(x: 0, y: 0, width: 800, height: 600),
            scaleFactor: 2,
            localizedName: "Display",
            isMain: true
        )
        let config = Config {
            SafeZones {
                reserve(rect: CGRect(x: 0, y: 500, width: 800, height: 100), on: 1)
            }
        }

        let result = config.safeZones.calculator().result(for: display)

        XCTAssertEqual(result.layoutFrame, CGRect(x: 0, y: 0, width: 800, height: 500))
        XCTAssertEqual(result.reserves.map(\.kind), [.user])
    }
}
