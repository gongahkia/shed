import OSLog

enum AXSignpost {
    private static let logger = Logger(subsystem: "dev.olly.ollyKit", category: "AX")
    private static let signposter = OSSignposter(logger: logger)

    @discardableResult
    static func interval<T>(_ name: StaticString, _ operation: () throws -> T) rethrows -> T {
        let id = signposter.makeSignpostID()
        let state = signposter.beginInterval(name, id: id)
        defer {
            signposter.endInterval(name, state)
        }
        return try operation()
    }
}
