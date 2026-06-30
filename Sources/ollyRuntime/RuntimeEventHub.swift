import Foundation
import ollyIPC
import ollyLayouts

actor RuntimeEventHub {
    private struct Subscriber {
        let connection: UnixDomainSocketServerConnection
        let kinds: Set<IPCEventKind>
        let protocolVersion: Int
    }

    private var subscribers: [UUID: Subscriber] = [:]

    func subscribe(
        connection: UnixDomainSocketServerConnection,
        kinds: [IPCEventKind],
        protocolVersion: Int
    ) -> UUID {
        let id = connection.id
        subscribers[id] = Subscriber(
            connection: connection,
            kinds: Set(kinds),
            protocolVersion: protocolVersion
        )
        return id
    }

    func unsubscribe(_ id: UUID) {
        subscribers[id] = nil
    }

    func publish(_ event: IPCEvent) {
        for subscriber in subscribers.values where subscriber.kinds.contains(event.kind) {
            guard let data = try? JSONEncoder().encode(IPCEventEnvelope(
                version: subscriber.protocolVersion,
                event: event
            )) else {
                continue
            }
            subscriber.connection.sendLine(data)
        }
    }
}

private extension IPCEvent {
    var kind: IPCEventKind {
        switch self {
        case .axPermission:
            return .axPermission
        case .engine:
            return .engine
        case .focus:
            return .focus
        case .fullscreen:
            return .fullscreen
        case .space:
            return .space
        }
    }
}
