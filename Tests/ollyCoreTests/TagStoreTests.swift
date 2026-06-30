import XCTest
@testable import ollyCore

final class TagStoreTests: XCTestCase {
    func testStateCreatesDefaultPerDisplay() async throws {
        let zero = try Tag(index: 0)
        let store = TagStore(defaultActiveTags: TagSet(zero))

        let state = await store.state(for: 2)

        XCTAssertEqual(state.displayID, 2)
        XCTAssertEqual(state.activeTags, TagSet(zero))
        XCTAssertTrue(state.tagToEngine.isEmpty)
        XCTAssertEqual(state.mruHistory, [TagSet(zero)])
    }

    func testActiveTagsMaintainBoundedMRUHistory() async throws {
        let zero = try Tag(index: 0)
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let store = TagStore(defaultActiveTags: TagSet(zero), maxHistoryCount: 3)

        await store.setActiveTags(TagSet(one), on: 1)
        await store.setActiveTags(TagSet(two), on: 1)
        await store.setActiveTags(TagSet(one), on: 1)
        await store.setActiveTags([], on: 1)

        let state = await store.state(for: 1)
        XCTAssertTrue(state.activeTags.isEmpty)
        XCTAssertEqual(state.mruHistory, [TagSet(one), TagSet(two), TagSet(zero)])
    }

    func testEngineBindingsArePerDisplay() async throws {
        let one = try Tag(index: 1)
        let engineID = LayoutEngineID(rawValue: "master-stack")
        let store = TagStore()

        await store.bindEngine(engineID, to: one, on: 1)
        let boundEngineID = await store.engine(for: one, on: 1)
        let unboundDisplayEngineID = await store.engine(for: one, on: 2)

        XCTAssertEqual(boundEngineID, engineID)
        XCTAssertNil(unboundDisplayEngineID)

        await store.unbindEngine(for: one, on: 1)
        let removedEngineID = await store.engine(for: one, on: 1)
        XCTAssertNil(removedEngineID)
    }

    func testGlobalVisibleTagsUnionActiveTagsAcrossDisplays() async throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let three = try Tag(index: 3)
        let store = TagStore()

        await store.setActiveTags(TagSet([one, three]), on: 1)
        await store.setActiveTags(TagSet(two), on: 2)

        let hasOne = await store.anyDisplayHasTagActive(one)
        let hasTwo = await store.anyDisplayHasTagActive(two)
        let hasFour = await store.anyDisplayHasTagActive(try Tag(index: 4))
        let globalTags = await store.globallyVisibleTagSet()

        XCTAssertTrue(hasOne)
        XCTAssertTrue(hasTwo)
        XCTAssertFalse(hasFour)
        XCTAssertEqual(globalTags, TagSet([one, two, three]))
    }

    func testAllStatesAreSortedByDisplayIDAndRemovable() async throws {
        let one = try Tag(index: 1)
        let store = TagStore()

        await store.setActiveTags(TagSet(one), on: 3)
        await store.setActiveTags(TagSet(one), on: 1)
        let displayIDs = await store.allStates().map(\.displayID)
        XCTAssertEqual(displayIDs, [1, 3])

        await store.removeDisplay(1)
        let remainingDisplayIDs = await store.allStates().map(\.displayID)
        XCTAssertEqual(remainingDisplayIDs, [3])
    }
}
