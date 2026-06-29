import ApplicationServices
import Foundation
import ollyKit

final class RuntimeWindowTargets: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.olly.runtime.window-targets")
    private var targetsByWindowID: [WindowID: WindowMoveTarget] = [:]
    private var windowIDsByElementHash: [Int: WindowID] = [:]

    func set(_ target: WindowMoveTarget, for windowID: WindowID) {
        queue.sync {
            targetsByWindowID[windowID] = target
            windowIDsByElementHash[Self.elementHash(target.axElement)] = windowID
        }
    }

    func remove(windowID: WindowID) {
        queue.sync {
            if let target = targetsByWindowID[windowID] {
                windowIDsByElementHash[Self.elementHash(target.axElement)] = nil
            }
            targetsByWindowID[windowID] = nil
        }
    }

    func target(for window: WindowState) -> WindowMoveTarget? {
        queue.sync {
            targetsByWindowID[window.id]
        }
    }

    func windowID(for element: AXUIElement) -> WindowID? {
        queue.sync {
            windowIDsByElementHash[Self.elementHash(element)]
        }
    }

    func removeAll() {
        queue.sync {
            targetsByWindowID.removeAll()
            windowIDsByElementHash.removeAll()
        }
    }

    private static func elementHash(_ element: AXUIElement) -> Int {
        Int(CFHash(element))
    }
}
