import XCTest
@testable import ollyCore

final class TagTests: XCTestCase {
    func testTagIndexBoundsAndBitValue() throws {
        let first = try Tag(index: 0)
        let last = try Tag(index: 63)

        XCTAssertEqual(first.bit, 1)
        XCTAssertEqual(last.bit, UInt64(1) << 63)
        XCTAssertThrowsError(try Tag(index: -1))
        XCTAssertThrowsError(try Tag(index: 64))
    }

    func testTagSetAlgebra() throws {
        let one = try Tag(index: 1)
        let two = try Tag(index: 2)
        let three = try Tag(index: 3)

        let left = TagSet([one, two])
        let right = TagSet([two, three])

        XCTAssertEqual(left.union(right), TagSet([one, two, three]))
        XCTAssertEqual(left.intersection(right), TagSet(two))
        XCTAssertEqual(left.subtracting(right), TagSet(one))
        XCTAssertEqual(left.symmetricDifference(right), TagSet([one, three]))
        XCTAssertTrue(left.intersects(right))
        XCTAssertFalse(TagSet(one).intersects(TagSet(three)))
    }

    func testTagSetMutationAndOrderedTags() throws {
        let zero = try Tag(index: 0)
        let five = try Tag(index: 5)
        var set = TagSet()

        XCTAssertTrue(set.insert(five))
        XCTAssertFalse(set.insert(five))
        XCTAssertTrue(set.insert(zero))
        XCTAssertEqual(set.tags, [zero, five])
        XCTAssertTrue(set.remove(five))
        XCTAssertFalse(set.remove(five))
        XCTAssertEqual(set, TagSet(zero))
    }
}
