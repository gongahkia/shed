import CoreGraphics
import Foundation
import ollyCore
import ollyKit

/// Purpose: Names the lifecycle event kind represented by a hook declaration.
/// Parameters: Choose raw, tag switch, display change, window, engine, fullscreen, config, or AX permission hooks.
/// Example: `HookKind.tagSwitch`
/// See also: `HookDeclaration`, `Hooks`.
public enum HookKind: String, Codable, Equatable, Sendable {
    case axPermissionChanged
    case configReload
    case displayChange
    case engineChange
    case fullscreenEnter
    case fullscreenExit
    case raw
    case tagSwitch
    case windowAppeared
    case windowClosed
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

/// Purpose: Carries typed context for window-closed lifecycle hooks.
/// Parameters: Provide the `WindowState` that closed.
/// Example: `WindowClosedHookContext(window: window)`
/// See also: `onWindowClosed(_:_:)`, `Hooks`.
public struct WindowClosedHookContext: Equatable, Sendable {
    public let window: WindowState

    public init(window: WindowState) {
        self.window = window
    }
}

/// Purpose: Carries typed context for config-reload lifecycle hooks.
/// Parameters: Provide previous and current config values plus the source URL when available.
/// Example: `ConfigReloadHookContext(previous: old, current: new, sourceURL: url)`
/// See also: `onConfigReload(_:_:)`, `Hooks`.
public struct ConfigReloadHookContext: Equatable, Sendable {
    public let previous: Config
    public let current: Config
    public let sourceURL: URL?

    public init(previous: Config, current: Config, sourceURL: URL?) {
        self.previous = previous
        self.current = current
        self.sourceURL = sourceURL
    }
}

/// Purpose: Carries typed context for engine-change lifecycle hooks.
/// Parameters: Provide display ID, tag, previous engine ID, and current engine ID.
/// Example: `EngineChangeHookContext(displayID: 1, tag: tag, previousEngineID: old, currentEngineID: new)`
/// See also: `onEngineChange(_:_:)`, `Hooks`.
public struct EngineChangeHookContext: Equatable, Sendable {
    public let displayID: DisplayID
    public let tag: Tag
    public let previousEngineID: LayoutEngineID?
    public let currentEngineID: LayoutEngineID

    public init(
        displayID: DisplayID,
        tag: Tag,
        previousEngineID: LayoutEngineID?,
        currentEngineID: LayoutEngineID
    ) {
        self.displayID = displayID
        self.tag = tag
        self.previousEngineID = previousEngineID
        self.currentEngineID = currentEngineID
    }
}

/// Purpose: Carries typed context for fullscreen lifecycle hooks.
/// Parameters: Provide the affected window and whether it entered fullscreen.
/// Example: `FullscreenHookContext(window: window, didEnter: true)`
/// See also: `onFullscreenEnter(_:_:)`, `onFullscreenExit(_:_:)`.
public struct FullscreenHookContext: Equatable, Sendable {
    public let window: WindowState
    public let didEnter: Bool

    public init(window: WindowState, didEnter: Bool) {
        self.window = window
        self.didEnter = didEnter
    }
}

/// Purpose: Carries typed context for AX permission lifecycle hooks.
/// Parameters: Provide the latest Accessibility permission status.
/// Example: `AXPermissionHookContext(status: .trusted)`
/// See also: `onAXPermissionChanged(_:_:)`, `Hooks`.
public struct AXPermissionHookContext: Equatable, Sendable {
    public let status: AXPermissionStatus

    public init(status: AXPermissionStatus) {
        self.status = status
    }
}

public typealias TagSwitchHookHandler = @Sendable (TagSwitchHookContext) -> Void
public typealias DisplayChangeHookHandler = @Sendable (DisplayChangeHookContext) -> Void
public typealias WindowAppearedHookHandler = @Sendable (WindowAppearedHookContext) -> Void
public typealias WindowClosedHookHandler = @Sendable (WindowClosedHookContext) -> Void
public typealias ConfigReloadHookHandler = @Sendable (ConfigReloadHookContext) -> Void
public typealias EngineChangeHookHandler = @Sendable (EngineChangeHookContext) -> Void
public typealias FullscreenHookHandler = @Sendable (FullscreenHookContext) -> Void
public typealias AXPermissionHookHandler = @Sendable (AXPermissionHookContext) -> Void

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

/// Purpose: Declares a typed hook for closed windows.
/// Parameters: Provide an optional stable label and a handler receiving `WindowClosedHookContext`.
/// Example: `onWindowClosed { context in _ = context.window.id }`
/// See also: `WindowClosedHookContext`, `Hooks`.
public func onWindowClosed(
    _ label: String = "onWindowClosed",
    _ body: @escaping WindowClosedHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .windowClosed, windowClosedHandler: body)
}

/// Purpose: Declares a typed hook for config reloads.
/// Parameters: Provide an optional stable label and a handler receiving `ConfigReloadHookContext`.
/// Example: `onConfigReload { context in _ = context.current.version }`
/// See also: `ConfigReloadHookContext`, `Hooks`.
public func onConfigReload(
    _ label: String = "onConfigReload",
    _ body: @escaping ConfigReloadHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .configReload, configReloadHandler: body)
}

/// Purpose: Declares a typed hook for engine changes.
/// Parameters: Provide an optional stable label and a handler receiving `EngineChangeHookContext`.
/// Example: `onEngineChange { context in _ = context.currentEngineID }`
/// See also: `EngineChangeHookContext`, `Hooks`.
public func onEngineChange(
    _ label: String = "onEngineChange",
    _ body: @escaping EngineChangeHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .engineChange, engineChangeHandler: body)
}

/// Purpose: Declares a typed hook for fullscreen entry.
/// Parameters: Provide an optional stable label and a handler receiving `FullscreenHookContext`.
/// Example: `onFullscreenEnter { context in _ = context.window.id }`
/// See also: `FullscreenHookContext`, `onFullscreenExit(_:_:)`.
public func onFullscreenEnter(
    _ label: String = "onFullscreenEnter",
    _ body: @escaping FullscreenHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .fullscreenEnter, fullscreenHandler: body)
}

/// Purpose: Declares a typed hook for fullscreen exit.
/// Parameters: Provide an optional stable label and a handler receiving `FullscreenHookContext`.
/// Example: `onFullscreenExit { context in _ = context.window.id }`
/// See also: `FullscreenHookContext`, `onFullscreenEnter(_:_:)`.
public func onFullscreenExit(
    _ label: String = "onFullscreenExit",
    _ body: @escaping FullscreenHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .fullscreenExit, fullscreenHandler: body)
}

/// Purpose: Declares a typed hook for Accessibility permission changes.
/// Parameters: Provide an optional stable label and a handler receiving `AXPermissionHookContext`.
/// Example: `onAXPermissionChanged { context in _ = context.status }`
/// See also: `AXPermissionHookContext`, `Hooks`.
public func onAXPermissionChanged(
    _ label: String = "onAXPermissionChanged",
    _ body: @escaping AXPermissionHookHandler
) -> HookDeclaration {
    HookDeclaration(label: label, kind: .axPermissionChanged, axPermissionHandler: body)
}
