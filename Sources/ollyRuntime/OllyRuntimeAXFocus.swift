import ApplicationServices
import ollyKit

extension OllyRuntime {
    func setAXFocus(_ target: WindowMoveTarget, operation: String) async throws {
        let error = await axWindowFocusSetter(target)
        guard error == .success else {
            await handleAXReadWriteError(error)
            throw OllyRuntimeError.axOperationFailed(operation, error)
        }
    }

    public static func defaultAXWindowFocusSetter(_ target: WindowMoveTarget) async -> AXError {
        AXUIElementSetAttributeValue(target.axElement, kAXFocusedAttribute as CFString, kCFBooleanTrue)
    }
}
