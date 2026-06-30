import Foundation

public enum PerformanceMetric: String, CaseIterable, Codable, Equatable, Sendable {
    case p50
    case p95
    case p99
    case max
}

public struct PerformanceScenarioResult: Codable, Equatable, Sendable {
    public let name: String
    public let unit: String
    public let sampleCount: Int
    public let p50: Double
    public let p95: Double
    public let p99: Double
    public let min: Double
    public let max: Double

    public init(
        name: String,
        unit: String,
        sampleCount: Int,
        p50: Double,
        p95: Double,
        p99: Double,
        min: Double,
        max: Double
    ) {
        self.name = name
        self.unit = unit
        self.sampleCount = sampleCount
        self.p50 = p50
        self.p95 = p95
        self.p99 = p99
        self.min = min
        self.max = max
    }

    public func value(for metric: PerformanceMetric) -> Double {
        switch metric {
        case .p50:
            return p50
        case .p95:
            return p95
        case .p99:
            return p99
        case .max:
            return max
        }
    }
}

public struct PerformanceBudget: Codable, Equatable, Sendable {
    public let scenarioName: String
    public let metric: PerformanceMetric
    public let limit: Double
    public let unit: String
    public let reason: String

    public init(
        scenarioName: String,
        metric: PerformanceMetric,
        limit: Double,
        unit: String = "milliseconds",
        reason: String
    ) {
        precondition(limit >= 0)
        self.scenarioName = scenarioName
        self.metric = metric
        self.limit = limit
        self.unit = unit
        self.reason = reason
    }
}

public enum PerformanceBudgetStatus: String, Codable, Equatable, Sendable {
    case passed
    case failed
    case missing
}

public struct PerformanceBudgetCheck: Codable, Equatable, Sendable {
    public let scenarioName: String
    public let metric: PerformanceMetric
    public let unit: String
    public let actual: Double?
    public let limit: Double
    public let status: PerformanceBudgetStatus
    public let reason: String

    public init(
        scenarioName: String,
        metric: PerformanceMetric,
        unit: String,
        actual: Double?,
        limit: Double,
        status: PerformanceBudgetStatus,
        reason: String
    ) {
        self.scenarioName = scenarioName
        self.metric = metric
        self.unit = unit
        self.actual = actual
        self.limit = limit
        self.status = status
        self.reason = reason
    }
}

public struct PerformanceBudgetDiagnostics: Codable, Equatable, Sendable {
    public let checks: [PerformanceBudgetCheck]
    public let passedCount: Int
    public let failedCount: Int
    public let missingCount: Int

    public init(checks: [PerformanceBudgetCheck]) {
        self.checks = checks
        self.passedCount = checks.filter { $0.status == .passed }.count
        self.failedCount = checks.filter { $0.status == .failed }.count
        self.missingCount = checks.filter { $0.status == .missing }.count
    }

    public var hasFailures: Bool {
        failedCount > 0 || missingCount > 0
    }
}

public enum PerformanceBudgetEvaluator {
    public static func evaluate(
        scenarios: [PerformanceScenarioResult],
        budgets: [PerformanceBudget]
    ) -> PerformanceBudgetDiagnostics {
        let scenariosByName = Dictionary(uniqueKeysWithValues: scenarios.map { ($0.name, $0) })
        let checks = budgets.map { budget in
            guard let scenario = scenariosByName[budget.scenarioName] else {
                return PerformanceBudgetCheck(
                    scenarioName: budget.scenarioName,
                    metric: budget.metric,
                    unit: budget.unit,
                    actual: nil,
                    limit: budget.limit,
                    status: .missing,
                    reason: budget.reason
                )
            }
            let actual = scenario.value(for: budget.metric)
            return PerformanceBudgetCheck(
                scenarioName: budget.scenarioName,
                metric: budget.metric,
                unit: budget.unit,
                actual: actual,
                limit: budget.limit,
                status: actual <= budget.limit ? .passed : .failed,
                reason: budget.reason
            )
        }
        return PerformanceBudgetDiagnostics(checks: checks)
    }
}

public enum PerformanceBudgetCatalog {
    public static func v0ProxyBudgets(windowCount: Int, soakEvents: Int) -> [PerformanceBudget] {
        baseBudgets()
            + windowBudgets(windowCount: windowCount)
            + soakBudgets(soakEvents: soakEvents)
    }

    private static func baseBudgets() -> [PerformanceBudget] {
        [
            PerformanceBudget(
                scenarioName: "cold-start-proxy",
                metric: .p99,
                limit: 800,
                reason: "proxy for Intel cold-start budget"
            ),
            PerformanceBudget(
                scenarioName: "hotkey-to-move-proxy",
                metric: .p99,
                limit: 5,
                reason: "proxy for hotkey dispatch-start budget"
            ),
            PerformanceBudget(
                scenarioName: "wake-from-sleep-proxy",
                metric: .max,
                limit: 500,
                reason: "wake recovery proxy budget"
            )
        ]
    }

    private static func windowBudgets(windowCount: Int) -> [PerformanceBudget] {
        [
            PerformanceBudget(
                scenarioName: "layout-recompute-\(windowCount)-windows",
                metric: .p95,
                limit: 4,
                reason: "layout recompute p95 budget"
            ),
            PerformanceBudget(
                scenarioName: "layout-recompute-\(windowCount)-windows",
                metric: .p99,
                limit: 16,
                reason: "layout recompute p99 budget"
            ),
            PerformanceBudget(
                scenarioName: "tag-switch-\(windowCount)-windows",
                metric: .p95,
                limit: 80,
                reason: "synthetic tag-switch p95 budget"
            ),
            PerformanceBudget(
                scenarioName: "state-snapshot-\(windowCount)-windows",
                metric: .p95,
                limit: 5,
                reason: "IPC state snapshot should stay cheap"
            ),
            PerformanceBudget(
                scenarioName: "recovery-journal-\(windowCount)-windows",
                metric: .p95,
                limit: 50,
                reason: "recovery journal I/O should stay bounded"
            ),
            PerformanceBudget(
                scenarioName: "thumbnail-generation-20-windows",
                metric: .p95,
                limit: 80,
                reason: "thumbnail cache generation should stay bounded"
            )
        ]
    }

    private static func soakBudgets(soakEvents: Int) -> [PerformanceBudget] {
        [
            PerformanceBudget(
                scenarioName: "soak-\(soakEvents)-events",
                metric: .p95,
                limit: 250,
                reason: "synthetic store soak throughput guard"
            )
        ]
    }
}
