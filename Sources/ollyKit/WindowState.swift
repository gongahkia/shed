import CoreGraphics
import Foundation

public struct WindowState: Equatable, Sendable {
    public let id: WindowID
    public let processID: pid_t
    public let bundleID: String?
    public let displayID: DisplayID?
    public let tagMask: UInt64
    public let isFloating: Bool
    public let isSticky: Bool
    public let isPinned: Bool
    public let isFullscreen: Bool
    public let isOffSpace: Bool
    public let engineOverride: LayoutEngineID?
    public let layoutOrder: Int?
    public let frame: CGRect
    public let title: String?
    public let role: String?
    public let subrole: String?

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        isSticky: Bool = false,
        isPinned: Bool = false,
        isFullscreen: Bool = false,
        isOffSpace: Bool = false,
        engineOverride: LayoutEngineID? = nil,
        layoutOrder: Int? = nil,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.id = id
        self.processID = processID
        self.bundleID = bundleID
        self.displayID = displayID
        self.tagMask = tagMask
        self.isFloating = isFloating
        self.isSticky = isSticky
        self.isPinned = isPinned
        self.isFullscreen = isFullscreen
        self.isOffSpace = isOffSpace
        self.engineOverride = engineOverride
        self.layoutOrder = layoutOrder
        self.frame = frame
        self.title = title
        self.role = role
        self.subrole = subrole
    }

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        layoutOrder: Int? = nil,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.init(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            isSticky: false,
            isPinned: false,
            isFullscreen: false,
            isOffSpace: false,
            engineOverride: nil,
            layoutOrder: layoutOrder,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        isSticky: Bool = false,
        isPinned: Bool = false,
        isFullscreen: Bool = false,
        isOffSpace: Bool = false,
        engineOverride: LayoutEngineID? = nil,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.init(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            isSticky: isSticky,
            isPinned: isPinned,
            isFullscreen: isFullscreen,
            isOffSpace: isOffSpace,
            engineOverride: engineOverride,
            layoutOrder: nil,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }

    public init(
        id: WindowID,
        processID: pid_t,
        bundleID: String? = nil,
        displayID: DisplayID? = nil,
        tagMask: UInt64 = 0,
        isFloating: Bool = false,
        frame: CGRect,
        title: String? = nil,
        role: String? = nil,
        subrole: String? = nil
    ) {
        self.init(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID,
            tagMask: tagMask,
            isFloating: isFloating,
            layoutOrder: nil,
            frame: frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }
}

public extension WindowState {
    func withLayoutOrder(_ layoutOrder: Int?) -> WindowState {
        copy(layoutOrder: layoutOrder)
    }

    func withFloating(_ isFloating: Bool) -> WindowState {
        copy(isFloating: isFloating)
    }

    func withDisplayID(_ displayID: DisplayID?) -> WindowState {
        copy(displayID: displayID)
    }

    func withSticky(_ isSticky: Bool) -> WindowState {
        copy(isSticky: isSticky)
    }

    func withPinned(_ isPinned: Bool) -> WindowState {
        copy(isPinned: isPinned)
    }

    func withFullscreen(_ isFullscreen: Bool) -> WindowState {
        copy(isFullscreen: isFullscreen)
    }

    func withOffSpace(_ isOffSpace: Bool) -> WindowState {
        copy(isOffSpace: isOffSpace)
    }

    func withEngineOverride(_ engineOverride: LayoutEngineID?) -> WindowState {
        copy(engineOverride: engineOverride)
    }

    func withTagMask(_ tagMask: UInt64) -> WindowState {
        copy(tagMask: tagMask)
    }

    func withFrame(_ frame: CGRect) -> WindowState {
        copy(frame: frame)
    }

    private func copy(
        displayID: DisplayID?? = nil,
        tagMask: UInt64? = nil,
        isFloating: Bool? = nil,
        isSticky: Bool? = nil,
        isPinned: Bool? = nil,
        isFullscreen: Bool? = nil,
        isOffSpace: Bool? = nil,
        engineOverride: LayoutEngineID?? = nil,
        layoutOrder: Int?? = nil,
        frame: CGRect? = nil
    ) -> WindowState {
        WindowState(
            id: id,
            processID: processID,
            bundleID: bundleID,
            displayID: displayID ?? self.displayID,
            tagMask: tagMask ?? self.tagMask,
            isFloating: isFloating ?? self.isFloating,
            isSticky: isSticky ?? self.isSticky,
            isPinned: isPinned ?? self.isPinned,
            isFullscreen: isFullscreen ?? self.isFullscreen,
            isOffSpace: isOffSpace ?? self.isOffSpace,
            engineOverride: engineOverride ?? self.engineOverride,
            layoutOrder: layoutOrder ?? self.layoutOrder,
            frame: frame ?? self.frame,
            title: title,
            role: role,
            subrole: subrole
        )
    }
}
