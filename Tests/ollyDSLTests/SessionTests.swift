import XCTest
@testable import ollyDSL

final class SessionTests: XCTestCase {
    func testSessionDefaultsRestoreOnLaunchOff() {
        XCTAssertFalse(Session().restoreOnLaunch)
        XCTAssertFalse(Config().session.restoreOnLaunch)
    }

    func testSessionDSLStoresRestoreOnLaunch() throws {
        let config = Config {
            Session {
                restoreOnLaunch(true)
            }
        }

        let data = try JSONEncoder().encode(config)
        let decoded = try JSONDecoder().decode(Config.self, from: data)

        XCTAssertTrue(config.session.restoreOnLaunch)
        XCTAssertTrue(decoded.session.restoreOnLaunch)
    }
}
