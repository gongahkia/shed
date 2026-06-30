import Foundation

/// Purpose: Stores an explicit shell command for a raw action.
/// Parameters: Pass the command, label, optional timeout, and optional working directory.
/// Example: `ShellAction("open -a Safari", label: "safari")`
/// See also: `Action`, `Permissions`.
public struct ShellAction: Codable, Equatable, Sendable {
    public let command: String
    public let label: String
    public let timeoutMs: Int?
    public let cwd: String?

    public init(_ command: String, label: String? = nil, timeoutMs: Int? = nil, cwd: String? = nil) {
        precondition(!command.isEmpty)
        self.command = command
        self.label = if let label, !label.isEmpty { label } else { command }
        self.timeoutMs = timeoutMs
        self.cwd = cwd
    }
}

public extension Action {
    /// Purpose: Declares a shell command action executed through the raw-action runtime.
    /// Parameters: Pass the shell command, optional label, timeout in milliseconds, and working directory.
    /// Example: `Action.shell("open -a Safari", label: "safari")`
    /// See also: `ShellAction`, `Permissions`.
    static func shell(_ command: String, label: String? = nil, timeoutMs: Int? = nil, cwd: String? = nil) -> Action {
        .shell(ShellAction(command, label: label, timeoutMs: timeoutMs, cwd: cwd))
    }

    var rawActionLabel: String? {
        switch self {
        case let .shell(action):
            return action.label
        case let .raw(label):
            return label
        default:
            return nil
        }
    }

    var shellAction: ShellAction? {
        if case let .shell(action) = self {
            return action
        }
        return nil
    }
}

public extension Config {
    /// Purpose: Lists configured shell actions in keybind and gesture sections.
    /// Parameters: No parameters; returns shell actions in declaration order.
    /// Example: `config.shellActions.map(\.label)`
    /// See also: `Action`, `Keybinds`.
    var shellActions: [ShellAction] {
        keybinds.bindings.compactMap(\.action.shellAction) + gestures.bindings.compactMap { binding in
            if case let .action(action) = binding.action {
                return action.shellAction
            }
            return nil
        }
    }

    /// Purpose: Looks up the last configured shell action with a raw-action label.
    /// Parameters: Pass the label used by `Action.shell` or `Action.raw`.
    /// Example: `config.shellAction(label: "safari")`
    /// See also: `ShellAction`, `Permissions`.
    func shellAction(label: String) -> ShellAction? {
        shellActions.last { $0.label == label }
    }
}
