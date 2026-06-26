import ollyCore
import ollyKit
import ollyLayouts

public enum OllyIPC {
    public static let moduleName = "ollyIPC"
    public static let protocolVersion = 1
    public static let supportedCommandNames = IPCCommandName.allCases
    public static let dependencyModules = [OllyKit.moduleName, OllyCore.moduleName, OllyLayouts.moduleName]
}
