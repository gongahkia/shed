import ApplicationServices
import CoreGraphics
import Foundation

public struct WindowUnderPointCandidate: Equatable, Sendable {
    public let windowID: WindowID
    public let processID: pid_t
    public let layer: Int
    public let bounds: CGRect

    public init(windowID: WindowID, processID: pid_t, layer: Int, bounds: CGRect) {
        self.windowID = windowID
        self.processID = processID
        self.layer = layer
        self.bounds = bounds
    }
}

public enum WindowUnderPointResolver {
    public static func systemCandidates() -> [WindowUnderPointCandidate] {
        guard let infoList = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID)
            as? [[String: Any]] else {
            return []
        }
        return infoList.compactMap(candidate)
    }

    public static func windowID(
        at point: CGPoint,
        candidates: [WindowUnderPointCandidate]
    ) -> WindowID? {
        candidates.first { candidate in
            candidate.layer == 0 &&
                candidate.bounds.width > 0 &&
                candidate.bounds.height > 0 &&
                candidate.bounds.contains(point)
        }?.windowID
    }

    private static func candidate(_ info: [String: Any]) -> WindowUnderPointCandidate? {
        guard let windowID = intValue(info[kCGWindowNumber as String]).map(WindowID.init),
              let processID = intValue(info[kCGWindowOwnerPID as String]).map(pid_t.init),
              let layer = intValue(info[kCGWindowLayer as String]),
              let boundsInfo = info[kCGWindowBounds as String] as? [String: Any],
              let bounds = CGRect(dictionaryRepresentation: boundsInfo as CFDictionary) else {
            return nil
        }
        return WindowUnderPointCandidate(
            windowID: windowID,
            processID: processID,
            layer: layer,
            bounds: bounds
        )
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            return number.intValue
        }
        return value as? Int
    }
}
