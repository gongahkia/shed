public enum TagError: Error, Equatable, Sendable {
    case indexOutOfRange(Int)
}

public struct Tag: Codable, Comparable, Hashable, Sendable {
    public let index: UInt8

    public init(index: Int) throws {
        guard (0..<64).contains(index) else {
            throw TagError.indexOutOfRange(index)
        }
        self.index = UInt8(index)
    }

    public var bit: UInt64 {
        UInt64(1) << UInt64(index)
    }

    public static func < (lhs: Tag, rhs: Tag) -> Bool {
        lhs.index < rhs.index
    }

    static func unchecked(index: UInt8) -> Tag {
        precondition(index < 64)
        return Tag(uncheckedIndex: index)
    }

    private init(uncheckedIndex index: UInt8) {
        self.index = index
    }
}

public struct TagSet: Codable, ExpressibleByArrayLiteral, Hashable, OptionSet, Sendable {
    public let rawValue: UInt64

    public init(rawValue: UInt64) {
        self.rawValue = rawValue
    }

    public init(_ tag: Tag) {
        self.init(rawValue: tag.bit)
    }

    public init<S: Sequence>(_ tags: S) where S.Element == Tag {
        self.init(rawValue: tags.reduce(UInt64(0)) { $0 | $1.bit })
    }

    public init(arrayLiteral elements: Tag...) {
        self.init(elements)
    }

    public static let all = TagSet(rawValue: .max)

    public var tags: [Tag] {
        (0..<64).compactMap { index in
            let tag = Tag.unchecked(index: UInt8(index))
            return contains(tag) ? tag : nil
        }
    }

    public func contains(_ tag: Tag) -> Bool {
        contains(TagSet(tag))
    }

    public func intersects(_ other: TagSet) -> Bool {
        !intersection(other).isEmpty
    }

    public func inserting(_ tag: Tag) -> TagSet {
        union(TagSet(tag))
    }

    public func removing(_ tag: Tag) -> TagSet {
        subtracting(TagSet(tag))
    }

    @discardableResult
    public mutating func insert(_ tag: Tag) -> Bool {
        let oldValue = rawValue
        formUnion(TagSet(tag))
        return rawValue != oldValue
    }

    @discardableResult
    public mutating func remove(_ tag: Tag) -> Bool {
        let oldValue = rawValue
        subtract(TagSet(tag))
        return rawValue != oldValue
    }
}
