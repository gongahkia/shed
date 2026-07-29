@testable import ItsyApp
import Foundation
import ItsyDAP
import ItsyDebugger
import Testing

@Test func integrationDAPLaunchHitsBreakpointAndResumes() async throws {
	let fixture = try DAPIntegrationFixture()
	defer {
		fixture.cleanup()
	}
	let sourceText = """
	func main() {
		let value = 41
		let stepped = value + 1 // BREAKPOINT
		print(stepped)
	}
	main()
	"""
	let sourceURL = try fixture.write("Sources/App.swift", sourceText)
	let breakLine = try #require(sourceText
		.split(separator: "\n", omittingEmptySubsequences: false)
		.firstIndex { $0.contains("BREAKPOINT") }
		.map { $0 + 1 })
	let adapterURL = try buildMockDAPAdapter(in: fixture.root)
	let logURL = fixture.root.appendingPathComponent("dap-requests.jsonl")
	let breakpointStoreURL = fixture.root.appendingPathComponent("breakpoints.json")
	let breakpointStore = BreakpointStore(fileURL: breakpointStoreURL)
	await breakpointStore.replace([SourceBreakpoint(line: breakLine)], for: sourceURL)
	try await breakpointStore.save()

	let session = try await DebugAppSession.start(
		adapter: DebugAdapterConfig(id: "vscode-js-debug", command: adapterURL.path, args: [logURL.path, sourceURL.path, "\(breakLine)"]),
		configuration: DebugLaunchConfiguration(
			name: "Mock DAP",
			type: "mock",
			request: DebugLaunchRequest.launch,
			program: "${workspaceFolder}/.build/mock-debuggee",
			cwd: "${workspaceFolder}",
			exceptionFilters: ["swift"],
			sourceMap: ["/remote/source": "${workspaceFolder}/Sources"],
			adapterOptions: ["type": .string("lldb"), "sourceLanguages": .array([.string("rust")])]
		),
		workspaceRoot: fixture.root,
		breakpointStore: breakpointStore
	)
	defer {
		session.terminate()
	}

	try await waitForState(.stopped, in: session.client)
	let threads = try await session.debugSession.refreshThreads()
	#expect(threads == [DebugThread(id: 1, name: "main")])
	let frames = try await session.debugSession.stackFrames(for: 1)
	let frame = try #require(frames.first)
	#expect(frame.sourcePath == sourceURL.standardizedFileURL.path)
	#expect(frame.line == breakLine)

	try await session.debugSession.continueExecution(threadID: 1)
	try await waitForState(.running, in: session.client)

	let requests = try loggedRequests(at: logURL)
	let commands = requests.compactMap { $0["command"] as? String }
	#expect(commands.contains(DAPCommand.launch))
	#expect(commands.contains(DAPCommand.setBreakpoints))
	#expect(commands.contains(DAPCommand.configurationDone))
	#expect(commands.contains(DAPCommand.continueExecution))
	let setBreakpoints = try #require(requests.first { $0["command"] as? String == DAPCommand.setBreakpoints })
	let launch = try #require(requests.first { $0["command"] as? String == DAPCommand.launch })
	let launchArguments = try #require(launch["arguments"] as? [String: Any])
	#expect(launchArguments["type"] as? String == "lldb")
	#expect(launchArguments["sourceLanguages"] as? [String] == ["rust"])
	#expect(launchArguments["autoAttachChildProcesses"] as? Bool == false)
	#expect((launchArguments["sourceMap"] as? [String: String])?["/remote/source"] == fixture.root.appendingPathComponent("Sources").path)
	let arguments = try #require(setBreakpoints["arguments"] as? [String: Any])
	let source = try #require(arguments["source"] as? [String: Any])
	let breakpoints = try #require(arguments["breakpoints"] as? [[String: Any]])
	#expect(source["path"] as? String == sourceURL.standardizedFileURL.path)
	#expect(breakpoints.first?["line"] as? Int == breakLine)
}

private enum DAPIntegrationError: Error, CustomStringConvertible {
	case processFailed(String, Int32, String)
	case timeout(DAPClientState)

	var description: String {
		switch self {
		case let .processFailed(command, status, output):
			return "\(command) failed \(status): \(output)"
		case let .timeout(state):
			return "timed out waiting for \(state)"
		}
	}
}

private final class DAPIntegrationFixture {
	let root: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-dap-integration-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ path: String, _ contents: String, fileManager: FileManager = .default) throws -> URL {
		let url = root.appendingPathComponent(path)
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
		return url
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}

private func waitForState(_ state: DAPClientState, in client: DAPClientSession) async throws {
	for _ in 0 ..< 200 {
		if await client.state == state {
			return
		}
		try await Task.sleep(nanoseconds: 10_000_000)
	}
	throw DAPIntegrationError.timeout(state)
}

private func buildMockDAPAdapter(in directory: URL) throws -> URL {
	let sourceURL = directory.appendingPathComponent("MockDAPAdapter.swift")
	let adapterURL = directory.appendingPathComponent("mock-dap")
	try mockDAPAdapterSource.write(to: sourceURL, atomically: true, encoding: .utf8)
	let result = try runProcess(URL(fileURLWithPath: "/usr/bin/xcrun"), arguments: ["swiftc", sourceURL.path, "-o", adapterURL.path])
	guard result.status == 0 else {
		throw DAPIntegrationError.processFailed("xcrun swiftc", result.status, result.output)
	}
	return adapterURL
}

