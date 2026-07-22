import Foundation
import ItsyDAP
import Testing

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.debugpy)))
func dapReferenceDebugpyLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.debugpy)
}

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.jsDebug)))
func dapReferenceJSLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.jsDebug)
}

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.delve)))
func dapReferenceDelveLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.delve)
}

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.lldbC)))
func dapReferenceLLDBCLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.lldbC)
}

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.lldbCPP)))
func dapReferenceLLDBCPPLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.lldbCPP)
}

@Test(.enabled(if: DAPReferenceAdapterHarness.isEnabled(.codelldb)))
func dapReferenceCodeLLDBRustLaunchBreakpointStepStackVariablesEvaluateAndTermination() async throws {
	try await DAPReferenceAdapterHarness.run(.codelldb)
}

private enum DAPReferenceAdapter: String {
	case debugpy
	case jsDebug = "js-debug"
	case delve
	case lldbC = "lldb-c"
	case lldbCPP = "lldb-cpp"
	case codelldb

	var runtimeAdapter: String {
		switch self {
		case .lldbC, .lldbCPP:
			return "lldb-dap"
		case .codelldb:
			return "codelldb"
		default:
			return rawValue
		}
	}
}

private enum DAPReferenceLanguage {
	case python
	case javascript
	case go
	case c
	case cpp
	case rust
}

private struct DAPReferenceScenario {
	let adapter: DAPReferenceAdapter
	let language: DAPReferenceLanguage
	let adapterType: String

	var breakpointLine: Int {
		switch language {
		case .python, .javascript:
			return 2
		case .go:
			return 5
		case .c, .cpp:
			return 4
		case .rust:
			return 3
		}
	}

	var sourceName: String {
		switch language {
		case .python:
			return "main.py"
		case .javascript:
			return "main.js"
		case .go:
			return "main.go"
		case .c:
			return "main.c"
		case .cpp:
			return "main.cpp"
		case .rust:
			return "main.rs"
		}
	}

	var source: String {
		switch language {
		case .python:
			return """
			value = 40
			value += 1
			value += 1
			print(value)
			"""
		case .javascript:
			return """
			let value = 40;
			value += 1;
			value += 1;
			console.log(value);
			"""
		case .go:
			return """
			package main
			import "fmt"
			func main() {
				value := 40
				value += 1
				value += 1
				fmt.Println(value)
			}
			"""
		case .c:
			return """
			#include <stdio.h>
			int main(void) {
				int value = 40;
				value += 1;
				value += 1;
				printf("%d\\n", value);
				return 0;
			}
			"""
		case .cpp:
			return """
			#include <iostream>
			int main() {
				int value = 40;
				value += 1;
				value += 1;
				std::cout << value << '\\n';
				return 0;
			}
			"""
		case .rust:
			return """
			fn main() {
			    let mut value = 40;
			    value += 1;
			    value += 1;
			    println!("{value}");
			}
			"""
		}
	}
}

private enum DAPReferenceAdapterHarness {
	static var requiresInstalledAdapters: Bool {
		ProcessInfo.processInfo.environment["ITSY_DAP_REQUIRED"] == "1"
	}

	static func isEnabled(_ adapter: DAPReferenceAdapter) -> Bool {
		if adapter == .lldbC || adapter == .lldbCPP {
			return requiresInstalledAdapters || ProcessInfo.processInfo.environment["ITSY_DAP_LLDB"] != nil
		}
		if adapter == .codelldb {
			return requiresInstalledAdapters || ProcessInfo.processInfo.environment["ITSY_DAP_CODELLDB"] != nil
		}
		return requiresInstalledAdapters || command(for: adapter) != nil
	}

	static func run(_ adapter: DAPReferenceAdapter) async throws {
		guard let command = command(for: adapter) else {
			throw DAPReferenceAdapterError(adapter: adapter, step: "adapter discovery", logURL: nil, underlying: "Set the adapter environment variable or install the adapter.")
		}
		let scenario = scenario(for: adapter)
		let fixture = try DAPReferenceFixture(scenario: scenario)
		let driver = try DAPReferenceAdapterDriver(adapter: adapter, command: command, fixture: fixture)
		defer {
			driver.terminate()
		}
		try await driver.run(scenario: scenario)
	}

