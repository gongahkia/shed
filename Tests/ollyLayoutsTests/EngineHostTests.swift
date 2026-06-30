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
        let eventRecorder = EngineHostEventRecorder()
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { window, placement in
                await recorder.record(windowID: window.id, placement: placement)
            },
            publishEvent: { event in
                await eventRecorder.record(event)
            }
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(tag).rawValue))

        let first = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let second = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs
        let events = await eventRecorder.events

        XCTAssertEqual(first.engineID, engineID)
        XCTAssertEqual(first.placements.map(\.windowID), [1, 2])
        XCTAssertEqual(first.appliedPlacements.map(\.windowID), [1, 2])
        XCTAssertEqual(
            first.events,
            [
                .arranged(
                    EngineArrangedEvent(
                        displayID: 1,
                        engineID: engineID,
                        placementCount: 2,
                        appliedPlacementCount: 2
                    )
                )
            ]
        )
        XCTAssertTrue(second.appliedPlacements.isEmpty)
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(recordedWindowIDs, [1, 2])
    }

    func testArrangeFallsBackToFloatingWhenNoEngineIsBound() async throws {
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
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue, frame: frame))

        let result = try await host.arrange(displayID: 1, bounds: .zero)

        XCTAssertEqual(result.engineID, FloatingLayoutEngine.engineID)
        XCTAssertEqual(result.placements, [Placement(windowID: 1, frame: frame, zOrder: 0, hidden: false)])
    }

    func testArrangeExcludesFloatingWindows() async throws {
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
        await windowStore.upsert(window(id: 2, tagMask: TagSet(tag).rawValue, isFloating: true))

        let result = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(result.placements.map(\.windowID), [1])
        XCTAssertEqual(recordedWindowIDs, [1])
    }

    func testArrangeUsesUnionOfActiveTags() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let three = try Tag(index: 3)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet([one, two]))
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
        await tagStore.bindEngine(engineID, to: one, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(one).rawValue))
        await windowStore.upsert(window(id: 2, tagMask: TagSet(two).rawValue))
        await windowStore.upsert(window(id: 3, tagMask: TagSet([one, two]).rawValue))
        await windowStore.upsert(window(id: 4, tagMask: TagSet(three).rawValue))

        let result = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let recordedWindowIDs = await recorder.windowIDs

        XCTAssertEqual(result.placements.map(\.windowID), [1, 2, 3])
        XCTAssertEqual(recordedWindowIDs, [1, 2, 3])
    }

    func testArrangeUsesLowestActiveTagEngineBinding() async throws {
        let lowerTag = try Tag(index: 1)
        let higherTag = try Tag(index: 3)
        let lowerEngineID = LayoutEngineID(rawValue: "lower")
        let higherEngineID = LayoutEngineID(rawValue: "higher")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet([higherTag, lowerTag]))
        let registry = try LayoutEngineRegistry(factories: [
            AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: lowerEngineID)),
            AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: higherEngineID))
        ])
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { _, _ in }
        )
        await tagStore.bindEngine(higherEngineID, to: higherTag, on: 1)
        await tagStore.bindEngine(lowerEngineID, to: lowerTag, on: 1)

        let result = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))

        XCTAssertEqual(result.engineID, lowerEngineID)
    }

    func testArrangeWithDisplayUsesSafeZoneLayoutFrame() async throws {
        let tag = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "fixed")
        let windowStore = WindowStore()
        let tagStore = TagStore(defaultActiveTags: TagSet(tag))
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(FixedLayoutEngineFactory(id: engineID))])
        let host = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { _ in FixedLayoutEngine.Config(width: 320) },
            applyPlacement: { _, _ in }
        )
        let display = Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 800, height: 600),
            visibleFrame: CGRect(x: 0, y: 0, width: 800, height: 576),
            safeAreaInsets: DisplaySafeAreaInsets(top: 40),
            scaleFactor: 2,
            localizedName: "Display",
            isMain: true
        )
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))

        let result = try await host.arrange(display: display, safeZones: SafeZoneCalculator(notchPadding: 12))

        XCTAssertEqual(result.placements, [
            Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 320, height: 548))
        ])
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

    func testRestoreAfterWakeReappliesLastPlacementSnapshot() async throws {
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

        _ = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))
        let restoreResult = await host.restoreAfterWake(displayID: 1)
        let result = try XCTUnwrap(restoreResult)
        let windowIDs = await recorder.windowIDs

        XCTAssertEqual(result.engineID, engineID)
        XCTAssertEqual(result.restoredPlacementCount, 1)
        XCTAssertTrue(result.isWithinTarget)
        XCTAssertEqual(windowIDs, [1, 1])
    }

    func testWakeRestoreTaskRespondsToInjectedWakeStream() async throws {
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
        let wakeStream = AsyncStream<Void> { continuation in
            continuation.yield(())
            continuation.finish()
        }
        await tagStore.bindEngine(engineID, to: tag, on: 1)
        await windowStore.upsert(window(id: 1, tagMask: TagSet(tag).rawValue))
        _ = try await host.arrange(displayID: 1, bounds: CGRect(x: 0, y: 0, width: 800, height: 600))

        let task = host.startWakeRestore(displayID: 1, wakeEvents: wakeStream)
        defer {
            task.cancel()
        }

        try await waitUntil {
            await recorder.windowIDs == [1, 1]
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

    private func window(
        id: WindowID,
        tagMask: UInt64,
        isFloating: Bool = false,
        frame: CGRect = CGRect(x: 0, y: 0, width: 100, height: 100)
    ) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: 1,
            tagMask: tagMask,
            isFloating: isFloating,
            frame: frame
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

private actor EngineHostEventRecorder {
    private(set) var events: [EngineEvent] = []

    func record(_ event: EngineEvent) {
        events.append(event)
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
