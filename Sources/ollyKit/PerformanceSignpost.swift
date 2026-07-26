import OSLog

public enum PerformanceSignpost {
    private static let logger = Logger(subsystem: "dev.olly.performance", category: "Performance")
    private static let signposter = OSSignposter(logger: logger)

    @discardableResult
    public static func interval<T>(_ name: StaticString, _ operation: () throws -> T) rethrows -> T {
        let id = signposter.makeSignpostID()
        let state = signposter.beginInterval(name, id: id)
        defer {
            signposter.endInterval(name, state)
        }
        return try operation()
    }

    @discardableResult
    public static func interval<T>(_ name: StaticString, _ operation: () async throws -> T) async rethrows -> T {
        let id = signposter.makeSignpostID()
        let state = signposter.beginInterval(name, id: id)
        defer {
            signposter.endInterval(name, state)
        }
        return try await operation()
    }
}
