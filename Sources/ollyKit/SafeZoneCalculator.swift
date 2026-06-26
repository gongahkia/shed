import CoreGraphics
import Foundation

public struct DisplaySafeAreaInsets: Codable, Equatable, Sendable {
    public let top: CGFloat
    public let left: CGFloat
    public let bottom: CGFloat
    public let right: CGFloat

    public init(top: CGFloat = 0, left: CGFloat = 0, bottom: CGFloat = 0, right: CGFloat = 0) {
        self.top = top
        self.left = left
        self.bottom = bottom
        self.right = right
    }
}

public enum SafeZoneKind: String, Codable, Equatable, Sendable {
    case menuBar
    case notch
    case visibleFrame
}

public struct SafeZoneReserve: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let kind: SafeZoneKind
    public let rect: CGRect

    public init(displayID: DisplayID, kind: SafeZoneKind, rect: CGRect) {
        self.displayID = displayID
        self.kind = kind
        self.rect = rect
    }
}

public struct SafeZoneResult: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let frame: CGRect
    public let visibleFrame: CGRect
    public let layoutFrame: CGRect
    public let reserves: [SafeZoneReserve]

    public init(
        displayID: DisplayID,
        frame: CGRect,
        visibleFrame: CGRect,
        layoutFrame: CGRect,
        reserves: [SafeZoneReserve]
    ) {
        self.displayID = displayID
        self.frame = frame
        self.visibleFrame = visibleFrame
        self.layoutFrame = layoutFrame
        self.reserves = reserves
    }
}

public struct SafeZoneCalculator: Sendable {
    public static let defaultNotchPadding: CGFloat = 12

    public let notchPadding: CGFloat

    public init(notchPadding: CGFloat = Self.defaultNotchPadding) {
        self.notchPadding = max(0, notchPadding)
    }

    public func result(for display: Display) -> SafeZoneResult {
        let rawFrame = display.frame.standardized
        let visibleFrame = display.visibleFrame.standardized
        let edgeReserves = edgeReserves(rawFrame: rawFrame, visibleFrame: visibleFrame)
        let safeInsets = safeInsets(for: display)
        let topReserve = max(edgeReserves.top, safeInsets.top)
        let leftReserve = max(edgeReserves.left, safeInsets.left)
        let bottomReserve = max(edgeReserves.bottom, safeInsets.bottom)
        let rightReserve = max(edgeReserves.right, safeInsets.right)
        let layoutFrame = inset(
            rawFrame,
            top: topReserve,
            left: leftReserve,
            bottom: bottomReserve,
            right: rightReserve
        )

        return SafeZoneResult(
            displayID: display.id,
            frame: rawFrame,
            visibleFrame: visibleFrame,
            layoutFrame: layoutFrame,
            reserves: reserves(
                displayID: display.id,
                rawFrame: rawFrame,
                edgeReserves: edgeReserves,
                notchTopReserve: safeInsets.top
            )
        )
    }

    public func layoutFrame(for display: Display) -> CGRect {
        result(for: display).layoutFrame
    }

    private func safeInsets(for display: Display) -> DisplaySafeAreaInsets {
        let top = display.safeAreaInsets.top > 0 ? display.safeAreaInsets.top + notchPadding : 0
        return DisplaySafeAreaInsets(
            top: top,
            left: display.safeAreaInsets.left,
            bottom: display.safeAreaInsets.bottom,
            right: display.safeAreaInsets.right
        )
    }

    private func edgeReserves(rawFrame: CGRect, visibleFrame: CGRect) -> DisplaySafeAreaInsets {
        DisplaySafeAreaInsets(
            top: max(0, rawFrame.maxY - visibleFrame.maxY),
            left: max(0, visibleFrame.minX - rawFrame.minX),
            bottom: max(0, visibleFrame.minY - rawFrame.minY),
            right: max(0, rawFrame.maxX - visibleFrame.maxX)
        )
    }

    private func inset(
        _ frame: CGRect,
        top: CGFloat,
        left: CGFloat,
        bottom: CGFloat,
        right: CGFloat
    ) -> CGRect {
        let width = max(0, frame.width - left - right)
        let height = max(0, frame.height - top - bottom)
        return CGRect(x: frame.minX + left, y: frame.minY + bottom, width: width, height: height)
    }

    private func reserves(
        displayID: DisplayID,
        rawFrame: CGRect,
        edgeReserves: DisplaySafeAreaInsets,
        notchTopReserve: CGFloat
    ) -> [SafeZoneReserve] {
        var reserves: [SafeZoneReserve] = []
        if edgeReserves.top > 0 {
            reserves.append(
                SafeZoneReserve(
                    displayID: displayID,
                    kind: .menuBar,
                    rect: topRect(in: rawFrame, height: edgeReserves.top)
                )
            )
        }
        if notchTopReserve > 0 {
            reserves.append(
                SafeZoneReserve(
                    displayID: displayID,
                    kind: .notch,
                    rect: topRect(in: rawFrame, height: notchTopReserve)
                )
            )
        }
        if edgeReserves.left > 0 || edgeReserves.bottom > 0 || edgeReserves.right > 0 {
            reserves.append(SafeZoneReserve(displayID: displayID, kind: .visibleFrame, rect: rawFrame))
        }
        return reserves
    }

    private func topRect(in frame: CGRect, height: CGFloat) -> CGRect {
        CGRect(x: frame.minX, y: frame.maxY - height, width: frame.width, height: height)
    }
}
