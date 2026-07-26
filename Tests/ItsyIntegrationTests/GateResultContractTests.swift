import Foundation
import Testing

@Test func gateResultEnvelopeAndAlphaReadinessAggregateStatuses() throws {
	let root = URL(fileURLWithPath: #filePath)
		.deletingLastPathComponent()
		.deletingLastPathComponent()
		.deletingLastPathComponent()
	let fixture = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-gate-result-\(UUID().uuidString)", isDirectory: true)
	try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
	defer { try? FileManager.default.removeItem(at: fixture) }
	let passed = fixture.appendingPathComponent("passed.json")
	let blocked = fixture.appendingPathComponent("blocked.json")
	let readiness = fixture.appendingPathComponent("alpha-readiness.json")

	let passResult = try runGate(root, arguments: [
		"--gate", "swift-tests", "--output", passed.path, "--", "/usr/bin/true",
	])
	#expect(passResult.status == 0)
	let passedEnvelope = try envelope(at: passed)
	#expect(passedEnvelope["status"] as? String == "passed")
	#expect((passedEnvelope["duration_ms"] as? Int ?? -1) >= 0)
	#expect((passedEnvelope["environment"] as? [String: String])?["architecture"]?.isEmpty == false)
	#expect(passedEnvelope["failure"] is NSNull)

	let blockedResult = try runGate(root, arguments: [
		"--gate", "dap-reference", "--output", blocked.path, "--blocked-exit", "7", "--", "/bin/sh", "-c", "exit 7",
	])
	#expect(blockedResult.status == 7)
	let blockedEnvelope = try envelope(at: blocked)
	#expect(blockedEnvelope["status"] as? String == "blocked")
	#expect((blockedEnvelope["failure"] as? [String: String])?["location"]?.hasSuffix(".log") == true)

	let aggregate = try runScript(root, script: "scripts/alpha_readiness.sh", arguments: ["--output", readiness.path, passed.path, blocked.path])
	#expect(aggregate.status == 2)
	let readinessEnvelope = try envelope(at: readiness)
	#expect(readinessEnvelope["gate"] as? String == "alpha-readiness")
	#expect(readinessEnvelope["status"] as? String == "blocked")
	#expect((readinessEnvelope["gates"] as? [[String: Any]])?.count == 2)
}

private struct GateProcessResult {
	let status: Int32
}

private func runGate(_ root: URL, arguments: [String]) throws -> GateProcessResult {
	try runScript(root, script: "scripts/run_gate.sh", arguments: arguments)
}

private func runScript(_ root: URL, script: String, arguments: [String]) throws -> GateProcessResult {
	let process = Process()
	process.executableURL = root.appendingPathComponent(script)
	process.arguments = arguments
	process.currentDirectoryURL = root
	process.standardOutput = Pipe()
	process.standardError = Pipe()
	try process.run()
	process.waitUntilExit()
	return GateProcessResult(status: process.terminationStatus)
}

private func envelope(at url: URL) throws -> [String: Any] {
	guard let value = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else {
		throw GateResultContractError.invalidEnvelope
	}
	return value
}

private enum GateResultContractError: Error {
	case invalidEnvelope
}