	private static func scenario(for adapter: DAPReferenceAdapter) -> DAPReferenceScenario {
		switch adapter {
		case .debugpy:
			return DAPReferenceScenario(adapter: adapter, language: .python, adapterType: "python")
		case .jsDebug:
			return DAPReferenceScenario(adapter: adapter, language: .javascript, adapterType: "pwa-node")
		case .delve:
			return DAPReferenceScenario(adapter: adapter, language: .go, adapterType: "go")
		case .lldbC:
			return DAPReferenceScenario(adapter: adapter, language: .c, adapterType: "lldb-dap")
		case .lldbCPP:
			return DAPReferenceScenario(adapter: adapter, language: .cpp, adapterType: "lldb-dap")
		case .codelldb:
			return DAPReferenceScenario(adapter: adapter, language: .rust, adapterType: "lldb")
		}
	}

	private static func command(for adapter: DAPReferenceAdapter) -> DAPReferenceAdapterCommand? {
		switch adapter {
		case .debugpy:
			guard let python = executable(named: ProcessInfo.processInfo.environment["ITSY_DAP_DEBUGPY"] ?? "python3"),
			      processSucceeds(executableURL: python, arguments: ["-c", "import debugpy"])
			else {
				return nil
			}
			return DAPReferenceAdapterCommand(executableURL: python, arguments: ["-m", "debugpy.adapter"])
		case .jsDebug:
			guard let script = ProcessInfo.processInfo.environment["ITSY_DAP_JS_DEBUG"],
			      FileManager.default.isReadableFile(atPath: script),
			      let node = executable(named: ProcessInfo.processInfo.environment["ITSY_DAP_NODE"] ?? "node")
			else {
				return nil
			}
			return DAPReferenceAdapterCommand(executableURL: node, arguments: [script])
		case .delve:
			guard let delve = executable(named: ProcessInfo.processInfo.environment["ITSY_DAP_DELVE"] ?? "dlv") else {
				return nil
			}
			return DAPReferenceAdapterCommand(executableURL: delve, arguments: ["dap", "--log", "--log-output=dap"])
		case .lldbC, .lldbCPP:
			guard let lldb = ProcessInfo.processInfo.environment["ITSY_DAP_LLDB"].flatMap(executable(named:)) ?? xcrunLLDBDAP() else {
				return nil
			}
			return DAPReferenceAdapterCommand(executableURL: lldb)
		case .codelldb:
			guard let codelldb = ProcessInfo.processInfo.environment["ITSY_DAP_CODELLDB"].flatMap(executable(named:)) else {
				return nil
			}
			return DAPReferenceAdapterCommand(executableURL: codelldb)
		}
	}

	private static func executable(named name: String) -> URL? {
		let expanded = NSString(string: name).expandingTildeInPath
		if expanded.contains("/") {
			let url = URL(fileURLWithPath: expanded).standardizedFileURL
			return FileManager.default.isExecutableFile(atPath: url.path) ? url : nil
		}
		let path = ProcessInfo.processInfo.environment["PATH"] ?? ""
		for directory in path.split(separator: ":") {
			let url = URL(fileURLWithPath: String(directory)).appendingPathComponent(expanded)
			if FileManager.default.isExecutableFile(atPath: url.path) {
				return url
			}
		}
		return nil
	}

	private static func xcrunLLDBDAP() -> URL? {
		guard let xcrun = executable(named: "xcrun") else {
			return nil
		}
		let process = Process()
		let output = Pipe()
		process.executableURL = xcrun
		process.arguments = ["--find", "lldb-dap"]
		process.standardOutput = output
		process.standardError = Pipe()
		guard (try? process.run()) != nil else {
			return nil
		}
		process.waitUntilExit()
		guard process.terminationStatus == 0,
		      let path = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
		      !path.isEmpty
		else {
			return nil
		}
		return executable(named: path)
	}

	private static func processSucceeds(executableURL: URL, arguments: [String]) -> Bool {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = arguments
		process.standardOutput = Pipe()
		process.standardError = Pipe()
		guard (try? process.run()) != nil else {
			return false
		}
		process.waitUntilExit()
		return process.terminationStatus == 0
	}
}

private struct DAPReferenceAdapterCommand {
	let executableURL: URL
	let arguments: [String]

	init(executableURL: URL, arguments: [String] = []) {
		self.executableURL = executableURL
		self.arguments = arguments
	}
}

