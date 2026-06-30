import CoreGraphics
import Foundation
import XCTest
import ollyKit
@testable import ollyCore

final class WindowRecoveryJournalTests: XCTestCase {
    func testMissingJournalLoadsEmptyState() async throws {
        let journal = WindowRecoveryJournal(stateURL: temporaryStateURL())

        let state = try await journal.load()

        XCTAssertEqual(state, WindowRecoveryJournalState())
    }

    func testRecordsReplacesAndRemovesWindowEntries() async throws {
        let stateURL = temporaryStateURL()
        let journal = WindowRecoveryJournal(stateURL: stateURL)
        let first = window(id: 7, frame: CGRect(x: 10, y: 20, width: 300, height: 200))
        let second = window(
            id: 7,
            frame: CGRect(x: 30, y: 40, width: 320, height: 220),
            isSticky: true,
            engineOverride: LayoutEngineID(rawValue: "floating")
        )

        try await journal.record(window: first, parkedFrame: CGRect(x: -32_000, y: -32_000, width: 300, height: 200))
        try await journal.record(window: second, parkedFrame: CGRect(x: -33_000, y: -33_000, width: 320, height: 220))
        var state = try await journal.load()

        XCTAssertEqual(state.entries.map(\.windowID), [7])
        XCTAssertEqual(state.entries.first?.originalFrame.cgRect, second.frame)
        XCTAssertEqual(state.entries.first?.parkedFrame.cgRect.origin.x, -33_000)
        XCTAssertEqual(state.entries.first?.isSticky, true)
        XCTAssertEqual(state.entries.first?.engineOverride, LayoutEngineID(rawValue: "floating"))

        try await journal.remove(windowID: 7)
        state = try await journal.load()

        XCTAssertTrue(state.entries.isEmpty)
    }

    func testCorruptedJournalThrows() async throws {
        let stateURL = temporaryStateURL()
        try FileManager.default.createDirectory(
            at: stateURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data("not-json".utf8).write(to: stateURL)
        let journal = WindowRecoveryJournal(stateURL: stateURL)

        do {
            _ = try await journal.load()
            XCTFail("expected corrupted journal to throw")
        } catch {
            // expected
        }
    }

    private func temporaryStateURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("recovery.json")
    }

    private func window(
        id: WindowID,
        frame: CGRect,
        isSticky: Bool = false,
        engineOverride: LayoutEngineID? = nil
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            bundleID: "com.example.App",
            displayID: 1,
            tagMask: 1,
            isSticky: isSticky,
            engineOverride: engineOverride,
            frame: frame,
            title: "window \(id)",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )
    }
}

final class WindowRecoveryJournalMigrationTests: XCTestCase {
    func testV1JournalMigratesToV2DefaultsAndWritesBack() async throws {
        let stateURL = temporaryStateURL()
        try FileManager.default.createDirectory(
            at: stateURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data(
            """
            {
              "version" : 1,
              "entries" : [
                {
                  "windowID" : 7,
                  "processID" : 42,
                  "bundleID" : "com.example.App",
                  "title" : "window 7",
                  "role" : "AXWindow",
                  "subrole" : "AXStandardWindow",
                  "displayID" : 1,
                  "tagMask" : 1,
                  "originalFrame" : { "x" : 10, "y" : 20, "width" : 300, "height" : 200 },
                  "parkedFrame" : { "x" : -32000, "y" : -32000, "width" : 300, "height" : 200 },
                  "updatedAt" : "2026-06-30T00:00:00Z"
                }
              ]
            }
            """.utf8
        ).write(to: stateURL)
        let journal = WindowRecoveryJournal(stateURL: stateURL)

        let state = try await journal.load()
        let entry = try XCTUnwrap(state.entries.first)
        let migratedJSON = try String(contentsOf: stateURL, encoding: .utf8)

        XCTAssertEqual(state.version, 2)
        XCTAssertFalse(entry.isSticky)
        XCTAssertFalse(entry.isFullscreen)
        XCTAssertNil(entry.engineOverride)
        XCTAssertTrue(migratedJSON.contains("\"version\" : 2"))
        XCTAssertTrue(migratedJSON.contains("\"isSticky\" : false"))
        XCTAssertTrue(migratedJSON.contains("\"isFullscreen\" : false"))
    }

    private func temporaryStateURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("recovery.json")
    }
}
