import Foundation
import ollyIPC

public actor RuntimeOverlayRequestBus {
    private var continuations: [UUID: AsyncStream<IPCOverlayKind>.Continuation] = [:]

    public init() {}

    public func subscribe(
        bufferingPolicy: AsyncStream<IPCOverlayKind>.Continuation.BufferingPolicy = .bufferingNewest(64)
    ) -> AsyncStream<IPCOverlayKind> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: IPCOverlayKind.self, bufferingPolicy: bufferingPolicy)
        continuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task {
                await self?.remove(id)
            }
        }
        return stream
    }

    public func publish(_ kind: IPCOverlayKind) {
        for continuation in continuations.values {
            continuation.yield(kind)
        }
    }

    public var activeSubscriberCount: Int {
        continuations.count
    }

    private func remove(_ id: UUID) {
        continuations[id] = nil
    }
}
