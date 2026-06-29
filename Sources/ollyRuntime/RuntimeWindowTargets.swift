import Foundation
import ollyKit

final class RuntimeWindowTargets: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.olly.runtime.window-targets")
    private var targetsByWindowID: [WindowID: WindowMoveTarget] = [:]

    func set(_ target: WindowMoveTarget, for windowID: WindowID) {
        queue.sync {
            targetsByWindowID[windowID] = target
        }
    }

    func remove(windowID: WindowID) {
        queue.sync {
            targetsByWindowID[windowID] = nil
        }
    }

    func target(for window: WindowState) -> WindowMoveTarget? {
        queue.sync {
            targetsByWindowID[window.id]
        }
    }
}
