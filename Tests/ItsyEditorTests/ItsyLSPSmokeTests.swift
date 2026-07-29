import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Suite(.enabled(if: SmokeTooling.sourceKitLSPURL != nil), .timeLimit(.minutes(1)))
struct ItsyLSPSmokeTests {
	@Test func sourceKitPackagePublishesDiagnosticsCompletionDefinitionRenameAndFormatting() async throws {
		let sourceKitLSPURL = try #require(SmokeTooling.sourceKitLSPURL)
		let fixture = try SourceKitSmokeFixture()
		defer {
			fixture.cleanup()
		}

		let client = LSPProcessClient(executableURL: sourceKitLSPURL, currentDirectoryURL: fixture.rootURL)
		try client.start()
		let eventPump = LSPServerEventPump(client: client, workspaceRoot: fixture.rootURL)
		let eventTask = await eventPump.start()
		defer {
			eventTask.cancel()
			client.terminate()
		}

		let initializeResult = try LSPInitializeResult(result: try await client.initialize(try LSPInitializeParams.itsy(workspaceRoot: fixture.rootURL)))
		#expect(await client.session.state == .running)
		#expect(initializeResult.capabilities.completionProvider != nil)

		let sync = LSPDocumentSyncCoordinator(sink: ProcessNotificationSink(client: client), debounceMillis: 0)
		try await sync.didOpen(url: fixture.fileURL, languageID: "swift", content: fixture.source)
		let sent = await sync.recordedMessages
		#expect(sent.contains { $0.method == LSPMethod.textDocumentDidOpen && $0.version == 1 })

		let diagnostics = try await eventPump.waitForDiagnostics(uri: fixture.fileURI, requiringNonEmpty: true, timeoutSeconds: 5)
		#expect(diagnostics.diagnostics.isEmpty == false)

		let completionResponse = try await client.sendRequest(
			method: LSPMethod.textDocumentCompletion,
			params: try LSPAny(encoding: LSPCompletionParams(
				textDocument: LSPTextDocumentIdentifier(uri: fixture.fileURI),
				position: fixture.completionPosition,
				context: LSPCompletionContext(triggerKind: .invoked)
			))
		)
		let completion = try LSPCompletionResult(result: completionResponse.result)
		#expect(completion.items.isEmpty == false)

		let definitionResponse = try await client.sendRequest(
			method: LSPMethod.textDocumentDefinition,
			params: try LSPAny(encoding: LSPTextDocumentPositionParams(
				textDocument: LSPTextDocumentIdentifier(uri: fixture.fileURI),
				position: fixture.definitionPosition
			))
		)
		let definitionData = try JSONEncoder().encode(definitionResponse.result ?? .null)
		let definition = try LSPDefinitionResult(decoding: definitionData)
		#expect(definition.locations.contains { location in
			fixture.matches(location: location) && location.range.start.line == 0
		})

		let rename = try await client.rename(uri: fixture.fileURI, position: fixture.renamePosition, newName: "renamedTargetFunction")
		let renameEdits = rename?.changes?[fixture.fileURI] ?? rename?.documentChanges?.first {
			$0.textDocument.uri == fixture.fileURI
		}?.edits
		#expect(renameEdits?.contains { $0.newText == "renamedTargetFunction" } == true)

		_ = try await client.formatDocument(
			uri: fixture.fileURI,
			options: LSPFormattingOptions(tabSize: 4, insertSpaces: true)
		)

		try? await client.shutdown()
	}
}

private struct ProcessNotificationSink: LSPNotificationSink {
	let client: LSPProcessClient

	func send(method: String, params: LSPAny) async throws {
		try await client.sendNotification(method: method, params: params)
	}
}

private struct SourceKitSmokeFixture {
	let rootURL: URL
	let fileURL: URL
	let source: String

	var fileURI: String {
		fileURL.standardizedFileURL.absoluteString
	}

	var completionPosition: LSPPosition {
		get throws {
			try position(after: "let completionProbe = smoke")
		}
	}

	var definitionPosition: LSPPosition {
		get throws {
			try position(inside: "smokeTargetFunction(41)")
		}
	}

	var renamePosition: LSPPosition {
		get throws {
			try position(inside: "smokeTargetFunction(41)")
		}
	}

