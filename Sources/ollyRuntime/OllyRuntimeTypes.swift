import ApplicationServices
import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyKit

public typealias AXSubroleReader = @Sendable (AXUIElement) async throws -> String?
public typealias DisplayChangeStreamProvider = @Sendable () -> AsyncStream<DisplayChange>
public typealias ActiveSpaceWindowIDProvider = @Sendable () -> Set<WindowID>?
public typealias NativeSpaceChangeStreamProvider = @Sendable () -> AsyncStream<Void>
public typealias TagApplicationLauncher = @Sendable (String) async throws -> Void
public typealias ScratchpadApplicationLauncher = @Sendable (String) async throws -> Void
public typealias ScratchpadFocusHandler = @Sendable (WindowState) async throws -> Void
public typealias ReduceMotionValueProvider = @MainActor @Sendable () -> Bool
public typealias ReduceMotionChangeStreamProvider = @Sendable () -> AsyncStream<Void>
public typealias MouseMoveStreamProvider = @Sendable () -> AsyncStream<CGPoint>
public typealias WindowUnderPointCandidateProvider = @Sendable () -> [WindowUnderPointCandidate]
public typealias AXWindowFocusSetter = @Sendable (WindowMoveTarget) async -> AXError
