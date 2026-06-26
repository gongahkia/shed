import ollyCore
import ollyLayouts

public enum OllyDSL {
    public static let moduleName = "ollyDSL"
    public static let dependencyModules = [OllyCore.moduleName, OllyLayouts.moduleName]
}
