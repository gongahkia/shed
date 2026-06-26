// swiftlint:disable inclusive_language
import ollyCore
import ollyKit

public enum EngineEvent: Codable, Equatable, Sendable {
    case arranged(EngineArrangedEvent)
    case masterSwapped(MasterSwappedEvent)
    case manualPreselected(ManualPreselectedEvent)
    case manualWindowInserted(ManualWindowInsertedEvent)
    case bspTreeChanged(BSPTreeChangedEvent)
    case niriColumnCreated(NiriColumnCreatedEvent)
    case niriWindowStacked(NiriWindowStackedEvent)
    case niriColumnWidthChanged(NiriColumnWidthChangedEvent)
}

public struct EngineArrangedEvent: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let engineID: LayoutEngineID
    public let placementCount: Int
    public let appliedPlacementCount: Int

    public init(
        displayID: DisplayID,
        engineID: LayoutEngineID,
        placementCount: Int,
        appliedPlacementCount: Int
    ) {
        self.displayID = displayID
        self.engineID = engineID
        self.placementCount = placementCount
        self.appliedPlacementCount = appliedPlacementCount
    }
}

public struct MasterSwappedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let previousMaster: WindowID?
    public let currentMaster: WindowID?
    public let order: [WindowID]

    public init(
        engineID: LayoutEngineID = MasterStackLayoutEngine.engineID,
        previousMaster: WindowID?,
        currentMaster: WindowID?,
        order: [WindowID]
    ) {
        self.engineID = engineID
        self.previousMaster = previousMaster
        self.currentMaster = currentMaster
        self.order = order
    }
}

public struct ManualPreselectedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let windowID: WindowID?
    public let path: ManualContainerPath
    public let direction: ManualPreselectDirection?

    public init(
        engineID: LayoutEngineID = ManualLayoutEngine.engineID,
        windowID: WindowID?,
        path: ManualContainerPath,
        direction: ManualPreselectDirection?
    ) {
        self.engineID = engineID
        self.windowID = windowID
        self.path = path
        self.direction = direction
    }
}

public struct ManualWindowInsertedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let windowID: WindowID
    public let focus: WindowID?
    public let path: ManualContainerPath

    public init(
        engineID: LayoutEngineID = ManualLayoutEngine.engineID,
        windowID: WindowID,
        focus: WindowID?,
        path: ManualContainerPath
    ) {
        self.engineID = engineID
        self.windowID = windowID
        self.focus = focus
        self.path = path
    }
}

public enum BSPTreeAction: String, Codable, Equatable, Sendable {
    case rotateChildren
    case flipAxis
    case balanceTree
}

public struct BSPTreeChangedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let action: BSPTreeAction
    public let path: BSPContainerPath

    public init(
        engineID: LayoutEngineID = BSPLayoutEngine.engineID,
        action: BSPTreeAction,
        path: BSPContainerPath
    ) {
        self.engineID = engineID
        self.action = action
        self.path = path
    }
}

public struct NiriColumnCreatedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let columnIndex: Int
    public let windowID: WindowID
    public let focus: WindowID?

    public init(
        engineID: LayoutEngineID = NiriScrollLayoutEngine.engineID,
        columnIndex: Int,
        windowID: WindowID,
        focus: WindowID?
    ) {
        self.engineID = engineID
        self.columnIndex = columnIndex
        self.windowID = windowID
        self.focus = focus
    }
}

public struct NiriWindowStackedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let columnIndex: Int
    public let windowID: WindowID
    public let focus: WindowID?

    public init(
        engineID: LayoutEngineID = NiriScrollLayoutEngine.engineID,
        columnIndex: Int,
        windowID: WindowID,
        focus: WindowID?
    ) {
        self.engineID = engineID
        self.columnIndex = columnIndex
        self.windowID = windowID
        self.focus = focus
    }
}

public struct NiriColumnWidthChangedEvent: Codable, Equatable, Sendable {
    public let engineID: LayoutEngineID
    public let columnIndex: Int
    public let widthPreset: NiriColumnWidthPreset

    public init(
        engineID: LayoutEngineID = NiriScrollLayoutEngine.engineID,
        columnIndex: Int,
        widthPreset: NiriColumnWidthPreset
    ) {
        self.engineID = engineID
        self.columnIndex = columnIndex
        self.widthPreset = widthPreset
    }
}
// swiftlint:enable inclusive_language
