import AppKit
import ollyIPC

struct SnapZone: Equatable {
    let position: IPCSnapPosition
    let frame: CGRect
}

enum SnapZoneResolver {
    static func zones(in layoutFrame: CGRect) -> [SnapZone] {
        let bounds = layoutFrame.standardized
        guard bounds.width > 0, bounds.height > 0 else {
            return []
        }
        return IPCSnapPosition.allCases.map { position in
            SnapZone(position: position, frame: frame(for: position, in: bounds))
        }
    }

    private static func frame(for position: IPCSnapPosition, in bounds: CGRect) -> CGRect {
        switch position {
        case .leftHalf:
            return leftFrame(in: bounds)
        case .rightHalf:
            return rightFrame(in: bounds)
        case .topHalf:
            return topFrame(in: bounds)
        case .bottomHalf:
            return bottomFrame(in: bounds)
        case .topLeft:
            return leftFrame(in: topFrame(in: bounds))
        case .topRight:
            return rightFrame(in: topFrame(in: bounds))
        case .bottomLeft:
            return leftFrame(in: bottomFrame(in: bounds))
        case .bottomRight:
            return rightFrame(in: bottomFrame(in: bounds))
        case .center:
            return centerFrame(in: bounds)
        case .maximize:
            return bounds
        }
    }

    private static func leftFrame(in bounds: CGRect) -> CGRect {
        CGRect(x: bounds.minX, y: bounds.minY, width: floor(bounds.width / 2), height: bounds.height)
    }

    private static func rightFrame(in bounds: CGRect) -> CGRect {
        let halfWidth = floor(bounds.width / 2)
        return CGRect(
            x: bounds.minX + halfWidth,
            y: bounds.minY,
            width: bounds.width - halfWidth,
            height: bounds.height
        )
    }

    private static func topFrame(in bounds: CGRect) -> CGRect {
        let halfHeight = floor(bounds.height / 2)
        return CGRect(
            x: bounds.minX,
            y: bounds.minY + halfHeight,
            width: bounds.width,
            height: bounds.height - halfHeight
        )
    }

    private static func bottomFrame(in bounds: CGRect) -> CGRect {
        CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: floor(bounds.height / 2))
    }

    static func zone(for mousePoint: CGPoint, in layoutFrame: CGRect) -> IPCSnapPosition? {
        let bounds = layoutFrame.standardized
        guard bounds.width > 0, bounds.height > 0, bounds.contains(mousePoint) else {
            return nil
        }
        let band = edgeBand(in: bounds)
        let left = mousePoint.x <= bounds.minX + band
        let right = mousePoint.x >= bounds.maxX - band
        let top = mousePoint.y >= bounds.maxY - band
        let bottom = mousePoint.y <= bounds.minY + band

        switch (left, right, top, bottom) {
        case (true, false, true, false):
            return .topLeft
        case (false, true, true, false):
            return .topRight
        case (true, false, false, true):
            return .bottomLeft
        case (false, true, false, true):
            return .bottomRight
        case (true, false, false, false):
            return .leftHalf
        case (false, true, false, false):
            return .rightHalf
        case (false, false, true, false):
            return .topHalf
        case (false, false, false, true):
            return .bottomHalf
        default:
            return centerFrame(in: bounds).contains(mousePoint) ? .center : .maximize
        }
    }

    private static func edgeBand(in frame: CGRect) -> CGFloat {
        min(max(48, min(frame.width, frame.height) * 0.12), min(frame.width, frame.height) / 2)
    }

    private static func centerFrame(in frame: CGRect) -> CGRect {
        let width = max(80, frame.width * 0.34)
        let height = max(80, frame.height * 0.34)
        return CGRect(
            x: frame.midX - min(width, frame.width) / 2,
            y: frame.midY - min(height, frame.height) / 2,
            width: min(width, frame.width),
            height: min(height, frame.height)
        )
    }
}

@MainActor
final class SnapZoneView: NSView {
    private var zoneLayers: [IPCSnapPosition: CALayer] = [:]
    private var zoneAccessibilityElements: [NSAccessibilityElement] = []
    private var highlighted: IPCSnapPosition?

