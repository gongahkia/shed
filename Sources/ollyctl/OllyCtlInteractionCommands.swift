import ArgumentParser
import ollyIPC
import ollyKit

struct SnapWindow: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "snap-window",
        abstract: "Snap a window to a display zone."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Position: half, corner, center, or maximize.")
    var position: String

    @Option(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    @Option(help: "Target display ID; inferred from the window when omitted.")
    var displayID: DisplayID?

    @Flag(help: "Move the frame without forcing the window into floating mode.")
    var keepTiling = false

    func run() throws {
        let command = IPCSnapWindowCommand(
            position: try parseSnapPosition(position),
            windowID: windowID,
            displayID: displayID,
            makeFloating: !keepTiling
        )
        try OllyCtlRunner(options: options).send(.snapWindow(command))
    }
}

struct DispatchGesture: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "dispatch-gesture",
        abstract: "Dispatch a configured DSL gesture from an external gesture tool."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Trigger: fourFingerHorizontal or fourFingerVertical.")
    var trigger: String

    @Argument(help: "Motion: left, right, upward, downward.")
    var motion: String

    @Option(help: "Target display ID.")
    var displayID: DisplayID?

    func run() throws {
        let command = IPCDispatchGestureCommand(
            trigger: try parseGestureTrigger(trigger),
            motion: try parseGestureMotion(motion),
            displayID: displayID
        )
        try OllyCtlRunner(options: options).send(.dispatchGesture(command))
    }
}

func parseSnapPosition(_ rawValue: String) throws -> IPCSnapPosition {
    guard let position = IPCSnapPosition(rawValue: rawValue) else {
        let values = IPCSnapPosition.allCases.map(\.rawValue).joined(separator: ", ")
        throw ValidationError("position must be one of: \(values)")
    }
    return position
}

func parseGestureTrigger(_ rawValue: String) throws -> IPCGestureTrigger {
    guard let trigger = IPCGestureTrigger(rawValue: rawValue) else {
        throw ValidationError("trigger must be one of: fourFingerHorizontal, fourFingerVertical")
    }
    return trigger
}

func parseGestureMotion(_ rawValue: String) throws -> IPCGestureMotion {
    guard let motion = IPCGestureMotion(rawValue: rawValue) else {
        throw ValidationError("motion must be one of: left, right, upward, downward")
    }
    return motion
}