private final class DAPReferenceAdapterDriver: @unchecked Sendable {
	private let adapter: DAPReferenceAdapter
	private let fixture: DAPReferenceFixture
	private let transport: DAPProcessTransport
	private let client: DAPClientSession
	private let log: DAPReferenceLog
	private let eventPump: Task<Void, Never>

	init(adapter: DAPReferenceAdapter, command: DAPReferenceAdapterCommand, fixture: DAPReferenceFixture) throws {
		self.adapter = adapter
		self.fixture = fixture
		log = try DAPReferenceLog(fileURL: fixture.logURL)
		let transport = DAPProcessTransport(
			executableURL: command.executableURL,
			arguments: command.arguments,
			currentDirectoryURL: fixture.root,
			environment: ProcessInfo.processInfo.environment
		)
		self.transport = transport
		let client = DAPClientSession(transport: DAPReferenceLoggingTransport(transport: transport, log: log))
		self.client = client
		let log = self.log
		eventPump = Task.detached(priority: .userInitiated) {
			for await event in transport.events {
				switch event {
				case let .stdout(data):
					log.append("stdout", data: data)
					do {
						_ = try await client.receive(data)
					} catch {
						log.append("receive-error \(error)\\n")
					}
				case let .stderr(data):
					log.append("stderr", data: data)
				case let .terminated(status):
					log.append("transport-terminated \(status)\\n")
					await client.transportDidTerminate(status: status)
				}
			}
		}
		try transport.start()
	}

	func terminate() {
		eventPump.cancel()
		transport.terminate()
	}

	func run(scenario: DAPReferenceScenario) async throws {
		let initialized = await client.on(event: DAPEvent.initialized)
		let capabilities = try await request("initialize") {
			let response = try await self.client.initialize(clientCapabilities: DAPInitializeRequestArguments(
				clientID: "itsy-reference-tests",
				clientName: "Itsy DAP reference tests",
				adapterID: scenario.adapter.runtimeAdapter,
				linesStartAt1: true,
				columnsStartAt1: true,
				pathFormat: "path",
				supportsVariableType: true,
				supportsInvalidatedEvent: true
			))
			return try self.decode(response, as: DAPCapabilities.self)
		}
		let firstStopped = await client.on(event: DAPEvent.stopped)
		log.append("step-start launch\\n")
		let launchTask = Task {
			self.log.append("request launch\\n")
			let response = try await self.client.launch(arguments: DAPAny.object(self.launchArguments(scenario)))
			self.log.append("response launch\\n")
			return response
		}
		await Task.yield()
		if await client.state == .initializing {
			_ = try await nextEvent(initialized, step: "initialized event")
		}
		log.append("client-state \(await client.state)\\n")
		log.append("step-start set breakpoints\\n")
		let breakpointTask = Task {
			self.log.append("request set breakpoints\\n")
			let response = try await self.client.setBreakpoints(DAPSetBreakpointsArguments(
				source: DAPSource(name: scenario.sourceName, path: self.fixture.sourceURL.path),
				breakpoints: [DAPSourceBreakpoint(line: scenario.breakpointLine)]
			))
			let result = try self.decode(response, as: DAPSetBreakpointsResponseBody.self)
			self.log.append("response set breakpoints\\n")
			return result
		}
		await Task.yield()
		let configurationTask = capabilities.supportsConfigurationDoneRequest == true ? Task {
			self.log.append("step-start configuration done\\n")
			let response = try await self.client.configurationDone()
			self.log.append("response configuration done\\n")
			return response
		} : nil
		await Task.yield()
		let breakpoints = try await request("set breakpoints") {
			try await breakpointTask.value
		}
		guard breakpoints.breakpoints.count == 1 else {
			throw error(step: "set breakpoints", underlying: "Adapter returned \(breakpoints.breakpoints.count) breakpoint statuses.")
		}
		if let configurationTask {
			_ = try await request("configuration done") {
				try await configurationTask.value
			}
		}
		_ = try await request("launch") {
			try await launchTask.value
		}
		_ = try await nextEvent(firstStopped, step: "first stopped event")
		let threads = try await request("threads") {
			let response = try await self.client.sendRequest(command: DAPCommand.threads)
			return try self.decode(response, as: DAPThreadsResponseBody.self)
		}
		guard let thread = threads.threads.first else {
			throw error(step: "threads", underlying: "Adapter returned no threads.")
		}
		let frames = try await request("stack trace") {
			let response = try await self.client.sendRequest(command: DAPCommand.stackTrace, arguments: try DAPAny(encoding: DAPStackTraceArguments(threadId: thread.id)))
			return try self.decode(response, as: DAPStackTraceResponseBody.self)
		}
		guard let frame = frames.stackFrames.first else {
			throw error(step: "stack trace", underlying: "Adapter returned no stack frames.")
		}
		let scopes = try await request("scopes") {
			let response = try await self.client.sendRequest(command: DAPCommand.scopes, arguments: try DAPAny(encoding: DAPScopesArguments(frameId: frame.id)))
			return try self.decode(response, as: DAPScopesResponseBody.self)
		}
		guard let scope = scopes.scopes.first(where: { $0.variablesReference > 0 }) else {
			throw error(step: "scopes", underlying: "Adapter returned no expandable scope.")
		}
		_ = try await request("variables") {
			let response = try await self.client.sendRequest(command: DAPCommand.variables, arguments: try DAPAny(encoding: DAPVariablesArguments(variablesReference: scope.variablesReference)))
			return try self.decode(response, as: DAPVariablesResponseBody.self)
		}
		let value = try await request("evaluate") {
			let response = try await self.client.sendRequest(command: DAPCommand.evaluate, arguments: try DAPAny(encoding: DAPEvaluateArguments(expression: "value", frameId: frame.id, context: "watch")))
			return try self.decode(response, as: DAPEvaluateResponseBody.self)
		}
		guard !value.result.isEmpty else {
			throw error(step: "evaluate", underlying: "Adapter returned an empty result.")
		}
		let nextStopped = await client.on(event: DAPEvent.stopped)
		_ = try await request("step") {
			try await self.client.sendRequest(command: DAPCommand.next, arguments: try DAPAny(encoding: DAPNextArguments(threadId: thread.id)))
		}
		_ = try await nextEvent(nextStopped, step: "step stopped event")
		let terminated = await client.on(event: DAPEvent.terminated)
		_ = try await request("continue") {
			try await self.client.sendRequest(command: DAPCommand.continueExecution, arguments: try DAPAny(encoding: DAPContinueArguments(threadId: thread.id)))
		}
		_ = try await nextEvent(terminated, step: "terminated event")
		_ = try await request("disconnect") {
			try await self.client.disconnect()
		}
	}

