import Foundation
import ollyIPC

public struct RuntimeErrorRecord: Codable, Equatable, Sendable {
    public let timestamp: Date
    public let message: String

    public init(timestamp: Date = Date(), message: String) {
        self.timestamp = timestamp
        self.message = message
    }
}

struct RuntimeErrorHistory: Equatable {
    let limit: Int
    private(set) var records: [RuntimeErrorRecord] = []

    init(limit: Int = 5) {
        self.limit = max(1, limit)
    }

    mutating func append(_ message: String, timestamp: Date = Date()) -> RuntimeErrorRecord? {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return nil
        }
        let record = RuntimeErrorRecord(timestamp: timestamp, message: trimmed)
        records.insert(record, at: 0)
        if records.count > limit {
            records.removeLast(records.count - limit)
        }
        return record
    }
}

extension OllyRuntime {
    public func recentErrors() -> [RuntimeErrorRecord] {
        errorHistory.records
    }

    func recordLastError(_ message: String?) {
        guard let message, let record = errorHistory.append(message) else {
            return
        }
        let event = IPCEvent.runtimeError(IPCRuntimeErrorEvent(
            timestamp: record.timestamp,
            message: record.message
        ))
        Task { [eventHub, runtimeEventBus] in
            await eventHub.publish(event)
            await runtimeEventBus.publish(event)
        }
    }
}
