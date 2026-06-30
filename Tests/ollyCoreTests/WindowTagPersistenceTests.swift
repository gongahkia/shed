import CoreGraphics
import Foundation
import XCTest
import ollyKit
@testable import ollyCore

final class WindowTagPersistenceTests: XCTestCase {
    func testSaveAndLoadStateJSON() async throws {
        let one = try Tag(index: 1)
        let stateURL = temporaryStateURL()
        let persistence = WindowTagPersistence(stateURL: stateURL)
        let rule = try WindowTagRule(
            processID: 42,
            bundleID: "com.example.App",
            titleRegex: "^Docs",
            tags: TagSet(one)
        )

        try await persistence.save(WindowTagPersistenceState(rules: [rule]))
        let loaded = try await persistence.load()
        let json = try String(contentsOf: stateURL, encoding: .utf8)

        XCTAssertEqual(loaded.rules, [rule])
        XCTAssertTrue(json.contains("\"version\" : 2"))
        XCTAssertTrue(json.contains("\"tags\" : 2"))
        XCTAssertTrue(json.contains("\"layoutOrders\""))
    }

    func testUpsertReplacesExistingRule() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let persistence = WindowTagPersistence(stateURL: temporaryStateURL())
        let first = try WindowTagRule(processID: 42, bundleID: "com.example.App", titleRegex: "^Docs", tags: TagSet(one))
        let second = try WindowTagRule(processID: 42, bundleID: "com.example.App", titleRegex: "^Docs", tags: TagSet(two))

        try await persistence.upsert(first)
        try await persistence.upsert(second)
        let loaded = try await persistence.load()

        XCTAssertEqual(loaded.rules, [second])
    }

    func testTagsPreferPIDButFallbackToBundleAndTitleRegex() throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let first = try WindowTagRule(processID: 42, bundleID: "com.example.App", titleRegex: "^Docs", tags: TagSet(one))
        let second = try WindowTagRule(processID: 43, bundleID: "com.example.App", titleRegex: "^Docs", tags: TagSet(two))
        let state = WindowTagPersistenceState(rules: [first, second])

        XCTAssertEqual(state.tags(processID: 43, bundleID: "com.example.App", title: "Docs 2"), TagSet(two))
        XCTAssertEqual(state.tags(processID: 99, bundleID: "com.example.App", title: "Docs 2"), TagSet(one))
        XCTAssertNil(state.tags(processID: 99, bundleID: "com.example.Other", title: "Docs 2"))
    }

    func testExactTitleRuleEscapesRegexCharacters() throws {
        let one = try Tag(index: 1)
        let window = WindowState(
            id: 1,
            processID: 42,
            displayID: 1,
            tagMask: TagSet(one).rawValue,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100),
            title: "a+b"
        )

        let rule = try WindowTagRule.exactTitleRule(window: window, bundleID: "com.example.App")

        XCTAssertTrue(rule.matches(processID: 99, bundleID: "com.example.App", title: "a+b"))
        XCTAssertFalse(rule.matches(processID: 99, bundleID: "com.example.App", title: "aaab"))
    }

    func testLayoutOrderPersistsByStableWindowIdentity() async throws {
        let stateURL = temporaryStateURL()
        let persistence = WindowTagPersistence(stateURL: stateURL)
        let window = WindowState(
            id: 100,
            processID: 42,
            bundleID: "com.example.App",
            displayID: 7,
            tagMask: 3,
            layoutOrder: 2,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100),
            title: " Docs ",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )
        let restartedWindow = WindowState(
            id: 200,
            processID: 99,
            bundleID: "com.example.App",
            displayID: 7,
            tagMask: 3,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100),
            title: "Docs",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )

        try await persistence.upsertLayoutOrders(for: [window])
        let layoutOrder = try await persistence.layoutOrder(for: restartedWindow)

        XCTAssertEqual(layoutOrder, 2)
    }

    func testInvalidRegexThrows() {
        XCTAssertThrowsError(
            try WindowTagRule(processID: 42, bundleID: nil, titleRegex: "[", tags: [])
        )
    }

    private func temporaryStateURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("state.json")
    }
}

final class WindowTagPersistenceMigrationTests: XCTestCase {
    func testV1StateMigratesToV2DefaultsAndWritesBack() async throws {
        let stateURL = temporaryStateURL()
        try FileManager.default.createDirectory(
            at: stateURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data(
            """
            {
              "version" : 1,
              "rules" : [
                {
                  "processID" : 42,
                  "bundleID" : "com.example.App",
                  "titleRegex" : "^Docs",
                  "tags" : 2
                }
              ],
              "layoutOrders" : []
            }
            """.utf8
        ).write(to: stateURL)
        let persistence = WindowTagPersistence(stateURL: stateURL)

        let state = try await persistence.load()
        let rule = try XCTUnwrap(state.rules.first)
        let migratedJSON = try String(contentsOf: stateURL, encoding: .utf8)

        XCTAssertEqual(state.version, 2)
        XCTAssertFalse(rule.isSticky)
        XCTAssertFalse(rule.isPinned)
        XCTAssertNil(rule.engineOverride)
        XCTAssertTrue(migratedJSON.contains("\"version\" : 2"))
        XCTAssertTrue(migratedJSON.contains("\"isSticky\" : false"))
        XCTAssertTrue(migratedJSON.contains("\"isPinned\" : false"))
    }

    private func temporaryStateURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("state.json")
    }
}
