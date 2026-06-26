import Foundation

public struct FocusScope: Hashable, Sendable {
    public let displayID: DisplayID
    public let tagMask: UInt64

    public init(displayID: DisplayID, tagMask: UInt64) {
        self.displayID = displayID
        self.tagMask = tagMask
    }
}

public actor FocusStack {
    private var stacksByScope: [FocusScope: [WindowID]] = [:]

    public init() {}

    public func recordFocus(windowID: WindowID, displayID: DisplayID, tagMask: UInt64) {
        let scope = FocusScope(displayID: displayID, tagMask: tagMask)
        var stack = stacksByScope[scope] ?? []
        stack.removeAll { $0 == windowID }
        stack.insert(windowID, at: 0)
        stacksByScope[scope] = stack
    }

    public func remove(windowID: WindowID) {
        for scope in stacksByScope.keys {
            stacksByScope[scope]?.removeAll { $0 == windowID }
            if stacksByScope[scope]?.isEmpty == true {
                stacksByScope[scope] = nil
            }
        }
    }

    public func preferredWindow(
        displayID: DisplayID,
        tagMask: UInt64,
        availableWindows: Set<WindowID>
    ) -> WindowID? {
        let scope = FocusScope(displayID: displayID, tagMask: tagMask)
        return stacksByScope[scope]?.first { availableWindows.contains($0) }
    }

    @discardableResult
    public func restoreFocus(
        displayID: DisplayID,
        tagMask: UInt64,
        availableWindows: Set<WindowID>,
        focus: (WindowID) async -> Bool
    ) async -> WindowID? {
        guard let windowID = preferredWindow(
            displayID: displayID,
            tagMask: tagMask,
            availableWindows: availableWindows
        ) else {
            return nil
        }

        guard await focus(windowID) else {
            remove(windowID: windowID)
            return await restoreFocus(
                displayID: displayID,
                tagMask: tagMask,
                availableWindows: availableWindows.subtracting([windowID]),
                focus: focus
            )
        }
        return windowID
    }
}
