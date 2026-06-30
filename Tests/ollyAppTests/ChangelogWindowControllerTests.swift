import Foundation
import XCTest
@testable import ollyApp

final class ChangelogWindowControllerTests: XCTestCase {
    func testVersionStoreShowsOncePerVersion() throws {
        let suiteName = "dev.olly.changelog.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let store = ChangelogVersionStore(defaults: defaults)

        XCTAssertTrue(store.shouldShow(version: "1.0"))

        store.markShown(version: "1.0")

        XCTAssertFalse(store.shouldShow(version: "1.0"))
        XCTAssertTrue(store.shouldShow(version: "1.1"))
    }

    func testBundledChangelogMarkdownLoads() throws {
        let markdown = try XCTUnwrap(ChangelogResources.loadMarkdown())

        XCTAssertTrue(markdown.contains("# Olly Changelog"))
    }

    func testMarkdownRendererKeepsTextContent() {
        let rendered = ChangelogMarkdownRenderer.render("# Title\n\n- Item")

        XCTAssertTrue(rendered.string.contains("Title"))
        XCTAssertTrue(rendered.string.contains("Item"))
    }
}
