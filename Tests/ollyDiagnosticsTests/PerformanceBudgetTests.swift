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
