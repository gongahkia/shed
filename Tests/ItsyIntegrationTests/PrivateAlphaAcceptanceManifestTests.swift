import Foundation
import Testing

@Test func privateAlphaManifestIsCompleteAndExecutable() throws {
	let root = URL(fileURLWithPath: #filePath)
		.deletingLastPathComponent()
		.deletingLastPathComponent()
		.deletingLastPathComponent()
	let manifestURL = root.appendingPathComponent("qa/private-alpha-v1.json")
	let manifest = try JSONDecoder().decode(PrivateAlphaManifest.self, from: Data(contentsOf: manifestURL))
	let expectedIssues = Set(43 ... 100)
	let allowedRequirements: Set<String> = ["git", "debugpy", "js-debug", "delve", "lldb-dap", "codelldb"]

	#expect(manifest.schemaVersion == 1)
	#expect(manifest.suite == "private-alpha-v1")
	#expect(Set(manifest.scenarios.map(\.issue)) == expectedIssues)
	#expect(manifest.scenarios.count == expectedIssues.count)
	#expect(Set(manifest.scenarios.map(\.id)).count == manifest.scenarios.count)

	for scenario in manifest.scenarios {
		#expect(scenario.id == "\(scenario.milestone)-\(scenario.issue)")
		#expect(["M1", "M2", "M3", "M4"].contains(scenario.milestone))
		#expect(["editing", "lsp", "git", "devloop"].contains(scenario.area))
		#expect(Set(scenario.requirements).isSubset(of: allowedRequirements))
		#expect(!scenario.tests.isEmpty)
		#expect(scenario.issue == 100 || scenario.requiredForAlpha)
		#expect(scenario.issue != 100 || !scenario.requiredForAlpha)
		for test in scenario.tests {
			let fixtureURL = root.appendingPathComponent(test.fixture)
			#expect(FileManager.default.fileExists(atPath: fixtureURL.path))
			let source = try String(contentsOf: fixtureURL, encoding: .utf8)
			#expect(source.contains("func \(test.filter)"))
		}
	}
}

private struct PrivateAlphaManifest: Decodable {
	let schemaVersion: Int
	let suite: String
	let scenarios: [PrivateAlphaScenario]
}

private struct PrivateAlphaScenario: Decodable {
	let id: String
	let issue: Int
	let milestone: String
	let area: String
	let requiredForAlpha: Bool
	let requirements: [String]
	let tests: [PrivateAlphaTest]
}

private struct PrivateAlphaTest: Decodable {
	let filter: String
	let fixture: String
}