	init(fileManager: FileManager = .default) throws {
		rootURL = fileManager.temporaryDirectory.appendingPathComponent("itsy-lsp-smoke-\(UUID().uuidString)", isDirectory: true)
		let sourceDir = rootURL.appendingPathComponent("Sources", isDirectory: true).appendingPathComponent("ItsyLSPSmoke", isDirectory: true)
		fileURL = sourceDir.appendingPathComponent("main.swift")
		source = """
		func smokeTargetFunction(_ value: Int) -> Int {
			value + 1
		}

		let smokeDefinitionProbe = smokeTargetFunction(41)
		let completionProbe = smoke
		let diagnosticProbe: Int = "not an int"
		"""
		try fileManager.createDirectory(at: sourceDir, withIntermediateDirectories: true)
		try """
		// swift-tools-version: 5.9
		import PackageDescription
		let package = Package(name: "ItsyLSPSmoke", targets: [.executableTarget(name: "ItsyLSPSmoke")])
		""".write(to: rootURL.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
		try source.write(to: fileURL, atomically: true, encoding: .utf8)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: rootURL)
	}

	private func position(after needle: String) throws -> LSPPosition {
		guard let range = source.range(of: needle) else {
			throw LSPSmokeError.missingNeedle(needle)
		}
		let offset = source[..<range.upperBound].utf8.count
		return LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: source)
	}

	private func position(inside needle: String) throws -> LSPPosition {
		guard let range = source.range(of: needle) else {
			throw LSPSmokeError.missingNeedle(needle)
		}
		let offset = source[..<range.lowerBound].utf8.count + 2
		return LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: source)
	}

	func matches(location: LSPLocation) -> Bool {
		guard let url = URL(string: location.uri) else {
			return false
		}
		return url.resolvingSymlinksInPath().path == fileURL.resolvingSymlinksInPath().path
	}
}

private actor LSPServerEventPump {
	private let client: LSPProcessClient
	private let workspaceFolder: LSPAny
	private var notifications: [JSONRPCNotificationMessage] = []
	private var failures: [String] = []

	init(client: LSPProcessClient, workspaceRoot: URL) {
		self.client = client
		workspaceFolder = .object([
			"uri": .string(workspaceRoot.standardizedFileURL.absoluteString),
			"name": .string(workspaceRoot.lastPathComponent),
		])
	}

	func start() -> Task<Void, Never> {
		Task { [client] in
			for await event in client.events {
				await self.handle(event)
			}
		}
	}

	func waitForDiagnostics(uri: String, requiringNonEmpty: Bool, timeoutSeconds: TimeInterval) async throws -> LSPPublishDiagnosticsParams {
		let deadline = Date(timeIntervalSinceNow: timeoutSeconds)
		while Date() < deadline {
			if let failure = failures.first {
				throw LSPSmokeError.serverFailure(failure)
			}
			for notification in notifications where notification.method == LSPMethod.textDocumentPublishDiagnostics {
				guard let params = try? decode(LSPPublishDiagnosticsParams.self, from: notification.params), params.uri == uri else {
					continue
				}
				if !requiringNonEmpty || !params.diagnostics.isEmpty {
					return params
				}
			}
			try await Task.sleep(nanoseconds: 10_000_000)
		}
		throw LSPSmokeError.timeout("textDocument/publishDiagnostics")
	}

	private func handle(_ event: LSPProcessClientEvent) async {
		switch event {
		case let .server(.request(request)):
			do {
				try await client.session.respond(to: request.id, result: responseResult(for: request))
			} catch {
				failures.append(String(describing: error))
			}
		case let .server(.notification(notification)):
			notifications.append(notification)
		case .stderr:
			break
		case let .terminated(status):
			if status != 0 {
				failures.append("sourcekit-lsp terminated with status \(status)")
			}
		case let .failure(message):
			failures.append(message)
		}
	}

	private func responseResult(for request: JSONRPCRequestMessage) -> LSPAny {
		switch request.method {
		case "workspace/configuration":
			return configurationResponse(for: request.params)
		case "workspace/workspaceFolders":
			return .array([workspaceFolder])
		default:
			return .null
		}
	}

	private func configurationResponse(for params: LSPAny?) -> LSPAny {
		guard case let .object(object) = params, case let .array(items)? = object["items"] else {
			return .array([])
		}
		return .array(items.map { _ in .object([:]) })
	}

	private func decode<Value: Decodable>(_ type: Value.Type, from value: LSPAny?) throws -> Value {
		let data = try JSONEncoder().encode(value ?? .null)
		return try JSONDecoder().decode(type, from: data)
	}
}

private enum SmokeTooling {
	static let sourceKitLSPURL = executable(named: "sourcekit-lsp")

	private static func executable(named name: String) -> URL? {
		for directory in (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":") {
			let url = URL(fileURLWithPath: String(directory)).appendingPathComponent(name)
			if FileManager.default.isExecutableFile(atPath: url.path) {
				return url
			}
		}
		return nil
	}
}

private enum LSPSmokeError: Error, Equatable {
	case missingNeedle(String)
	case serverFailure(String)
	case timeout(String)
}
