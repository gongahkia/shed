import CoreGraphics
import Foundation
import ollyCore
import ollyKit

public enum IPCTagIndexError: Error, Equatable, Sendable {
    case outOfRange(Int)
}

public struct IPCTagIndex: Codable, Equatable, Hashable, Sendable {
    public let rawValue: UInt8

    public init(validating rawValue: Int) throws {
        guard let index = UInt8(exactly: rawValue), index < 64 else {
            throw IPCTagIndexError.outOfRange(rawValue)
        }
        self.rawValue = index
    }

    init(unchecked rawValue: UInt8) {
        precondition(rawValue < 64)
        self.rawValue = rawValue
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(Int.self)
        do {
            try self.init(validating: value)
        } catch {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "tag index must be in 0..<64"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(Int(rawValue))
    }

    static func indices(in tagSet: TagSet) -> [IPCTagIndex] {
        tagSet.tags.map { IPCTagIndex(unchecked: $0.index) }
    }
}

public struct IPCFrame: Codable, Equatable, Sendable {
    public let originX: Double
    public let originY: Double
    public let width: Double
    public let height: Double

    public init(x xCoordinate: Double, y yCoordinate: Double, width: Double, height: Double) {
        self.originX = xCoordinate
        self.originY = yCoordinate
        self.width = width
        self.height = height
    }

    public init(_ rect: CGRect) {
        self.init(
            x: Double(rect.origin.x),
            y: Double(rect.origin.y),
            width: Double(rect.size.width),
            height: Double(rect.size.height)
        )
    }

    public var cgRect: CGRect {
        CGRect(x: originX, y: originY, width: width, height: height)
    }

    private enum CodingKeys: String, CodingKey {
        case originX = "x"
        case originY = "y"
        case width
        case height
    }
}

public struct IPCWindowState: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let processID: Int32
    public let bundleID: String?
    public let displayID: DisplayID?
    public let tags: [IPCTagIndex]
    public let isFloating: Bool
    public let isSticky: Bool
    public let isPinned: Bool
    public let layoutOrder: Int?
    public let frame: IPCFrame
    public let title: String?
    public let role: String?
    public let subrole: String?

    private enum CodingKeys: String, CodingKey {
        case windowID
        case processID
        case bundleID
        case displayID
        case tags
        case isFloating
        case isSticky
        case isPinned
        case layoutOrder
        case frame
        case title
        case role
        case subrole
    }

    public init(
        windowID: WindowID,
        processID: Int32,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tags: [IPCTagIndex] = [],
        isFloating: Bool = false,
        isSticky: Bool = false,
        isPinned: Bool = false,
        layoutOrder: Int? = nil,
        frame: IPCFrame,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.windowID = windowID
        self.processID = processID
        self.bundleID = bundleID
        self.displayID = displayID
        self.tags = tags
        self.isFloating = isFloating
        self.isSticky = isSticky
        self.isPinned = isPinned
        self.layoutOrder = layoutOrder
        self.frame = frame
        self.title = title
        self.role = role
        self.subrole = subrole
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            windowID: try container.decode(WindowID.self, forKey: .windowID),
            processID: try container.decode(Int32.self, forKey: .processID),
            bundleID: try container.decodeIfPresent(String.self, forKey: .bundleID),
            displayID: try container.decodeIfPresent(DisplayID.self, forKey: .displayID),
            tags: try container.decode([IPCTagIndex].self, forKey: .tags),
            isFloating: try container.decode(Bool.self, forKey: .isFloating),
            isSticky: try container.decodeIfPresent(Bool.self, forKey: .isSticky) ?? false,
            isPinned: try container.decodeIfPresent(Bool.self, forKey: .isPinned) ?? false,
            layoutOrder: try container.decodeIfPresent(Int.self, forKey: .layoutOrder),
            frame: try container.decode(IPCFrame.self, forKey: .frame),
            title: try container.decodeIfPresent(String.self, forKey: .title),
            role: try container.decodeIfPresent(String.self, forKey: .role),
            subrole: try container.decodeIfPresent(String.self, forKey: .subrole)
        )
    }

    public init(state: WindowState) {
        self.init(
            windowID: state.id,
            processID: state.processID,
            bundleID: state.bundleID,
            displayID: state.displayID,
            tags: IPCTagIndex.indices(in: TagSet(rawValue: state.tagMask)),
            isFloating: state.isFloating,
            isSticky: state.isSticky,
            isPinned: state.isPinned,
            layoutOrder: state.layoutOrder,
            frame: IPCFrame(state.frame),
            title: state.title,
            role: state.role,
            subrole: state.subrole
        )
    }
}

public struct IPCTagEngineBinding: Codable, Equatable, Sendable {
    public let tag: IPCTagIndex
    public let engineID: LayoutEngineID

    public init(tag: IPCTagIndex, engineID: LayoutEngineID) {
        self.tag = tag
        self.engineID = engineID
    }
}

public struct IPCDisplayState: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let activeTags: [IPCTagIndex]
    public let tagEngines: [IPCTagEngineBinding]
    public let mruHistory: [[IPCTagIndex]]

    public init(
        displayID: DisplayID,
        activeTags: [IPCTagIndex] = [],
        tagEngines: [IPCTagEngineBinding] = [],
        mruHistory: [[IPCTagIndex]] = []
    ) {
        self.displayID = displayID
        self.activeTags = activeTags
        self.tagEngines = tagEngines
        self.mruHistory = mruHistory
    }

    public init(state: DisplayTagState) {
        let bindings = state.tagToEngine.map { tag, engineID in
            IPCTagEngineBinding(tag: IPCTagIndex(unchecked: tag.index), engineID: engineID)
        }.sorted { lhs, rhs in
            lhs.tag.rawValue < rhs.tag.rawValue
        }

        self.init(
            displayID: state.displayID,
            activeTags: IPCTagIndex.indices(in: state.activeTags),
            tagEngines: bindings,
            mruHistory: state.mruHistory.map(IPCTagIndex.indices)
        )
    }
}

public struct IPCStateSnapshot: Codable, Equatable, Sendable {
    public let displays: [IPCDisplayState]
    public let windows: [IPCWindowState]
    public let focusedWindowID: WindowID?

    public init(
        displays: [IPCDisplayState] = [],
        windows: [IPCWindowState] = [],
        focusedWindowID: WindowID? = nil
    ) {
        self.displays = displays
        self.windows = windows
        self.focusedWindowID = focusedWindowID
    }
}
