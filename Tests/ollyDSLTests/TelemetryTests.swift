import XCTest
@testable import ollyDSL

final class TelemetryTests: XCTestCase {
    func testTelemetryDefaultsDisabledAndScrubbed() {
        let telemetry = Telemetry()

        XCTAssertFalse(telemetry.enabled)
        XCTAssertNil(telemetry.endpoint)
        XCTAssertTrue(telemetry.scrubbedBundleIDs)
        XCTAssertFalse(Config().telemetry.enabled)
    }

    func testTelemetryDSLStoresEndpointAndPrivacySettings() throws {
        let config = Config {
            Telemetry {
                enabled(true)
                endpoint("https://crashes.example.test/olly")
                scrubbedBundleIDs(false)
            }
        }

        let data = try JSONEncoder().encode(config)
        let decoded = try JSONDecoder().decode(Config.self, from: data)

        XCTAssertTrue(decoded.telemetry.enabled)
        XCTAssertEqual(decoded.telemetry.endpoint, "https://crashes.example.test/olly")
        XCTAssertFalse(decoded.telemetry.scrubbedBundleIDs)
    }
}
