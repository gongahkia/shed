import CoreGraphics
import XCTest
import ollyCore
@testable import ollyDSL

final class ScratchpadTests: XCTestCase {
    func testScratchpadDSLBuildsConfigSection() throws {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)

        let config = Config {
            Scratchpads {
                Scratchpad("term") {
                    bundleID("com.apple.Terminal")
                    titleRegex("^Scratch")
                    role("AXWindow")
                    visible(false)
                    lastVisibleFrame(frame)
                }
            }
        }

        XCTAssertEqual(config.scratchpads.entries, [
            ScratchpadEntry(
                name: "term",
                bundleID: "com.apple.Terminal",
                titleRegex: "^Scratch",
                role: "AXWindow",
                lastVisibleFrame: WindowRecoveryFrame(frame),
                isVisible: false
            )
        ])
    }
}
