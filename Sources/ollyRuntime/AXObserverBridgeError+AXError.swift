import ApplicationServices
import ollyKit

extension AXObserverBridgeError {
    var axError: AXError? {
        switch self {
        case let .observerCreateFailed(error):
            return error
        case let .addNotificationFailed(_, error):
            return error
        case .streamContinuationUnavailable:
            return nil
        }
    }
}
