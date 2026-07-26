import CoreGraphics
import XCTest
import ollyKit
@testable import ollyDSL

final class SafeZoneDSLTests: XCTestCase {
    func testSafeZonesCollectNotchPaddingReservesAndCustomZones() {
        let rect = CGRect(x: 0, y: 900, width: 1512, height: 82)
        let customRect = CGRect(x: 0, y: 0, width: 378, height: 982)
        let safeZones = SafeZones {
            notchPadding(16)
            reserve(rect: rect, on: 42)
            customZone(name: "leftQuarter", rect: customRect, on: 42)
        }

        XCTAssertEqual(safeZones.notchPadding, 16)
        XCTAssertEqual(safeZones.reserves, [SafeZoneReservation(rect: rect, displayID: 42)])
        XCTAssertEqual(safeZones.customZones, [CustomSnapZone(name: "leftQuarter", rect: customRect, displayID: 42)])
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

    func testSafeZonesDecodeMissingCustomZonesAsEmpty() throws {
        let data = Data(#"{"notchPadding":20,"reserves":[]}"#.utf8)
        let safeZones = try JSONDecoder().decode(SafeZones.self, from: data)

        XCTAssertEqual(safeZones.notchPadding, 20)
        XCTAssertTrue(safeZones.customZones.isEmpty)
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

    func testSafeZonesBuildCalculatorWithDynamicReserves() {
        let display = Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 800, height: 600),
            visibleFrame: CGRect(x: 0, y: 0, width: 800, height: 600),
            scaleFactor: 2,
            localizedName: "Display",
            isMain: true
        )
        let reserve = SafeZoneReserve(
            displayID: 1,
            kind: .cooperativeApp,
            rect: CGRect(x: 0, y: 540, width: 800, height: 60)
        )

        let result = SafeZones().calculator(dynamicReserves: [reserve]).result(for: display)

        XCTAssertEqual(result.layoutFrame, CGRect(x: 0, y: 0, width: 800, height: 540))
        XCTAssertEqual(result.reserves.map(\.kind), [.cooperativeApp])
    }
}
