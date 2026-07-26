import ApplicationServices
import CoreGraphics

public struct AXFrameReader: Sendable {
    public init() {}

    public func frame(for element: AXUIElement) -> CGRect? {
        guard let position = pointAttribute(kAXPositionAttribute, from: element),
              let size = sizeAttribute(kAXSizeAttribute, from: element) else {
            return nil
        }
        return CGRect(origin: position, size: size)
    }

    private func pointAttribute(_ attribute: String, from element: AXUIElement) -> CGPoint? {
        guard let value = axValueAttribute(attribute, from: element),
              AXValueGetType(value) == .cgPoint else {
            return nil
        }
        var point = CGPoint.zero
        guard AXValueGetValue(value, .cgPoint, &point) else {
            return nil
        }
        return point
    }

    private func sizeAttribute(_ attribute: String, from element: AXUIElement) -> CGSize? {
        guard let value = axValueAttribute(attribute, from: element),
              AXValueGetType(value) == .cgSize else {
            return nil
        }
        var size = CGSize.zero
        guard AXValueGetValue(value, .cgSize, &size) else {
            return nil
        }
        return size
    }

    private func axValueAttribute(_ attribute: String, from element: AXUIElement) -> AXValue? {
        var value: CFTypeRef?
        let error = AXSignpost.interval("ax.read.attribute") {
            AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        }
        guard error == .success, let value, CFGetTypeID(value) == AXValueGetTypeID() else {
            return nil
        }
        return unsafeBitCast(value, to: AXValue.self)
    }
}
