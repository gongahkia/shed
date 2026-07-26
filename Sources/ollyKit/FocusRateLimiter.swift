import Foundation

public struct FocusRateLimitSettings: Equatable, Sendable {
    public let maxEventsPerSecond: Int
    public let minHumanIntervalMilliseconds: Int

    public init(maxEventsPerSecond: Int = 20, minHumanIntervalMilliseconds: Int = 80) {
        self.maxEventsPerSecond = max(1, maxEventsPerSecond)
        self.minHumanIntervalMilliseconds = max(0, minHumanIntervalMilliseconds)
    }
}

public actor FocusRateLimiter {
    private var settings: FocusRateLimitSettings
    private let now: @Sendable () -> TimeInterval
    private var acceptedTimesByProcessID: [pid_t: [TimeInterval]] = [:]

    public init(
        settings: FocusRateLimitSettings = FocusRateLimitSettings(),
        now: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSinceReferenceDate }
    ) {
        self.settings = settings
        self.now = now
    }

    public func update(settings: FocusRateLimitSettings) {
        self.settings = settings
        acceptedTimesByProcessID.removeAll(keepingCapacity: true)
    }

    public func shouldAccept(processID: pid_t, isUserInitiated: Bool) -> Bool {
        let current = now()
        var acceptedTimes = acceptedTimesByProcessID[processID, default: []].filter { current - $0 < 1 }
        defer {
            acceptedTimesByProcessID[processID] = acceptedTimes
        }
        guard !isUserInitiated else {
            acceptedTimes.append(current)
            return true
        }
        if let last = acceptedTimes.last,
           current - last < Double(settings.minHumanIntervalMilliseconds) / 1_000 {
            return false
        }
        guard acceptedTimes.count < settings.maxEventsPerSecond else {
            return false
        }
        acceptedTimes.append(current)
        return true
    }
}
