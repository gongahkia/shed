import Foundation
import XCTest
import ollyDiagnostics
import ollyDSL
import ollyKit
@testable import ollyApp

@MainActor
final class FirstRunWindowControllerTests: XCTestCase {
    func testCompleteSetupWritesSelectedProfileConfig() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("olly-first-run-\(UUID().uuidString)", isDirectory: true)
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let defaults = try defaults()
        let consentStore = UsageTelemetryConsentStore(defaults: defaults)
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        var completed = false
        let controller = FirstRunWindowController(
            sourceURL: sourceURL,
            axStatusProvider: { .trusted },
            displayProvider: { [] },
            usageTelemetryConsentStore: consentStore
        )
        controller.selectProfile(.minimal)
        controller.selectUsageTelemetryConsent(.optIn)
        controller.onComplete = { completed = true }

        try controller.completeSetup()

        XCTAssertTrue(completed)
        XCTAssertEqual(try String(contentsOf: sourceURL), ConfigTemplateProfile.minimal.source)
        XCTAssertEqual(consentStore.read(), .optIn)
        XCTAssertEqual(defaults.string(forKey: UsageTelemetryConsentStore.key), "opt-in")
    }

    private func defaults() throws -> UserDefaults {
        let suiteName = "olly-first-run-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suiteName)
        }
        return defaults
    }
}
