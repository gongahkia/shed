import Foundation
import XCTest
import ollyDSL
import ollyKit
@testable import ollyApp

@MainActor
final class FirstRunWindowControllerTests: XCTestCase {
    func testCompleteSetupWritesSelectedProfileConfig() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("olly-first-run-\(UUID().uuidString)", isDirectory: true)
        let sourceURL = directory.appendingPathComponent("Config.swift")
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        var completed = false
        let controller = FirstRunWindowController(
            sourceURL: sourceURL,
            axStatusProvider: { .trusted },
            displayProvider: { [] }
        )
        controller.selectProfile(.minimal)
        controller.onComplete = { completed = true }

        try controller.completeSetup()

        XCTAssertTrue(completed)
        XCTAssertEqual(try String(contentsOf: sourceURL), ConfigTemplateProfile.minimal.source)
    }
}
