import Foundation

public actor EngineEventBus {
    private var subscribers: [UUID: AsyncStream<EngineEvent>.Continuation] = [:]

    public init() {}

    public func publish(_ event: EngineEvent) {
        for subscriber in subscribers.values {
            subscriber.yield(event)
        }
    }

    public func events() -> AsyncStream<EngineEvent> {
        let id = UUID()
        var capturedContinuation: AsyncStream<EngineEvent>.Continuation?
        let stream = AsyncStream<EngineEvent>(bufferingPolicy: .bufferingNewest(256)) { continuation in
            capturedContinuation = continuation
            continuation.onTermination = { [weak self] _ in
                Task {
                    await self?.removeSubscriber(id: id)
                }
            }
        }

        if let capturedContinuation {
            subscribers[id] = capturedContinuation
        }
        return stream
    }

    private func removeSubscriber(id: UUID) {
        subscribers[id] = nil
    }
}