private func loggedRequests(at url: URL) throws -> [[String: Any]] {
	let text = try String(contentsOf: url, encoding: .utf8)
	return try text.split(whereSeparator: \.isNewline).map { line in
		let data = Data(line.utf8)
		guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
			return [:]
		}
		return object
	}
}

private struct ProcessResult {
	var status: Int32
	var output: String
}

private func runProcess(_ executableURL: URL, arguments: [String]) throws -> ProcessResult {
	let process = Process()
	process.executableURL = executableURL
	process.arguments = arguments
	let stdout = Pipe()
	let stderr = Pipe()
	process.standardOutput = stdout
	process.standardError = stderr
	try process.run()
	process.waitUntilExit()
	let output = [stdout.fileHandleForReading.readDataToEndOfFile(), stderr.fileHandleForReading.readDataToEndOfFile()]
		.compactMap { String(data: $0, encoding: .utf8) }
		.joined()
	return ProcessResult(status: process.terminationStatus, output: output)
}

private let mockDAPAdapterSource = #"""
import Foundation

let logURL = URL(fileURLWithPath: CommandLine.arguments[1])
let sourcePath = CommandLine.arguments[2]
let breakLine = Int(CommandLine.arguments[3])!
FileManager.default.createFile(atPath: logURL.path, contents: nil)
let logHandle = try FileHandle(forWritingTo: logURL)
defer {
	try? logHandle.close()
}

var buffer = Data()
var nextSeq = 1
let headerMarker = Data("\r\n\r\n".utf8)

func nextPayload() -> Data? {
	guard let headerRange = buffer.range(of: headerMarker),
	      let header = String(data: Data(buffer[..<headerRange.lowerBound]), encoding: .utf8)
	else {
		return nil
	}
	let length = header
		.components(separatedBy: "\r\n")
		.compactMap { line -> Int? in
			let parts = line.split(separator: ":", maxSplits: 1)
			guard parts.count == 2, parts[0].lowercased() == "content-length" else {
				return nil
			}
			return Int(String(parts[1]).trimmingCharacters(in: .whitespaces))
		}
		.first
	guard let length else {
		return nil
	}
	let bodyStart = headerRange.upperBound
	guard buffer.count >= bodyStart + length else {
		return nil
	}
	let payload = buffer[bodyStart ..< bodyStart + length]
	buffer.removeSubrange(0 ..< bodyStart + length)
	return Data(payload)
}

func write(_ object: [String: Any]) {
	let payload = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
	var frame = Data("Content-Length: \(payload.count)\r\n\r\n".utf8)
	frame.append(payload)
	try! FileHandle.standardOutput.write(contentsOf: frame)
}

func log(_ request: [String: Any]) {
	let payload = try! JSONSerialization.data(withJSONObject: request, options: [.sortedKeys])
	logHandle.write(payload)
	logHandle.write(Data("\n".utf8))
}

func response(to request: [String: Any], body: [String: Any]? = nil) {
	var object: [String: Any] = [
		"seq": nextSeq,
		"type": "response",
		"request_seq": request["seq"] as! Int,
		"success": true,
		"command": request["command"] as! String
	]
	nextSeq += 1
	if let body {
		object["body"] = body
	}
	write(object)
}

func event(_ name: String, body: [String: Any]? = nil) {
	var object: [String: Any] = [
		"seq": nextSeq,
		"type": "event",
		"event": name
	]
	nextSeq += 1
	if let body {
		object["body"] = body
	}
	write(object)
}

func handle(_ payload: Data) {
	guard let request = try! JSONSerialization.jsonObject(with: payload) as? [String: Any],
	      let command = request["command"] as? String
	else {
		return
	}
	log(request)
	switch command {
	case "initialize":
		response(to: request, body: [
			"supportsConfigurationDoneRequest": true,
			"supportsSetVariable": true
		])
		event("initialized")
	case "launch":
		response(to: request)
	case "setBreakpoints":
		let arguments = request["arguments"] as? [String: Any]
		let breakpoints = arguments?["breakpoints"] as? [[String: Any]] ?? []
		response(to: request, body: [
			"breakpoints": breakpoints.map {
				["verified": true, "line": $0["line"] as? Int ?? breakLine]
			}
		])
	case "setExceptionBreakpoints":
		response(to: request, body: ["breakpoints": []])
	case "configurationDone":
		response(to: request)
		event("stopped", body: [
			"reason": "breakpoint",
			"threadId": 1,
			"allThreadsStopped": true
		])
	case "threads":
		response(to: request, body: [
			"threads": [["id": 1, "name": "main"]]
		])
	case "stackTrace":
		response(to: request, body: [
			"stackFrames": [[
				"id": 7,
				"name": "main",
				"source": ["name": URL(fileURLWithPath: sourcePath).lastPathComponent, "path": sourcePath],
				"line": breakLine,
				"column": 1
			]],
			"totalFrames": 1
		])
	case "continue":
		response(to: request, body: ["allThreadsContinued": true])
		event("continued", body: ["threadId": 1, "allThreadsContinued": true])
	case "disconnect", "terminate":
		response(to: request)
		exit(0)
	default:
		response(to: request, body: [:])
	}
}

while true {
	let chunk = FileHandle.standardInput.availableData
	if chunk.isEmpty {
		break
	}
	buffer.append(chunk)
	while let payload = nextPayload() {
		handle(payload)
	}
}
"""#
