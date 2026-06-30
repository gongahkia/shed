import CoreGraphics
import Foundation
import ollyKit

public struct FullscreenSnapshot: Equatable, Sendable {
    public let tagMask: UInt64
    public let displayID: DisplayID?

    public init(tagMask: UInt64, displayID: DisplayID?) {
        self.tagMask = tagMask
        self.displayID = displayID
    }
}

public actor FullscreenTracker {
    private var saved: [WindowID: FullscreenSnapshot] = [:]

    public init() {}

    public func enter(_ windowID: WindowID, tagMask: UInt64, displayID: DisplayID?) {
        saved[windowID] = FullscreenSnapshot(tagMask: tagMask, displayID: displayID)
    }

    public func exit(_ windowID: WindowID) -> FullscreenSnapshot? {
        saved.removeValue(forKey: windowID)
    }

    public func snapshot(for windowID: WindowID) -> FullscreenSnapshot? {
        saved[windowID]
    }

    public func isFullscreen(_ windowID: WindowID) -> Bool {
        saved[windowID] != nil
    }
}
