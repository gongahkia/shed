// swiftlint:disable inclusive_language
import CoreGraphics
import ollyCore
import ollyKit

/// Centered-master layout for ultrawide displays, with balanced slave stacks on both sides.
public struct ThreeColLayoutEngine: LayoutEngine {
    public struct Config: Codable, Equatable, Sendable {
        public let masterRatio: CGFloat

        public init(masterRatio: CGFloat = 0.5) {
            precondition(masterRatio > 0 && masterRatio < 1)
            self.masterRatio = masterRatio
        }
    }

    public static let engineID = LayoutEngineID(rawValue: "three-col")

    public let id = ThreeColLayoutEngine.engineID
    public let displayName = "ThreeCol"
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

        let columns = columnFrames(in: bounds)
        let slaves = Array(windows.dropFirst())
        let leftTotal = (slaves.count + 1) / 2
        let rightTotal = slaves.count / 2
        var leftIndex = 0
        var rightIndex = 0
        var placements = [Placement(windowID: master.windowID, frame: columns.master, zOrder: 0)]
        placements.reserveCapacity(windows.count)

        for (offset, window) in slaves.enumerated() {
            if offset.isMultiple(of: 2) {
                placements.append(Placement(
                    windowID: window.windowID,
                    frame: stackFrame(at: leftIndex, count: leftTotal, in: columns.left),
                    zOrder: offset + 1
                ))
                leftIndex += 1
            } else {
                placements.append(Placement(
                    windowID: window.windowID,
                    frame: stackFrame(at: rightIndex, count: rightTotal, in: columns.right),
                    zOrder: offset + 1
                ))
                rightIndex += 1
            }
        }

        return placements
    }

    private func columnFrames(in bounds: CGRect) -> ThreeColColumnFrames {
        let masterWidth = floor(bounds.width * config.masterRatio)
        let sideWidth = (bounds.width - masterWidth) / 2
        let left = CGRect(x: bounds.minX, y: bounds.minY, width: sideWidth, height: bounds.height)
        let master = CGRect(x: left.maxX, y: bounds.minY, width: masterWidth, height: bounds.height)
        let right = CGRect(x: master.maxX, y: bounds.minY, width: bounds.maxX - master.maxX, height: bounds.height)
        return ThreeColColumnFrames(left: left, master: master, right: right)
    }

    private func stackFrame(at index: Int, count: Int, in bounds: CGRect) -> CGRect {
        let height = bounds.height / CGFloat(count)
        let originY = bounds.minY + CGFloat(index) * height
        return CGRect(
            x: bounds.minX,
            y: originY,
            width: bounds.width,
            height: index == count - 1 ? bounds.maxY - originY : height
        )
    }
}

public struct ThreeColLayoutEngineFactory: LayoutEngineFactory {
    public let id = ThreeColLayoutEngine.engineID
    public let displayName = "ThreeCol"

    public init() {}

    public func makeEngine(config: ThreeColLayoutEngine.Config) throws -> ThreeColLayoutEngine {
        ThreeColLayoutEngine(config: config)
    }
}

private struct ThreeColColumnFrames {
    let left: CGRect
    let master: CGRect
    let right: CGRect
}
// swiftlint:enable inclusive_language
