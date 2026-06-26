import CoreGraphics
import ollyKit

public struct Placement: Codable, Equatable, Sendable {
    public let windowID: WindowID
    public let frame: CGRect
    public let zOrder: Int
    public let hidden: Bool

    public init(windowID: WindowID, frame: CGRect, zOrder: Int = 0, hidden: Bool = false) {
        self.windowID = windowID
        self.frame = frame
        self.zOrder = zOrder
        self.hidden = hidden
    }
}
