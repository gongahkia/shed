import ArgumentParser
import ollyCore
import ollyIPC
import ollyKit
import ollyLayouts

struct ManualPreselect: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "manual-preselect",
        abstract: "Preselect the split direction for the focused manual-layout window."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Direction: left, right, up, down.")
    var direction: String

    @Option(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCManualPreselectCommand(
            direction: try parseManualPreselectDirection(direction),
            windowID: windowID,
            displayID: displayID
        )
        try OllyCtlRunner(options: options).send(.manualPreselect(command))
    }
}

struct BSPTree: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "bsp-tree",
        abstract: "Mutate the active BSP tree."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Action: rotate-children, flip-axis, balance-tree.")
    var action: String

    @Option(help: "Container path as comma-separated 0/1 indexes; root when omitted.")
    var path: String?

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCBSPTreeCommand(
            action: try parseBSPTreeAction(action),
            path: try parseBSPContainerPath(path),
            displayID: displayID
        )
        try OllyCtlRunner(options: options).send(.bspTree(command))
    }
}

func parseManualPreselectDirection(_ rawValue: String) throws -> ManualPreselectDirection {
    guard let direction = ManualPreselectDirection(rawValue: rawValue) else {
        throw ValidationError("direction must be one of: left, right, up, down")
    }
    return direction
}

func parseBSPTreeAction(_ rawValue: String) throws -> BSPTreeAction {
    switch rawValue {
    case "rotate-children", "rotateChildren":
        return .rotateChildren
    case "flip-axis", "flipAxis":
        return .flipAxis
    case "balance-tree", "balanceTree":
        return .balanceTree
    default:
        throw ValidationError("action must be one of: rotate-children, flip-axis, balance-tree")
    }
}

func parseBSPContainerPath(_ rawValue: String?) throws -> BSPContainerPath {
    guard let rawValue, !rawValue.isEmpty else {
        return .root
    }
    let indexes = try rawValue.split(separator: ",").map { part in
        guard let index = Int(part), index == 0 || index == 1 else {
            throw ValidationError("path must contain only 0/1 indexes separated by commas")
        }
        return index
    }
    return BSPContainerPath(indexes)
}
