import Foundation
import ollyIPC

public actor RuntimeEventBus {
    private var continuations: [UUID: AsyncStream<IPCEvent>.Continuation] = [:]

    public init() {}

    public func subscribe(
        bufferingPolicy: AsyncStream<IPCEvent>.Continuation.BufferingPolicy = .bufferingNewest(256)
    ) -> AsyncStream<IPCEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: IPCEvent.self, bufferingPolicy: bufferingPolicy)
        continuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task {
                await self?.remove(id)
            }
        }
        return stream
    }

    public func publish(_ event: IPCEvent) {
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }

    public var activeSubscriberCount: Int {
        continuations.count
    }

    private func remove(_ id: UUID) {
        continuations[id] = nil
    }
}

extension OllyRuntime {
    func publishRuntimeEvent(_ event: IPCEvent) async {
        await eventHub.publish(event)
        await runtimeEventBus.publish(event)
    }
}
