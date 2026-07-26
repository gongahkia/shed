import ollyCore
import ollyKit
import ollyLayouts

public enum OllyIPC {
    public static let moduleName = "ollyIPC"
    public static let protocolVersion = 2
    public static let supportedCommandNames = IPCCommandName.allCases
    public static let supportedEventKinds = IPCEventKind.allCases
    public static let dependencyModules = [OllyKit.moduleName, OllyCore.moduleName, OllyLayouts.moduleName]

    public static func supportedEventKinds(forProtocolVersion version: Int) -> [IPCEventKind] {
        guard version >= 2 else {
            return IPCEventKind.v1Cases
        }
        return supportedEventKinds
    }
}
