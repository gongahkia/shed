// swiftlint:disable inclusive_language
import CoreGraphics
import ollyCore
import ollyKit

public enum VerticalTileOrientation: String, Codable, Equatable, Sendable {
    case automatic
    case landscape
    case portrait
}

/// Rotation-aware full-height master layout following Qtile-style tall/vertical layout precedents:
/// https://docs.qtile.org/en/latest/manual/ref/layouts.html
public struct VerticalTileLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let masterRatio: CGFloat
        public let orientation: VerticalTileOrientation

        public init(masterRatio: CGFloat = 0.5, orientation: VerticalTileOrientation = .automatic) {
            precondition(masterRatio > 0 && masterRatio < 1)
            self.masterRatio = masterRatio
            self.orientation = orientation
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "vertical-tile")

    public let id = VerticalTileLayoutEngine.engineID
    public let displayName = "VerticalTile"
    public let config: Config
    public let capabilities: LayoutEngineCapabilities = [.supportsResizing]

    public init(config: Config = Config()) {
        self.config = config
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        guard let master = windows.first else {
            return []
        }
        guard windows.count > 1 else {
            return [Placement(windowID: master.windowID, frame: bounds)]
        }

        let slaves = Array(windows.dropFirst())
        switch resolvedOrientation(in: bounds) {
        case .landscape:
            return landscapePlacements(master: master, slaves: slaves, in: bounds)
        case .portrait:
            return portraitPlacements(master: master, slaves: slaves, in: bounds)
        case .automatic:
            return []
        }
    }

    private func resolvedOrientation(in bounds: CGRect) -> VerticalTileOrientation {
        switch config.orientation {
        case .automatic:
            return bounds.height > bounds.width ? .portrait : .landscape
        case .landscape, .portrait:
            return config.orientation
        }
    }

    private func landscapePlacements(
        master: WindowSnapshot,
        slaves: [WindowSnapshot],
        in bounds: CGRect
    ) -> [Placement] {
        let masterWidth = floor(bounds.width * config.masterRatio)
        let masterFrame = CGRect(x: bounds.minX, y: bounds.minY, width: masterWidth, height: bounds.height)
        let slaveBounds = CGRect(
            x: masterFrame.maxX,
            y: bounds.minY,
            width: bounds.maxX - masterFrame.maxX,
            height: bounds.height
        )
        return [Placement(windowID: master.windowID, frame: masterFrame, zOrder: 0)] +
            horizontalSlavePlacements(slaves, in: slaveBounds)
    }

    private func portraitPlacements(
        master: WindowSnapshot,
        slaves: [WindowSnapshot],
        in bounds: CGRect
    ) -> [Placement] {
        let masterHeight = floor(bounds.height * config.masterRatio)
        let masterFrame = CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: masterHeight)
        let slaveBounds = CGRect(
            x: bounds.minX,
            y: masterFrame.maxY,
            width: bounds.width,
            height: bounds.maxY - masterFrame.maxY
        )
        return [Placement(windowID: master.windowID, frame: masterFrame, zOrder: 0)] +
            verticalSlavePlacements(slaves, in: slaveBounds)
    }

    private func horizontalSlavePlacements(_ slaves: [WindowSnapshot], in bounds: CGRect) -> [Placement] {
        let width = bounds.width / CGFloat(slaves.count)
        return slaves.enumerated().map { index, window in
            let originX = bounds.minX + CGFloat(index) * width
            return Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: originX,
                    y: bounds.minY,
                    width: index == slaves.count - 1 ? bounds.maxX - originX : width,
                    height: bounds.height
                ),
                zOrder: index + 1
            )
        }
    }

    private func verticalSlavePlacements(_ slaves: [WindowSnapshot], in bounds: CGRect) -> [Placement] {
        let height = bounds.height / CGFloat(slaves.count)
        return slaves.enumerated().map { index, window in
            let originY = bounds.minY + CGFloat(index) * height
            return Placement(
                windowID: window.windowID,
                frame: CGRect(
                    x: bounds.minX,
                    y: originY,
                    width: bounds.width,
                    height: index == slaves.count - 1 ? bounds.maxY - originY : height
                ),
                zOrder: index + 1
            )
        }
    }
}

public struct VerticalTileLayoutEngineFactory: LayoutEngineFactory {
    public let id = VerticalTileLayoutEngine.engineID
    public let displayName = "VerticalTile"

    public init() {}

    public func makeEngine(config: VerticalTileLayoutEngine.Config) throws -> VerticalTileLayoutEngine {
        VerticalTileLayoutEngine(config: config)
    }
}
// swiftlint:enable inclusive_language
