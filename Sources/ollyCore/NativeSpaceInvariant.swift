import Foundation
import ollyKit

public struct NativeSpaceID: Codable, Equatable, Hashable, RawRepresentable, Sendable {
    public let rawValue: UInt64

    public init(rawValue: UInt64) {
        self.rawValue = rawValue
    }
}

public enum NativeSpaceDriftPolicy: Equatable, Sendable {
    case rehome
    case unmanage
}

public enum NativeSpaceInvariantIssue: Equatable, Sendable {
    case unknownSpace(windowID: WindowID)
    case drifted(windowID: WindowID, expected: NativeSpaceID, actual: NativeSpaceID)
}

public struct NativeSpaceInvariantResult: Equatable, Sendable {
    public let expectedSpaceID: NativeSpaceID?
    public let issues: [NativeSpaceInvariantIssue]
    public let rehomedWindowIDs: [WindowID]
    public let unmanagedWindowIDs: [WindowID]

    public var isVerified: Bool {
        expectedSpaceID != nil && issues.isEmpty
    }
}

public protocol WindowNativeSpaceProviding {
    func nativeSpaceID(for window: WindowState) async -> NativeSpaceID?
}

public struct PublicWindowNativeSpaceProvider: WindowNativeSpaceProviding {
    public init() {}

    public func nativeSpaceID(for window: WindowState) async -> NativeSpaceID? {
        nil
    }
}

public typealias NativeSpaceRehomeHandler = (WindowState, NativeSpaceID) async -> Bool
public typealias NativeSpaceUnmanageHandler = (WindowState) async -> Void

public actor NativeSpaceInvariant {
    private let windowStore: WindowStore
    private let spaceProvider: WindowNativeSpaceProviding
    private let driftPolicy: NativeSpaceDriftPolicy
    private let rehomeWindow: NativeSpaceRehomeHandler
    private let unmanageWindow: NativeSpaceUnmanageHandler

    public init(
        windowStore: WindowStore,
        spaceProvider: WindowNativeSpaceProviding = PublicWindowNativeSpaceProvider(),
        driftPolicy: NativeSpaceDriftPolicy = .unmanage,
        rehomeWindow: @escaping NativeSpaceRehomeHandler = { _, _ in false },
        unmanageWindow: @escaping NativeSpaceUnmanageHandler = { _ in }
    ) {
        self.windowStore = windowStore
        self.spaceProvider = spaceProvider
        self.driftPolicy = driftPolicy
        self.rehomeWindow = rehomeWindow
        self.unmanageWindow = unmanageWindow
    }

    public func verify(expectedSpaceID: NativeSpaceID? = nil) async -> NativeSpaceInvariantResult {
        let windows = await windowStore.allWindows()
        var baseline = expectedSpaceID
        var issues: [NativeSpaceInvariantIssue] = []
        var rehomedWindowIDs: [WindowID] = []
        var unmanagedWindowIDs: [WindowID] = []

        for window in windows {
            guard let spaceID = await spaceProvider.nativeSpaceID(for: window) else {
                issues.append(.unknownSpace(windowID: window.id))
                continue
            }

            guard let expected = baseline else {
                baseline = spaceID
                continue
            }

            guard spaceID != expected else {
                continue
            }

            issues.append(.drifted(windowID: window.id, expected: expected, actual: spaceID))
            switch driftPolicy {
            case .rehome:
                if await rehomeWindow(window, expected) {
                    rehomedWindowIDs.append(window.id)
                }
            case .unmanage:
                await windowStore.remove(id: window.id)
                await unmanageWindow(window)
                unmanagedWindowIDs.append(window.id)
            }
        }

        return NativeSpaceInvariantResult(
            expectedSpaceID: baseline,
            issues: issues,
            rehomedWindowIDs: rehomedWindowIDs,
            unmanagedWindowIDs: unmanagedWindowIDs
        )
    }
}
