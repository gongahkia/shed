import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class ScratchpadRegistryTests: XCTestCase {
    func testEntriesRoundTripToDisk() async throws {
        let fixture = try ScratchpadRegistryFixture()
        defer { fixture.cleanup() }
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let registry = ScratchpadRegistry(stateURL: fixture.stateURL)

        try await registry.upsert(ScratchpadEntry(
            name: "term",
            bundleID: "com.apple.Terminal",
            titleRegex: "Scratch",
            role: "AXWindow",
            lastVisibleFrame: WindowRecoveryFrame(frame),
            isVisible: false
        ))

        let reloaded = ScratchpadRegistry(stateURL: fixture.stateURL)
        let entries = try await reloaded.entries()
        XCTAssertEqual(entries, [
            ScratchpadEntry(
                name: "term",
                bundleID: "com.apple.Terminal",
                titleRegex: "Scratch",
                role: "AXWindow",
                lastVisibleFrame: WindowRecoveryFrame(frame),
                isVisible: false
            )
        ])
    }

    func testMatchingEntryUsesBundleRoleAndTitleRegex() async throws {
        let fixture = try ScratchpadRegistryFixture()
        defer { fixture.cleanup() }
        let registry = ScratchpadRegistry(stateURL: fixture.stateURL)
        try await registry.upsert(ScratchpadEntry(
            name: "term",
            bundleID: "com.apple.Terminal",
            titleRegex: "^Scratch",
            role: "AXWindow"
        ))

        let matching = try await registry.matchingEntry(for: window(
            bundleID: "com.apple.Terminal",
            title: "Scratch shell",
            role: "AXWindow"
        ))
        let wrongTitle = try await registry.matchingEntry(for: window(
            bundleID: "com.apple.Terminal",
            title: "Main shell",
            role: "AXWindow"
        ))

        XCTAssertEqual(matching?.name, "term")
        XCTAssertNil(wrongTitle)
    }

    func testInvalidRegexIsRejected() async throws {
        let fixture = try ScratchpadRegistryFixture()
        defer { fixture.cleanup() }
        let registry = ScratchpadRegistry(stateURL: fixture.stateURL)

        do {
            try await registry.upsert(ScratchpadEntry(name: "bad", titleRegex: "["))
            XCTFail("expected invalid regex")
        } catch {
            XCTAssertNotNil(error)
        }
    }

    private func window(bundleID: String?, title: String?, role: String?) -> WindowState {
        WindowState(
            id: 1,
            processID: 42,
            bundleID: bundleID,
            displayID: 1,
            tagMask: 1,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100),
            title: title,
            role: role
        )
    }
}

private struct ScratchpadRegistryFixture {
    let directoryURL: URL
    let stateURL: URL

    init() throws {
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("olly-scratchpad-registry-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        stateURL = directoryURL.appendingPathComponent("scratchpads.json")
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directoryURL)
    }
}
