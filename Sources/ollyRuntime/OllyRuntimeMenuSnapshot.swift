import ollyCore
import ollyKit

public struct OllyRuntimeMenuSnapshot: Equatable, Sendable {
    public let displayName: String
    public let displayID: DisplayID?
    public let activeTags: [UInt8]
    public let currentEngineID: LayoutEngineID
    public let axStatus: AXPermissionStatus
    public let isIPCServerRunning: Bool
    public let lastError: String?

    public init(
        displayName: String,
        displayID: DisplayID?,
        activeTags: [UInt8],
        currentEngineID: LayoutEngineID,
        axStatus: AXPermissionStatus,
        isIPCServerRunning: Bool,
        lastError: String?
    ) {
        self.displayName = displayName
        self.displayID = displayID
        self.activeTags = activeTags
        self.currentEngineID = currentEngineID
        self.axStatus = axStatus
        self.isIPCServerRunning = isIPCServerRunning
        self.lastError = lastError
    }
}
