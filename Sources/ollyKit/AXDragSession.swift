import CoreGraphics
import Foundation

public enum AXDragEvent: Equatable, Sendable {
    case started(WindowID, CGRect, CGPoint)
    case moved(WindowID, CGRect, CGPoint)
    case ended(WindowID, CGRect)
}

typealias AXDragEndTaskFactory = @Sendable (
    _ delayNanoseconds: UInt64,
    _ operation: @escaping @Sendable () async -> Void
) -> Task<Void, Never>

public actor AXDragSession {
    private struct ActiveDrag {
        let windowID: WindowID
        let frame: CGRect
        let generation: UInt64
    }

    private let endDelayNanoseconds: UInt64
    private let programmaticMoveThreshold: CGFloat
    private let mouseProvider: @Sendable () -> CGPoint
    private let endTaskFactory: AXDragEndTaskFactory
    private var continuations: [UUID: AsyncStream<AXDragEvent>.Continuation] = [:]
    private var activeDrag: ActiveDrag?
    private var generation: UInt64 = 0
    private var endTask: Task<Void, Never>?

    public init(
        endDelayNanoseconds: UInt64 = 120_000_000,
        programmaticMoveThreshold: CGFloat = 1,
        mouseProvider: @escaping @Sendable () -> CGPoint = {
            CGEvent(source: nil)?.location ?? .zero
        }
    ) {
        self.init(
            endDelayNanoseconds: endDelayNanoseconds,
            programmaticMoveThreshold: programmaticMoveThreshold,
            mouseProvider: mouseProvider,
            endTaskFactory: { delayNanoseconds, operation in
                Self.defaultEndTaskFactory(
                    delayNanoseconds: delayNanoseconds,
                    operation: operation
                )
            }
        )
    }

    init(
        endDelayNanoseconds: UInt64,
        programmaticMoveThreshold: CGFloat = 1,
        mouseProvider: @escaping @Sendable () -> CGPoint,
        endTaskFactory: @escaping AXDragEndTaskFactory
    ) {
        self.endDelayNanoseconds = endDelayNanoseconds
        self.programmaticMoveThreshold = programmaticMoveThreshold
        self.mouseProvider = mouseProvider
        self.endTaskFactory = endTaskFactory
    }

    public func subscribe(
        bufferingPolicy: AsyncStream<AXDragEvent>.Continuation.BufferingPolicy = .bufferingNewest(256)
    ) -> AsyncStream<AXDragEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(
            of: AXDragEvent.self,
            bufferingPolicy: bufferingPolicy
        )
        continuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task {
                await self?.remove(id)
            }
        }
        return stream
    }

    public func feed(windowID: WindowID, frame: CGRect, ourLastFrame: CGRect?) {
        guard !isProgrammaticMove(frame, matching: ourLastFrame) else {
            return
        }

        generation &+= 1
        let currentGeneration = generation
        let mouse = mouseProvider()
        if let activeDrag, activeDrag.windowID == windowID {
            self.activeDrag = ActiveDrag(windowID: windowID, frame: frame, generation: currentGeneration)
            yield(.moved(windowID, frame, mouse))
        } else {
            if let activeDrag {
                yield(.ended(activeDrag.windowID, activeDrag.frame))
            }
            activeDrag = ActiveDrag(windowID: windowID, frame: frame, generation: currentGeneration)
            yield(.started(windowID, frame, mouse))
        }
        scheduleEnd(windowID: windowID, generation: currentGeneration)
    }

    public func endActiveSession() {
        generation &+= 1
        endTask?.cancel()
        endTask = nil
        guard let activeDrag else {
            return
        }
        self.activeDrag = nil
        yield(.ended(activeDrag.windowID, activeDrag.frame))
    }

    public func end(windowID: WindowID) {
        guard activeDrag?.windowID == windowID else {
            return
        }
        endActiveSession()
    }

    public var activeWindowID: WindowID? {
        activeDrag?.windowID
    }

    public var activeSubscriberCount: Int {
        continuations.count
    }

    private func scheduleEnd(windowID: WindowID, generation: UInt64) {
        endTask?.cancel()
        endTask = endTaskFactory(endDelayNanoseconds) { [weak self] in
            await self?.endIfIdle(windowID: windowID, generation: generation)
        }
    }

    private func endIfIdle(windowID: WindowID, generation: UInt64) {
        guard let activeDrag,
              activeDrag.windowID == windowID,
              activeDrag.generation == generation else {
            return
        }
        self.activeDrag = nil
        endTask = nil
        yield(.ended(activeDrag.windowID, activeDrag.frame))
    }

    private func isProgrammaticMove(_ frame: CGRect, matching ourLastFrame: CGRect?) -> Bool {
        guard let ourLastFrame else {
            return false
        }
        return abs(ourLastFrame.minX - frame.minX) < programmaticMoveThreshold &&
            abs(ourLastFrame.minY - frame.minY) < programmaticMoveThreshold
    }

    private func yield(_ event: AXDragEvent) {
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }

    private func remove(_ id: UUID) {
        continuations[id] = nil
    }

    private static func defaultEndTaskFactory(
        delayNanoseconds: UInt64,
        operation: @escaping @Sendable () async -> Void
    ) -> Task<Void, Never> {
        Task {
            try? await Task.sleep(nanoseconds: delayNanoseconds)
            guard !Task.isCancelled else {
                return
            }
            await operation()
        }
    }
}
