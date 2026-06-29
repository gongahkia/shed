import CoreGraphics
import ollyCore
import ollyKit

public struct WindowSnapshot: Equatable, Sendable {
    public let windowID: WindowID
    public let bundleID: String?
    public let frame: CGRect
    public let displayID: DisplayID?
    public let tags: TagSet
    public let isFloating: Bool
    public let layoutOrder: Int?
    public let title: String?
    public let role: String?
    public let subrole: String?

    public init(
        windowID: WindowID,
        bundleID: String? = nil,
        frame: CGRect,
        displayID: DisplayID? = nil,
        tags: TagSet = [],
        isFloating: Bool = false,
        layoutOrder: Int? = nil,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.windowID = windowID
        self.bundleID = bundleID
        self.frame = frame
        self.displayID = displayID
        self.tags = tags
        self.isFloating = isFloating
        self.layoutOrder = layoutOrder
        self.title = title
        self.role = role
        self.subrole = subrole
    }

    public init(
        windowID: WindowID,
        bundleID: String? = nil,
        frame: CGRect,
        displayID: DisplayID? = nil,
        tags: TagSet = [],
        isFloating: Bool = false,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.init(
            windowID: windowID,
            bundleID: bundleID,
            frame: frame,
            displayID: displayID,
            tags: tags,
            isFloating: isFloating,
            layoutOrder: nil,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public init(state: WindowState) {
        self.init(
            windowID: state.id,
            bundleID: state.bundleID,
            frame: state.frame,
            displayID: state.displayID,
            tags: TagSet(rawValue: state.tagMask),
            isFloating: state.isFloating,
            layoutOrder: state.layoutOrder,
            title: state.title,
            role: state.role,
            subrole: state.subrole
        )
    }

    public static func precedes(_ lhs: WindowSnapshot, _ rhs: WindowSnapshot) -> Bool {
        let lhsOrder = lhs.layoutOrder ?? Int(lhs.windowID)
        let rhsOrder = rhs.layoutOrder ?? Int(rhs.windowID)
        guard lhsOrder == rhsOrder else {
            return lhsOrder < rhsOrder
        }
        return lhs.windowID < rhs.windowID
    }
}

public protocol LayoutEngine {
    associatedtype Config

    var id: LayoutEngineID { get }
    var displayName: String { get }
    var config: Config { get }
    var capabilities: LayoutEngineCapabilities { get }

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement]
}

public extension LayoutEngine {
    var capabilities: LayoutEngineCapabilities {
        []
    }

    @available(*, unavailable, message: "LayoutEngine.arrange must remain synchronous")
    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) async -> [Placement] {
        fatalError("LayoutEngine.arrange must remain synchronous")
    }
}
