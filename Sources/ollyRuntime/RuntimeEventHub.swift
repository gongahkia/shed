import Foundation
import ollyIPC
import ollyLayouts

actor RuntimeEventHub {
    private struct Subscriber {
        let connection: UnixDomainSocketServerConnection
        let kinds: Set<IPCEventKind>
    }

    private var subscribers: [UUID: Subscriber] = [:]

    func subscribe(
        connection: UnixDomainSocketServerConnection,
        kinds: [IPCEventKind]
    ) -> UUID {
        let id = connection.id
        subscribers[id] = Subscriber(connection: connection, kinds: Set(kinds))
        return id
    }

    func unsubscribe(_ id: UUID) {
        subscribers[id] = nil
    }

    func publish(_ event: IPCEvent) {
        guard let data = try? JSONEncoder().encode(IPCEventEnvelope(event: event)) else {
            return
        }
        for subscriber in subscribers.values where subscriber.kinds.contains(event.kind) {
            subscriber.connection.sendLine(data)
        }
    }
}

private extension IPCEvent {
    var kind: IPCEventKind {
        switch self {
        case .engine:
            return .engine
        case .focus:
            return .focus
        }
    }
}
