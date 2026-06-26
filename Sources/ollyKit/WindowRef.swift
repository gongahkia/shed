import ApplicationServices
import AppKit
import Darwin
import Foundation

public typealias WindowID = CGWindowID

public struct WindowAttributes: Equatable, Sendable {
    public let title: String?
    public let role: String?
    public let subrole: String?
    public let frame: CGRect
    public let processID: pid_t
    public let windowID: WindowID?
}

public struct WindowIDLookupOptions: Equatable, Sendable {
    public var allowPrivateAXLookup: Bool

    public init(allowPrivateAXLookup: Bool = false) {
        self.allowPrivateAXLookup = allowPrivateAXLookup
    }

    public static let publicOnly = WindowIDLookupOptions()

    public static var environment: WindowIDLookupOptions {
        let value = ProcessInfo.processInfo.environment["OLLY_ENABLE_PRIVATE_AX_WINDOW_ID"]
        return WindowIDLookupOptions(allowPrivateAXLookup: value == "1")
    }
}

public enum WindowRefError: Error {
    case missingProcessID
    case missingFrame
}

public struct WindowRef {
    public let axElement: AXUIElement
    public private(set) var attributes: WindowAttributes

    public init(
        axElement: AXUIElement,
        lookupOptions: WindowIDLookupOptions = .environment
    ) throws {
        self.axElement = axElement
        self.attributes = try Self.readAttributes(from: axElement, lookupOptions: lookupOptions)
    }

    public mutating func refresh(lookupOptions: WindowIDLookupOptions = .environment) throws {
        attributes = try Self.readAttributes(from: axElement, lookupOptions: lookupOptions)
    }

    private static func readAttributes(
        from axElement: AXUIElement,
        lookupOptions: WindowIDLookupOptions
    ) throws -> WindowAttributes {
        guard let processID = processID(from: axElement) else {
            throw WindowRefError.missingProcessID
        }
        guard let frame = frame(from: axElement) else {
            throw WindowRefError.missingFrame
        }

        let title = stringAttribute(kAXTitleAttribute, from: axElement)
        let role = stringAttribute(kAXRoleAttribute, from: axElement)
        let subrole = stringAttribute(kAXSubroleAttribute, from: axElement)
        let windowID = resolveWindowID(
            axElement: axElement,
            processID: processID,
            title: title,
            frame: frame,
            lookupOptions: lookupOptions
        )

        return WindowAttributes(
            title: title,
            role: role,
            subrole: subrole,
            frame: frame,
            processID: processID,
            windowID: windowID
        )
    }

    private static func resolveWindowID(
        axElement: AXUIElement,
        processID: pid_t,
        title: String?,
        frame: CGRect,
        lookupOptions: WindowIDLookupOptions
    ) -> WindowID? {
        if lookupOptions.allowPrivateAXLookup,
           let windowID = PrivateAXWindowIDResolver.windowID(for: axElement) {
            return windowID
        }
        return fallbackWindowID(processID: processID, title: title, frame: frame)
    }

    static func fallbackWindowID(processID: pid_t, title: String?, frame: CGRect) -> WindowID? {
        guard let windowInfo = CGWindowListCopyWindowInfo(.optionAll, kCGNullWindowID) as? [[String: Any]] else {
            return nil
        }
        return fallbackWindowID(processID: processID, title: title, frame: frame, windowInfo: windowInfo)
    }

    static func fallbackWindowID(
        processID: pid_t,
        title: String?,
        frame: CGRect,
        windowInfo: [[String: Any]]
    ) -> WindowID? {
        windowInfo.first { info in
            matches(info: info, processID: processID, title: title, frame: frame)
        }.flatMap { info in
            intValue(info[kCGWindowNumber as String]).map(WindowID.init)
        }
    }

    private static func matches(info: [String: Any], processID: pid_t, title: String?, frame: CGRect) -> Bool {
        guard intValue(info[kCGWindowOwnerPID as String]) == Int(processID),
              intValue(info[kCGWindowLayer as String]) == 0,
              let bounds = info[kCGWindowBounds as String] as? [String: Any],
              let windowFrame = CGRect(dictionaryRepresentation: bounds as CFDictionary),
              approximatelyEqual(windowFrame, frame) else {
            return false
        }

        let cgTitle = info[kCGWindowName as String] as? String
        guard let title, !title.isEmpty, let cgTitle, !cgTitle.isEmpty else {
            return true
        }
        return title == cgTitle
    }

    private static func approximatelyEqual(_ lhs: CGRect, _ rhs: CGRect, tolerance: CGFloat = 2) -> Bool {
        abs(lhs.origin.x - rhs.origin.x) <= tolerance &&
            abs(lhs.origin.y - rhs.origin.y) <= tolerance &&
            abs(lhs.size.width - rhs.size.width) <= tolerance &&
            abs(lhs.size.height - rhs.size.height) <= tolerance
    }

    private static func processID(from axElement: AXUIElement) -> pid_t? {
        var processID = pid_t()
        let error = AXUIElementGetPid(axElement, &processID)
        return error == .success ? processID : nil
    }

    private static func frame(from axElement: AXUIElement) -> CGRect? {
        guard let origin = pointAttribute(kAXPositionAttribute, from: axElement),
              let size = sizeAttribute(kAXSizeAttribute, from: axElement) else {
            return nil
        }
        return CGRect(origin: origin, size: size)
    }

    private static func stringAttribute(_ attribute: String, from axElement: AXUIElement) -> String? {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(axElement, attribute as CFString, &value)
        guard error == .success else {
            return nil
        }
        return value as? String
    }

    private static func pointAttribute(_ attribute: String, from axElement: AXUIElement) -> CGPoint? {
        guard let value = axValueAttribute(attribute, from: axElement),
              AXValueGetType(value) == .cgPoint else {
            return nil
        }

        var point = CGPoint.zero
        guard AXValueGetValue(value, .cgPoint, &point) else {
            return nil
        }
        return point
    }

    private static func sizeAttribute(_ attribute: String, from axElement: AXUIElement) -> CGSize? {
        guard let value = axValueAttribute(attribute, from: axElement),
              AXValueGetType(value) == .cgSize else {
            return nil
        }

        var size = CGSize.zero
        guard AXValueGetValue(value, .cgSize, &size) else {
            return nil
        }
        return size
    }

    private static func axValueAttribute(_ attribute: String, from axElement: AXUIElement) -> AXValue? {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(axElement, attribute as CFString, &value)
        guard error == .success, let value, CFGetTypeID(value) == AXValueGetTypeID() else {
            return nil
        }
        return unsafeBitCast(value, to: AXValue.self)
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            return number.intValue
        }
        return value as? Int
    }
}

private enum PrivateAXWindowIDResolver {
    private typealias Function = @convention(c) (AXUIElement, UnsafeMutablePointer<CGWindowID>) -> AXError

    static func windowID(for axElement: AXUIElement) -> WindowID? {
        let symbolName = ["_AXUIElement", "GetWindow"].joined()
        guard let handle = dlopen(nil, RTLD_LAZY),
              let symbol = dlsym(handle, symbolName) else {
            return nil
        }

        let function = unsafeBitCast(symbol, to: Function.self)
        var windowID = CGWindowID()
        let error = function(axElement, &windowID)
        guard error == .success, windowID != 0 else {
            return nil
        }
        return windowID
    }
}
