import Dispatch
import Foundation

public struct PerformanceTraceEvent: Codable, Equatable {
    public let id: UInt64
    public let name: String
    public let timestampNS: UInt64
    public let attributes: [String: String]

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case timestampNS = "timestamp_ns"
        case attributes
    }
}

public enum PerformanceTrace {
	private static let lock = NSLock()
    private static var nextID: UInt64 = 0

    public static var isEnabled: Bool {
        tracePath != nil
    }

    @discardableResult
    public static func record(
        _ name: String,
        id: UInt64? = nil,
        attributes: [String: String] = [:],
        path: String? = nil
    ) -> UInt64? {
        guard let destination = path ?? tracePath else { return nil }
		lock.lock()
		defer { lock.unlock() }
        let eventID: UInt64
        if let id {
            eventID = id
        } else {
            nextID &+= 1
            eventID = nextID
        }
        let event = PerformanceTraceEvent(
            id: eventID,
            name: name,
            timestampNS: DispatchTime.now().uptimeNanoseconds,
            attributes: attributes
        )
        guard let data = try? JSONEncoder().encode(event) else { return eventID }
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: destination) {
            fileManager.createFile(atPath: destination, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: destination)) else { return eventID }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        _ = try? handle.write(contentsOf: data)
        _ = try? handle.write(contentsOf: Data("\n".utf8))
        return eventID
    }

    private static var tracePath: String? {
        guard let path = ProcessInfo.processInfo.environment["ITSY_PERF_TRACE_PATH"], !path.isEmpty else {
            return nil
        }
        return path
    }
}
