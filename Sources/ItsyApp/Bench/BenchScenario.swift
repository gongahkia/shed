import CoreGraphics
import Foundation

enum BenchScenario: String {
    case palette
    case scroll
}

struct BenchScenarioRequest {
	let scenario: BenchScenario
	let query: String
	let expectedTop: String?
	let expectedResultCount: Int?
    let scrollDelta: CGFloat
    let exitAfterCompletion: Bool

    static func current(arguments: [String] = CommandLine.arguments) -> BenchScenarioRequest? {
        guard
            let rawScenario = value(for: "--bench-scenario", in: arguments),
            let scenario = BenchScenario(rawValue: rawScenario)
        else {
            return nil
        }
		let query = value(for: "--bench-query", in: arguments) ?? ""
		let expectedTop = value(for: "--bench-expected-top", in: arguments)
		let expectedResultCount = value(for: "--bench-expected-results", in: arguments).flatMap(Int.init)
		let scrollDelta = CGFloat(Double(value(for: "--bench-scroll-delta", in: arguments) ?? "-960") ?? -960)
        return BenchScenarioRequest(
			scenario: scenario,
			query: query,
			expectedTop: expectedTop,
			expectedResultCount: expectedResultCount,
            scrollDelta: scrollDelta,
            exitAfterCompletion: arguments.contains("--bench-exit-after-scenario")
        )
    }

    private static func value(for name: String, in arguments: [String]) -> String? {
        let prefix = "\(name)="
        return arguments.first(where: { $0.hasPrefix(prefix) }).map { String($0.dropFirst(prefix.count)) }
    }
}
