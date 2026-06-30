import ApplicationServices
import Foundation
import ollyIPC
import ollyKit

public enum OllyRuntimeError: Error, CustomStringConvertible {
    case displayUnavailable
    case engineUnavailable(LayoutEngineID)
    case windowUnavailable(WindowID)
    case missingFocusedWindow
    case missingDirectionalTarget(IPCDirection)
    case gestureUnbound(trigger: String, motion: String)
    case invalidMacroName(String)
    case macroAlreadyRecording
    case macroNotRecording
    case macroUnavailable(String)
    case macroPersistenceFailed(String)
    case ruleUnavailable(UUID)
    case unsupportedGestureAction(String)
    case axOperationFailed(String, AXError)
    case unsupportedAXCommand(String)
    case unsupportedEngineCommand(command: String, engineID: LayoutEngineID)

    public var description: String {
        switch self {
        case .displayUnavailable:
            return "display unavailable"
        case let .engineUnavailable(engineID):
            return "engine unavailable: \(engineID.rawValue)"
        case let .windowUnavailable(windowID):
            return "window unavailable: \(windowID)"
        case .missingFocusedWindow:
            return "no focused window"
        case let .missingDirectionalTarget(direction):
            return "no window in direction: \(direction.rawValue)"
        case let .gestureUnbound(trigger, motion):
            return "no gesture binding for \(trigger) \(motion)"
        case let .invalidMacroName(name):
            return "invalid macro name: \(name)"
        case .macroAlreadyRecording:
            return "macro recording already active"
        case .macroNotRecording:
            return "no macro recording active"
        case let .macroUnavailable(name):
            return "macro unavailable: \(name)"
        case let .macroPersistenceFailed(message):
            return "macro persistence failed: \(message)"
        case let .ruleUnavailable(ruleID):
            return "rule unavailable: \(ruleID.uuidString)"
        case let .unsupportedGestureAction(action):
            return "gesture action is unsupported: \(action)"
        case let .axOperationFailed(operation, error):
            return "\(operation) failed: \(error)"
        case let .unsupportedAXCommand(command):
            return "\(command) requires Accessibility permission"
        case let .unsupportedEngineCommand(command, engineID):
            return "\(command) is unavailable for engine \(engineID.rawValue)"
        }
    }

    var code: String {
        switch self {
        case .displayUnavailable:
            return "display_unavailable"
        case .engineUnavailable:
            return "engine_unavailable"
        case .windowUnavailable:
            return "window_unavailable"
        case .missingFocusedWindow:
            return "missing_focused_window"
        case .missingDirectionalTarget:
            return "missing_directional_target"
        case .gestureUnbound:
            return "gesture_unbound"
        case .invalidMacroName:
            return "invalid_macro_name"
        case .macroAlreadyRecording:
            return "macro_already_recording"
        case .macroNotRecording:
            return "macro_not_recording"
        case .macroUnavailable:
            return "macro_unavailable"
        case .macroPersistenceFailed:
            return "macro_persistence_failed"
        case .ruleUnavailable:
            return "rule_unavailable"
        case .unsupportedGestureAction:
            return "unsupported_gesture_action"
        case .axOperationFailed:
            return "ax_operation_failed"
        case .unsupportedAXCommand:
            return "ax_unavailable"
        case .unsupportedEngineCommand:
            return "unsupported_engine_command"
        }
    }
}