	private func launchArguments(_ scenario: DAPReferenceScenario) -> [String: DAPAny] {
		var arguments: [String: DAPAny] = [
			"name": .string("Itsy reference \(scenario.adapter.rawValue)"),
			"type": .string(scenario.adapterType),
			"request": .string("launch"),
			"program": .string(fixture.programURL.path),
			"cwd": .string(fixture.root.path),
			"stopOnEntry": .bool(false),
		]
		switch scenario.adapter {
		case .delve:
			arguments["mode"] = .string("debug")
			arguments["program"] = .string(fixture.sourceURL.path)
		case .debugpy, .jsDebug, .lldbC, .lldbCPP:
			break
		case .codelldb:
			arguments["sourceLanguages"] = .array([.string("rust")])
		}
		return arguments
	}

	private func request<Value>(_ step: String, _ operation: @escaping () async throws -> Value) async throws -> Value {
		log.append("step-start \(step)\\n")
		do {
			let value = try await withThrowingTaskGroup(of: Value.self) { group in
				group.addTask {
					try await operation()
				}
				group.addTask {
					try await Task.sleep(nanoseconds: 15_000_000_000)
					throw self.error(step: step, underlying: "Timed out after 15 seconds.")
				}
				let value = try await group.next()!
				group.cancelAll()
				return value
			}
			log.append("step-success \(step)\\n")
			return value
		} catch {
			throw self.error(step: step, underlying: error)
		}
	}

	private func decode<Value: Decodable>(_ response: DAPResponse, as type: Value.Type) throws -> Value {
		guard let body = response.body,
		      let data = try? JSONEncoder().encode(body),
		      let value = try? JSONDecoder().decode(type, from: data)
		else {
			throw error(step: "response decoding", underlying: "Missing or invalid \(String(describing: type)) response body.")
		}
		return value
	}

