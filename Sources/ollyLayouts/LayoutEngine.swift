import CoreGraphics
import ollyCore
import ollyKit

public struct WindowSnapshot: Equatable, Sendable {
    public let windowID: WindowID
    public let frame: CGRect
    public let displayID: DisplayID?
    public let tags: TagSet
    public let title: String?
    public let role: String?
    public let subrole: String?

    public init(
        windowID: WindowID,
        frame: CGRect,
        displayID: DisplayID? = nil,
        tags: TagSet = [],
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.windowID = windowID
        self.frame = frame
        self.displayID = displayID
        self.tags = tags
        self.title = title
        self.role = role
        self.subrole = subrole
    }

    public init(state: WindowState) {
        self.init(
            windowID: state.id,
            frame: state.frame,
            displayID: state.displayID,
            tags: TagSet(rawValue: state.tagMask),
            title: state.title,
            role: state.role,
            subrole: state.subrole
        )
    }
}

public protocol LayoutEngine {
    associatedtype Config

    var id: LayoutEngineID { get }
    var displayName: String { get }
    var config: Config { get }

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement]
}
