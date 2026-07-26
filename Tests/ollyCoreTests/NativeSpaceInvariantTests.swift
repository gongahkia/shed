import CoreGraphics
import XCTest
import ollyKit
@testable import ollyCore

final class NativeSpaceInvariantTests: XCTestCase {
    func testVerifiedWhenAllManagedWindowsShareSpace() async {
        let store = WindowStore()
        await store.upsert(window(id: 1))
        await store.upsert(window(id: 2))
        let invariant = NativeSpaceInvariant(
            windowStore: store,
            spaceProvider: FixedNativeSpaceProvider(spaceIDs: [1: NativeSpaceID(rawValue: 9), 2: NativeSpaceID(rawValue: 9)])
        )

        let result = await invariant.verify()

        XCTAssertTrue(result.isVerified)
        XCTAssertEqual(result.expectedSpaceID, NativeSpaceID(rawValue: 9))
        XCTAssertTrue(result.issues.isEmpty)
    }

    func testDriftCanRehomeWindow() async {
        let store = WindowStore()
        let recorder = NativeSpaceActionRecorder()
        await store.upsert(window(id: 1))
        await store.upsert(window(id: 2))
        let invariant = NativeSpaceInvariant(
            windowStore: store,
            spaceProvider: FixedNativeSpaceProvider(spaceIDs: [1: NativeSpaceID(rawValue: 9), 2: NativeSpaceID(rawValue: 10)]),
            driftPolicy: .rehome,
            rehomeWindow: { window, spaceID in
                await recorder.recordRehome(windowID: window.id, spaceID: spaceID)
                return true
            }
        )

        let result = await invariant.verify()
        let rehomes = await recorder.rehomes

        XCTAssertEqual(result.issues, [.drifted(windowID: 2, expected: NativeSpaceID(rawValue: 9), actual: NativeSpaceID(rawValue: 10))])
        XCTAssertEqual(result.rehomedWindowIDs, [2])
        XCTAssertEqual(rehomes, [NativeSpaceActionRecorder.Rehome(windowID: 2, spaceID: NativeSpaceID(rawValue: 9))])
    }

    func testDriftCanUnmanageWindow() async {
        let store = WindowStore()
        let recorder = NativeSpaceActionRecorder()
        await store.upsert(window(id: 1))
        await store.upsert(window(id: 2))
        let invariant = NativeSpaceInvariant(
            windowStore: store,
            spaceProvider: FixedNativeSpaceProvider(spaceIDs: [1: NativeSpaceID(rawValue: 9), 2: NativeSpaceID(rawValue: 10)]),
            driftPolicy: .unmanage,
            unmanageWindow: { window in
                await recorder.recordUnmanage(windowID: window.id)
            }
        )

        let result = await invariant.verify()
        let removedWindow = await store.state(for: 2)
        let unmanagedWindowIDs = await recorder.unmanagedWindowIDs

        XCTAssertEqual(result.unmanagedWindowIDs, [2])
        XCTAssertNil(removedWindow)
        XCTAssertEqual(unmanagedWindowIDs, [2])
    }

    func testDriftCanFollowWindowOffSpace() async {
        let store = WindowStore()
        await store.upsert(window(id: 1))
        await store.upsert(window(id: 2))
        let invariant = NativeSpaceInvariant(
            windowStore: store,
            spaceProvider: FixedNativeSpaceProvider(spaceIDs: [1: NativeSpaceID(rawValue: 9), 2: NativeSpaceID(rawValue: 10)])
        )

        let result = await invariant.verify()
        let followedWindow = await store.state(for: 2)

        XCTAssertEqual(result.offSpaceWindowIDs, [2])
        XCTAssertEqual(result.unmanagedWindowIDs, [])
        XCTAssertEqual(followedWindow?.isOffSpace, true)
    }

    func testPublicProviderReportsUnknownSpace() async {
        let store = WindowStore()
        await store.upsert(window(id: 1))
        let invariant = NativeSpaceInvariant(windowStore: store)

        let result = await invariant.verify()

        XCTAssertFalse(result.isVerified)
        XCTAssertFalse(result.isProviderSupported)
        XCTAssertNil(result.expectedSpaceID)
        XCTAssertEqual(result.issues, [.unsupportedNativeSpaces(windowID: 1)])
    }

    private func window(id: WindowID) -> WindowState {
        WindowState(
            id: id,
            processID: 42,
            displayID: 1,
            tagMask: 0,
            frame: CGRect(x: 0, y: 0, width: 100, height: 100)
        )
    }
}

private struct FixedNativeSpaceProvider: WindowNativeSpaceProviding {
    let spaceIDs: [WindowID: NativeSpaceID]

    func nativeSpaceID(for window: WindowState) async -> NativeSpaceID? {
        spaceIDs[window.id]
    }
}

private actor NativeSpaceActionRecorder {
    struct Rehome: Equatable {
        let windowID: WindowID
        let spaceID: NativeSpaceID
    }

    private(set) var rehomes: [Rehome] = []
    private(set) var unmanagedWindowIDs: [WindowID] = []

    func recordRehome(windowID: WindowID, spaceID: NativeSpaceID) {
        rehomes.append(Rehome(windowID: windowID, spaceID: spaceID))
    }

    func recordUnmanage(windowID: WindowID) {
        unmanagedWindowIDs.append(windowID)
    }
}