	private func nextEvent(_ stream: AsyncStream<DAPEventMessage>, step: String) async throws -> DAPEventMessage {
		do {
			return try await withThrowingTaskGroup(of: DAPEventMessage.self) { group in
				group.addTask {
					for await event in stream {
						return event
					}
					throw DAPReferenceAdapterError(adapter: self.adapter, step: step, logURL: self.fixture.logURL, underlying: "Adapter event stream ended.")
				}
				group.addTask {
					try await Task.sleep(nanoseconds: 15_000_000_000)
					throw DAPReferenceAdapterError(adapter: self.adapter, step: step, logURL: self.fixture.logURL, underlying: "Timed out after 15 seconds.")
				}
				let event = try await group.next()!
				group.cancelAll()
				return event
			}
		} catch let error as DAPReferenceAdapterError {
			throw error
		} catch {
			throw self.error(step: step, underlying: error)
		}
	}

	private func error(step: String, underlying: Any) -> DAPReferenceAdapterError {
		DAPReferenceAdapterError(adapter: adapter, step: step, logURL: fixture.logURL, underlying: String(describing: underlying))
	}
}

private struct DAPReferenceFixture {
	let root: URL
	let sourceURL: URL
	let programURL: URL
	let logURL: URL

	init(scenario: DAPReferenceScenario, fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-dap-reference-\(scenario.adapter.rawValue)-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
		sourceURL = root.appendingPathComponent(scenario.sourceName)
		logURL = root.appendingPathComponent("adapter.log")
		fileManager.createFile(atPath: logURL.path, contents: nil)
		try scenario.source.write(to: sourceURL, atomically: true, encoding: .utf8)
		let builtProgramURL = root.appendingPathComponent("debuggee")
		switch scenario.language {
		case .python, .javascript:
			programURL = sourceURL
		case .go:
			try Self.runBuild(root: root, logURL: logURL, adapter: scenario.adapter, executable: "go", arguments: ["build", "-gcflags=all=-N -l", "-o", builtProgramURL.path, sourceURL.path])
			programURL = builtProgramURL
		case .c:
			try Self.runBuild(root: root, logURL: logURL, adapter: scenario.adapter, executable: "clang", arguments: ["-g", "-O0", sourceURL.path, "-o", builtProgramURL.path])
			programURL = builtProgramURL
		case .cpp:
			try Self.runBuild(root: root, logURL: logURL, adapter: scenario.adapter, executable: "clang++", arguments: ["-g", "-O0", sourceURL.path, "-o", builtProgramURL.path])
			programURL = builtProgramURL
		case .rust:
			try Self.runBuild(root: root, logURL: logURL, adapter: scenario.adapter, executable: "rustc", arguments: ["-C", "debuginfo=2", sourceURL.path, "-o", builtProgramURL.path])
			programURL = builtProgramURL
		}
	}

	private static func runBuild(root: URL, logURL: URL, adapter: DAPReferenceAdapter, executable: String, arguments: [String]) throws {
		let process = Process()
		let output = Pipe()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
		process.arguments = [executable] + arguments
		process.currentDirectoryURL = root
		process.standardOutput = output
		process.standardError = output
		try process.run()
		process.waitUntilExit()
		guard process.terminationStatus == 0 else {
			let text = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
			throw DAPReferenceAdapterError(adapter: adapter, step: "build", logURL: logURL, underlying: text)
		}
	}
}

private final class DAPReferenceLog: @unchecked Sendable {
	private let handle: FileHandle
	private let lock = NSLock()

	init(fileURL: URL) throws {
		handle = try FileHandle(forWritingTo: fileURL)
	}

	func append(_ prefix: String, data: Data) {
		lock.lock()
		defer {
			lock.unlock()
		}
		try? handle.write(contentsOf: Data("[\(prefix)] ".utf8))
		try? handle.write(contentsOf: data)
	}

	func append(_ text: String) {
		append("event", data: Data(text.utf8))
	}
}

private final class DAPReferenceLoggingTransport: DAPClientTransport, @unchecked Sendable {
	private let transport: DAPProcessTransport
	private let log: DAPReferenceLog

	init(transport: DAPProcessTransport, log: DAPReferenceLog) {
		self.transport = transport
		self.log = log
	}

	func write(_ data: Data) throws {
		log.append("stdin", data: data)
		try transport.write(data)
	}
}

private struct DAPReferenceAdapterError: Error, CustomStringConvertible {
	let adapter: DAPReferenceAdapter
	let step: String
	let logURL: URL?
	let underlying: String

	var description: String {
		let log = logURL.map { " Log: \($0.path)" } ?? ""
		return "DAP reference adapter \(adapter.rawValue) failed at \(step): \(underlying).\(log)"
	}
}
