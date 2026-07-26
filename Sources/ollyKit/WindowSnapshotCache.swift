import ApplicationServices
import Foundation

public actor WindowSnapshotCache {
    public typealias Loader = (AXUIElement, WindowIDLookupOptions) throws -> WindowAttributes

    public final class Snapshot {
        public let axElement: AXUIElement
        public let attributes: WindowAttributes

        public init(axElement: AXUIElement, attributes: WindowAttributes) {
            self.axElement = axElement
            self.attributes = attributes
        }
    }

    private let loader: Loader
    private var snapshotsByElementHash: [Int: WeakSnapshot] = [:]

    public init() {
        self.loader = Self.defaultLoader
    }

    public init(loader: @escaping Loader) {
        self.loader = loader
    }

    public func snapshot(
        for axElement: AXUIElement,
        lookupOptions: WindowIDLookupOptions = .environment
    ) throws -> Snapshot {
        pruneReleasedSnapshots()
        let key = Self.key(for: axElement)
        if let snapshot = snapshotsByElementHash[key]?.value {
            return snapshot
        }

        let snapshot = Snapshot(axElement: axElement, attributes: try loader(axElement, lookupOptions))
        snapshotsByElementHash[key] = WeakSnapshot(snapshot)
        return snapshot
    }

    public func invalidate(for event: AXNotificationEvent) {
        guard event.notification.invalidatesWindowSnapshot else {
            return
        }
        snapshotsByElementHash[Self.key(for: event.element)] = nil
    }

    public func removeAll() {
        snapshotsByElementHash.removeAll()
    }

    private func pruneReleasedSnapshots() {
        snapshotsByElementHash = snapshotsByElementHash.filter { $0.value.value != nil }
    }

    private static func key(for axElement: AXUIElement) -> Int {
        Int(CFHash(axElement))
    }

    private static func defaultLoader(
        axElement: AXUIElement,
        lookupOptions: WindowIDLookupOptions
    ) throws -> WindowAttributes {
        try WindowRef(axElement: axElement, lookupOptions: lookupOptions).attributes
    }
}

private struct WeakSnapshot {
    weak var value: WindowSnapshotCache.Snapshot?

    init(_ value: WindowSnapshotCache.Snapshot) {
        self.value = value
    }
}

public extension AXNotification {
    var invalidatesWindowSnapshot: Bool {
        switch self {
        case .windowCreated, .uiElementDestroyed, .windowMoved, .windowResized:
            return true
        case .focusedWindowChanged, .mainWindowChanged, .applicationActivated:
            return false
        }
    }
}
