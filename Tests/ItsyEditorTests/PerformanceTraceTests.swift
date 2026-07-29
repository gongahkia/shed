@testable import ItsyEditor
import Foundation
import Testing

@MainActor @Test func performanceTraceWritesCorrelatedJSONLines() throws {
	let url = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-performance-trace-\(UUID().uuidString).jsonl")
	defer { try? FileManager.default.removeItem(at: url) }
	let id = try #require(PerformanceTrace.record("palette.query", attributes: ["query_bytes": "3"], path: url.path))
	_ = PerformanceTrace.record("palette.results", id: id, attributes: ["result_count": "1"], path: url.path)
	let lines = try String(contentsOf: url, encoding: .utf8).split(whereSeparator: \.isNewline)
	let events = try lines.map { try JSONDecoder().decode(PerformanceTraceEvent.self, from: Data($0.utf8)) }

	#expect(events.map(\.name) == ["palette.query", "palette.results"])
	#expect(events.map(\.id) == [id, id])
	#expect(events[0].attributes["query_bytes"] == "3")
}
