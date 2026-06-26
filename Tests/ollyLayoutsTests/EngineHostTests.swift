import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class EngineHostTests: XCTestCase {
    func testArrangeInvokesBoundEngineAndDiffsPlacements() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let recorder = EngineHostPlacementRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(tag).rawValue))

        let first = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let second = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(first.engineID, engineID)
        XCTAssertEqual(first.placements.map(\.windowID), [1, 2])
        XCTAssertEqual(first.appliedPlacements.map(\.windowID), [1, 2])
        XCTAssertTrue(second.appliedPlacements.isEmpty)
        XCTAssertEqual(recordedWindowIDs, [1, 2])
    }

    func testArrangeThrowsWhenNoEngineIsBound() async throws {
        let tag = try Tag(index: 1)
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in nil },
            applyPlacement: { _, _ in }
        )

        do {
            _ = try await host.arrange(displayID: 1, bounds: .zero)
            XCTFail("expected missing engine binding")
        } catch EngineHostError.missingEngineBinding(1, TagSet(tag)) {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testStartSubscribesToWindowAndTagChanges() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let recorder = EngineHostPlacementRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        let task = host.start(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        defer {
            task.cancel()
        }

        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        try await waitUntil {
            await recorder.windowIDs == [1]
        }
    }

    private func waitUntil(
        timeoutNanoseconds: UInt64 = 1_000_000_000,
        condition: () async -> Bool
    ) async throws {
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if await condition() {
                return
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("timed out waiting for condition")
    }

    private func window(id: WindowID, tagMask: UInt64) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: 1,
            tagMask: tagMask,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100)
        )
    }
}

private actor EngineHostPlacementRecorder {
    private(set) var records: [(windowID: WindowID, placement: Placement)] = []

    var windowIDs: [WindowID] {
        records.map(\.windowID)
    }

    func record(windowID: WindowID, placement: Placement) {
        records.append((windowID, placement))
    }
}

private struct FixedLayoutEngine: LayoutEngine {
    struct Config {
        let width: CGFloat
    }

    let id: LayoutEngineID
    let displayName = "Fixed"
    let config: Config

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        windows.map { window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(x: bounds.minX, y: bounds.minY, width: config.width, height: bounds.height)
            )
        }
    }
}

private struct FixedLayoutEngineFactory: LayoutEngineFactory {
    let id: LayoutEngineID
    let displayName = "Fixed"

    func makeEngine(config: FixedLayoutEngine.Config) throws -> FixedLayoutEngine {
        FixedLayoutEngine(id: id, config: config)
    }
}