    var activeLayerCount: Int {
        zoneLayers.count
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer = CALayer()
        layer?.masksToBounds = false
        setAccessibilityElement(true)
        setAccessibilityRole(.layoutArea)
        setAccessibilityLabel("Snap zones")
    }

    required init?(coder: NSCoder) {
        nil
    }

    func update(zones: [SnapZone], highlighted: IPCSnapPosition?, animateHighlight: Bool) {
        let positions = Set(zones.map(\.position))
        for (position, layer) in zoneLayers where !positions.contains(position) {
            layer.removeFromSuperlayer()
            zoneLayers[position] = nil
        }

        for zone in zones {
            let layer = layer(for: zone.position)
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.frame = zone.frame.integral
            layer.cornerRadius = 8
            layer.borderWidth = zone.position == highlighted ? 2 : 1
            layer.borderColor = borderColor(for: zone.position, highlighted: highlighted).cgColor
            layer.backgroundColor = fillColor(for: zone.position, highlighted: highlighted).cgColor
            layer.zPosition = zone.position.zPosition(highlighted: highlighted)
            CATransaction.commit()
        }

        if animateHighlight, self.highlighted != highlighted {
            animate(layer: highlighted.flatMap { zoneLayers[$0] })
        }
        updateAccessibility(zones: zones)
        self.highlighted = highlighted
    }

    private func layer(for position: IPCSnapPosition) -> CALayer {
        if let layer = zoneLayers[position] {
            return layer
        }
        let layer = CALayer()
        self.layer?.addSublayer(layer)
        zoneLayers[position] = layer
        return layer
    }

    private func animate(layer: CALayer?) {
        guard let layer else {
            return
        }
        let animation = CABasicAnimation(keyPath: "opacity")
        animation.fromValue = 0.45
        animation.toValue = 1
        animation.duration = 0.12
        layer.add(animation, forKey: "snap-zone-highlight")
    }

    private func fillColor(for position: IPCSnapPosition, highlighted: IPCSnapPosition?) -> NSColor {
        if position == highlighted {
            return NSColor.systemBlue.withAlphaComponent(0.26)
        }
        if position == .maximize {
            return NSColor.systemBlue.withAlphaComponent(0.05)
        }
        return NSColor.systemTeal.withAlphaComponent(0.09)
    }

    private func borderColor(for position: IPCSnapPosition, highlighted: IPCSnapPosition?) -> NSColor {
        if position == highlighted {
            return NSColor.systemBlue.withAlphaComponent(0.9)
        }
        if position == .maximize {
            return NSColor.separatorColor.withAlphaComponent(0.18)
        }
        return NSColor.separatorColor.withAlphaComponent(0.42)
    }

    private func updateAccessibility(zones: [SnapZone]) {
        zoneAccessibilityElements = zones.map { zone in
            let element = NSAccessibilityElement()
            element.setAccessibilityRole(.button)
            element.setAccessibilityLabel(zone.position.accessibilityLabel)
            element.setAccessibilityFrameInParentSpace(zone.frame.integral)
            return element
        }
        setAccessibilityChildren(zoneAccessibilityElements)
    }
}

private extension IPCSnapPosition {
    func zPosition(highlighted: IPCSnapPosition?) -> CGFloat {
        if self == highlighted {
            return 10
        }
        return self == .maximize ? 0 : 1
    }

    var accessibilityLabel: String {
        switch self {
        case .leftHalf:
            return "Snap left half"
        case .rightHalf:
            return "Snap right half"
        case .topHalf:
            return "Snap top half"
        case .bottomHalf:
            return "Snap bottom half"
        case .topLeft:
            return "Snap top left"
        case .topRight:
            return "Snap top right"
        case .bottomLeft:
            return "Snap bottom left"
        case .bottomRight:
            return "Snap bottom right"
        case .center:
            return "Snap center"
        case .maximize:
            return "Maximize"
        }
    }
}
