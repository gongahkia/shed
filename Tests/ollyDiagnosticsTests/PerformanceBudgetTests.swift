import XCTest
import ollyDiagnostics

final class PerformanceBudgetTests: XCTestCase {
    func testEvaluatorMarksPassingBudget() {
        let diagnostics = PerformanceBudgetEvaluator.evaluate(
            scenarios: [
                scenario(name: "layout-recompute-50-windows", p95: 2.5, p99: 8)
            ],
            budgets: [
                PerformanceBudget(
                    scenarioName: "layout-recompute-50-windows",
                    metric: .p95,
                    limit: 4,
                    reason: "layout budget"
                )
            ]
        )

        XCTAssertEqual(diagnostics.passedCount, 1)
        XCTAssertEqual(diagnostics.failedCount, 0)
        XCTAssertEqual(diagnostics.missingCount, 0)
        XCTAssertFalse(diagnostics.hasFailures)
        XCTAssertEqual(diagnostics.checks.first?.status, .passed)
    }

    func testEvaluatorMarksFailingBudget() {
        let diagnostics = PerformanceBudgetEvaluator.evaluate(
            scenarios: [
                scenario(name: "hotkey-to-move-proxy", p99: 7)
            ],
            budgets: [
                PerformanceBudget(
                    scenarioName: "hotkey-to-move-proxy",
                    metric: .p99,
                    limit: 5,
                    reason: "hotkey budget"
                )
            ]
        )

        XCTAssertEqual(diagnostics.failedCount, 1)
        XCTAssertTrue(diagnostics.hasFailures)
        XCTAssertEqual(diagnostics.checks.first?.actual, 7)
        XCTAssertEqual(diagnostics.checks.first?.status, .failed)
    }

    func testEvaluatorMarksMissingScenario() {
        let diagnostics = PerformanceBudgetEvaluator.evaluate(
            scenarios: [],
            budgets: [
                PerformanceBudget(
                    scenarioName: "state-snapshot-50-windows",
                    metric: .p95,
                    limit: 5,
                    reason: "state snapshot budget"
                )
            ]
        )

        XCTAssertEqual(diagnostics.missingCount, 1)
        XCTAssertTrue(diagnostics.hasFailures)
        XCTAssertNil(diagnostics.checks.first?.actual)
        XCTAssertEqual(diagnostics.checks.first?.status, .missing)
    }

    func testCatalogUsesRequestedWindowAndSoakCounts() {
        let budgets = PerformanceBudgetCatalog.v0ProxyBudgets(windowCount: 12, soakEvents: 345)
        let scenarioNames = budgets.map(\.scenarioName)

        XCTAssertTrue(scenarioNames.contains("layout-recompute-12-windows"))
        XCTAssertTrue(scenarioNames.contains("state-snapshot-12-windows"))
        XCTAssertTrue(scenarioNames.contains("soak-345-events"))
    }

    func testCatalogIncludesReleaseScenarioBudgets() {
        let budgets = PerformanceBudgetCatalog.v0ProxyBudgets(windowCount: 50, soakEvents: 5_000)
        let budgetsByScenario = Dictionary(grouping: budgets, by: \.scenarioName)

        XCTAssertEqual(budgetsByScenario["scratchpad-toggle-latency"]?.first?.metric, .p99)
        XCTAssertEqual(budgetsByScenario["scratchpad-toggle-latency"]?.first?.limit, 60)
        XCTAssertEqual(budgetsByScenario["permission-revoke-recovery"]?.first?.metric, .max)
        XCTAssertEqual(budgetsByScenario["permission-revoke-recovery"]?.first?.limit, 1_500)
        XCTAssertEqual(budgetsByScenario["space-drift-verify"]?.first?.metric, .p95)
        XCTAssertEqual(budgetsByScenario["space-drift-verify"]?.first?.limit, 25)
        XCTAssertEqual(budgetsByScenario["focus-rate-limit-eval"]?.first?.metric, .p99)
        XCTAssertEqual(budgetsByScenario["focus-rate-limit-eval"]?.first?.limit, 0.5)
        XCTAssertEqual(budgetsByScenario["animated-arrange"]?.first?.metric, .max)
        XCTAssertEqual(budgetsByScenario["animated-arrange"]?.first?.limit, 2)
        XCTAssertEqual(budgetsByScenario["animated-arrange"]?.first?.unit, "baseline-ratio")
        XCTAssertEqual(budgetsByScenario["thumbnail-cache-fill-20-windows"]?.first?.metric, .p95)
        XCTAssertEqual(budgetsByScenario["thumbnail-cache-fill-20-windows"]?.first?.limit, 80)
    }

    private func scenario(
        name: String,
        p50: Double = 1,
        p95: Double = 1,
        p99: Double = 1,
        max: Double = 1
    ) -> PerformanceScenarioResult {
        PerformanceScenarioResult(
            name: name,
            unit: "milliseconds",
            sampleCount: 10,
            p50: p50,
            p95: p95,
            p99: p99,
            min: 0,
            max: max
        )
    }
}
