import CoreGraphics
import ollyCore
import ollyKit

/// Purpose: Names the lifecycle event kind represented by a hook declaration.
/// Parameters: Choose raw, tag switch, display change, or window appeared.
/// Example: `HookKind.tagSwitch`
/// See also: `HookDeclaration`, `Hooks`.
public enum HookKind: String, Codable, Equatable, Sendable {
    case raw
    case tagSwitch
    case displayChange
    case windowAppeared
}

/// Purpose: Carries typed context for tag-switch lifecycle hooks.
/// Parameters: Provide display ID, previous tags, and active tags after the switch.
/// Example: `TagSwitchHookContext(displayID: 1, previousTags: [], activeTags: TagSet(rawValue: 2))`
/// See also: `onTagSwitch(_:_:)`, `Hooks`.
public struct TagSwitchHookContext: Equatable, Sendable {
    public let displayID: DisplayID
    public let previousTags: TagSet
    public let activeTags: TagSet

    public init(displayID: DisplayID, previousTags: TagSet, activeTags: TagSet) {
        self.displayID = displayID
        self.previousTags = previousTags
        self.activeTags = activeTags
    }
}

/// Purpose: Carries typed context for display-change lifecycle hooks.
/// Parameters: Provide the `DisplayChange` emitted by `DisplayMonitor`.
/// Example: `DisplayChangeHookContext(change: change)`
/// See also: `onDisplayChange(_:_:)`, `Hooks`.
public struct DisplayChangeHookContext: Equatable, Sendable {
    public let change: DisplayChange

    public init(change: DisplayChange) {
        self.change = change
    }
}

/// Purpose: Carries typed context for window-appeared lifecycle hooks.
/// Parameters: Provide the `WindowState` that appeared.
/// Example: `WindowAppearedHookContext(window: window)`
/// See also: `onWindowAppeared(_:_:)`, `Hooks`.
public struct WindowAppearedHookContext: Equatable, Sendable {
    public let window: WindowState

    public init(window: WindowState) {
        self.window = window
    }
}

public typealias TagSwitchHookHandler = @Sendable (TagSwitchHookContext) -> Void
public typealias DisplayChangeHookHandler = @Sendable (DisplayChangeHookContext) -> Void
public typealias WindowAppearedHookHandler = @Sendable (WindowAppearedHookContext) -> Void

/// Purpose: Declares a typed hook for tag switches.
/// Parameters: Provide an optional stable label and a handler receiving `TagSwitchHookContext`.
/// Example: `onTagSwitch { context in _ = context.activeTags }`
/// See also: `TagSwitchHookContext`, `Hooks`.
public func onTagSwitch(_ label: String = "onTagSwitch", _ body: @escaping TagSwitchHookHandler) -> HookDeclaration {
    HookDeclaration(label: label, kind: .tagSwitch, tagSwitchHandler: body)
}

/// Purpose: Declares a typed hook for display changes.
/// Parameters: Provide an optional stable label and a handler receiving `DisplayChangeHookContext`.
/// Example: `onDisplayChange { context in _ = context.change.displayID }`
/// See also: `DisplayChangeHookContext`, `Hooks`.
public func onDisplayChange(
    _ label: String = "onDisplayChange",
    _ body: @escaping DisplayChangeHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .displayChange, displayChangeHandler: body)
}

/// Purpose: Declares a typed hook for newly appeared windows.
/// Parameters: Provide an optional stable label and a handler receiving `WindowAppearedHookContext`.
/// Example: `onWindowAppeared { context in _ = context.window.bundleID }`
/// See also: `WindowAppearedHookContext`, `Hooks`.
public func onWindowAppeared(
    _ label: String = "onWindowAppeared",
    _ body: @escaping WindowAppearedHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .windowAppeared, windowAppearedHandler: body)
}
