import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func cFamilyAndSystemsLanguagesLaunchDeclaredServerOrReportUnavailable() async throws {
	let fixture = try LanguageServerMatrixFixture()
	defer { fixture.cleanup() }
	let supportedIDs = ["c", "cpp", "csharp", "go", "rust", "zig"]
	for languageID in supportedIDs {
		let language = try #require(BundledLanguageInventory.languages.first { $0.languageID == languageID })
		let declaredServer = try #require(language.server)
		let sourceURL = try fixture.sourceURL(for: language)
		let executable = try fixture.executable(named: declaredServer.executableProbe)
		let config = LSPServerConfig(languageId: languageID, command: executable.path, args: declaredServer.args, rootPatterns: [".git"])
		let launches = MatrixLaunchRecorder()
		let manager = LSPManager(registry: LSPServerRegistry(configs: [config]), clientFactory: launches.factory)

		let client = try await manager.ensureClient(for: sourceURL)
		#expect(launches.configs == [config])
		let key = try #require(await manager.sessionKey(for: sourceURL))
		#expect(await manager.status(of: key) == .starting)
		client.terminate()
	}

	let nix = try #require(BundledLanguageInventory.languages.first { $0.languageID == "nix" })
	let nixURL = try fixture.sourceURL(for: nix)
	let manager = LSPManager(registry: LSPServerRegistry(configs: []))
	#expect(await manager.unsupportedLanguage(for: nixURL) == LSPServerRegistry.UnsupportedLanguage(languageID: "nix", reason: .noBundledServer))
	do {
		_ = try await manager.ensureClient(for: nixURL)
		Issue.record("expected declared unavailable condition")
	} catch let error as LSPManagerError {
		#expect(error == .unsupportedLanguage(LSPServerRegistry.UnsupportedLanguage(languageID: "nix", reason: .noBundledServer)))
	}
}

@Test func cFamilyAndSystemsLanguagesRunOpenDiagnosticsCompletionDefinitionAndRenameProtocolScenarios() async throws {
	for languageID in ["c", "cpp", "csharp", "go", "rust", "zig"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.languageID == languageID })
		try await runProtocolScenario(language: language)
	}
}

@Test func webLanguageGrammarsLaunchDeclaredServerOrReportUnavailable() async throws {
	let fixture = try LanguageServerMatrixFixture()
	defer { fixture.cleanup() }
	let languageIDs = ["javascript", "typescript", "tsx", "json", "css", "scss", "html", "vue", "svelte", "graphql", "markdown"]
	for languageID in languageIDs {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == languageID })
		let sourceURL = try fixture.sourceURL(for: language)
		switch language.support {
		case .supported:
			let declaredServer = try #require(language.server)
			let executable = try fixture.executable(named: declaredServer.executableProbe)
			let config = LSPServerConfig(languageId: language.languageID, command: executable.path, args: declaredServer.args, rootPatterns: [".git"])
			let launches = MatrixLaunchRecorder()
			let manager = LSPManager(registry: LSPServerRegistry(configs: [config]), clientFactory: launches.factory)
			_ = try await manager.ensureClient(for: sourceURL)
			#expect(launches.configs == [config])
		case let .unsupported(reason):
			let manager = LSPManager(registry: LSPServerRegistry(configs: []))
			#expect(await manager.unsupportedLanguage(for: sourceURL) == LSPServerRegistry.UnsupportedLanguage(languageID: language.languageID, reason: reason))
		}
	}
}

@Test func webLanguageGrammarsRunCommonProtocolScenarioWithMixedLanguageFixture() async throws {
	let languageIDs = ["javascript", "typescript", "tsx", "json", "css", "scss", "html", "vue", "svelte", "graphql", "markdown"]
	for languageID in languageIDs {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == languageID })
		try await runProtocolScenario(language: language)
	}
	let tsx = try #require(BundledLanguageInventory.languages.first { $0.grammarID == "tsx" })
	try await runProtocolScenario(
		language: tsx,
		content: "import { gql } from \"graphql-tag\";\nconst query = gql`query Viewer { viewer { id } }`;\nconst View = <main>{query}</main>;\n"
	)
}

@Test func scriptingAndAutomationGrammarsLaunchDeclaredServerOrReportUnavailable() async throws {
	let fixture = try LanguageServerMatrixFixture()
	defer { fixture.cleanup() }
	for grammarID in ["bash", "python", "ruby", "lua", "php", "sql", "dockerfile", "terraform"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		let sourceURL = try fixture.sourceURL(for: language)
		switch language.support {
		case .supported:
			let declaredServer = try #require(language.server)
			let executable = try fixture.executable(named: declaredServer.executableProbe)
			let config = LSPServerConfig(languageId: language.languageID, command: executable.path, args: declaredServer.args, rootPatterns: [".git"])
			let launches = MatrixLaunchRecorder()
			let manager = LSPManager(registry: LSPServerRegistry(configs: [config]), clientFactory: launches.factory)
			_ = try await manager.ensureClient(for: sourceURL)
			#expect(launches.configs == [config])
		case let .unsupported(reason):
			let manager = LSPManager(registry: LSPServerRegistry(configs: []))
			#expect(await manager.unsupportedLanguage(for: sourceURL) == LSPServerRegistry.UnsupportedLanguage(languageID: language.languageID, reason: reason))
		}
	}
}

@Test func scriptingAndAutomationGrammarsRunCommonProtocolScenario() async throws {
	for grammarID in ["bash", "python", "ruby", "lua", "php", "sql", "dockerfile", "terraform"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		try await runProtocolScenario(language: language)
	}
}

@Test func jvmAndFunctionalGrammarsLaunchDeclaredServerOrReportUnavailable() async throws {
	let fixture = try LanguageServerMatrixFixture()
	defer { fixture.cleanup() }
	for grammarID in ["java", "kotlin", "haskell", "ocaml", "elixir", "dart"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		let sourceURL = try fixture.sourceURL(for: language)
		switch language.support {
		case .supported:
			let declaredServer = try #require(language.server)
			let executable = try fixture.executable(named: declaredServer.executableProbe)
			let config = LSPServerConfig(languageId: language.languageID, command: executable.path, args: declaredServer.args, rootPatterns: [".git"])
			let launches = MatrixLaunchRecorder()
			let manager = LSPManager(registry: LSPServerRegistry(configs: [config]), clientFactory: launches.factory)
			_ = try await manager.ensureClient(for: sourceURL)
			#expect(launches.configs == [config])
		case let .unsupported(reason):
			let manager = LSPManager(registry: LSPServerRegistry(configs: []))
			#expect(await manager.unsupportedLanguage(for: sourceURL) == LSPServerRegistry.UnsupportedLanguage(languageID: language.languageID, reason: reason))
		}
	}
}

@Test func jvmAndFunctionalGrammarsRunCommonProtocolScenariosAndReportToolRemediation() async throws {
	for grammarID in ["java", "kotlin", "haskell", "ocaml", "elixir", "dart"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		try await runProtocolScenario(language: language)
		guard case .supported = language.support, let server = language.server else {
			continue
		}
		#expect(throws: LSPExecutableDetectionError.missingExecutable(server.executableProbe)) {
			try LSPExecutableDetector.detect(
				command: server.command,
				probe: LSPExecutableProbe(executable: server.executableProbe, approvedPlatformLocations: []),
				environment: ["PATH": "/nonexistent"]
			)
		}
		#expect(!server.installHint.isEmpty)
	}
}

@Test func unsupportedScientificAndDocumentGrammarsReportCodableUnavailableCapabilityState() throws {
	for grammarID in ["julia", "r", "latex", "proto"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		#expect(language.lspCapabilityState == .unavailable)
		#expect(try JSONDecoder().decode(BundledLanguageLSPCapabilityState.self, from: JSONEncoder().encode(language.lspCapabilityState)) == .unavailable)
	}
}

@Test func yamlAndTomlDeclareLSPCapabilityState() throws {
	for grammarID in ["yaml", "toml"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		#expect(language.lspCapabilityState == .declaredServer)
		#expect(try JSONDecoder().decode(BundledLanguageLSPCapabilityState.self, from: JSONEncoder().encode(language.lspCapabilityState)) == .declaredServer)
	}
}

@Test func scientificAndDocumentGrammarsRunCommonProtocolScenarios() async throws {
	for grammarID in ["julia", "r", "latex", "yaml", "toml", "proto"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		try await runProtocolScenario(language: language)
	}
}

@Test func swiftAndRemainingGrammarRowsRunAutomatedProtocolScenarios() async throws {
	for grammarID in ["swift", "markdown-inline"] {
		let language = try #require(BundledLanguageInventory.languages.first { $0.grammarID == grammarID })
		try await runProtocolScenario(language: language)
	}
}

@Test func everyBundledLanguageInventoryRowHasAnAutomatedScenario() {
	let covered = Set([
		"bash", "c", "cpp", "csharp", "css", "dart", "dockerfile", "elixir", "go", "graphql", "haskell", "html", "java", "javascript", "julia", "json", "kotlin", "latex", "lua", "markdown", "markdown-inline", "nix", "ocaml", "php", "proto", "python", "r", "ruby", "rust", "scss", "sql", "svelte", "swift", "terraform", "toml", "tsx", "typescript", "vue", "yaml", "zig",
	])
	#expect(Set(BundledLanguageInventory.languages.map(\.grammarID)) == covered)
}

@Test func canonicalInventoryLSPProtocolMatrix() async throws {
	let requested = requestedMatrixGrammarIDs()
	if let requested {
		#expect(requested.isSubset(of: Set(BundledLanguageInventory.languages.map(\.grammarID))))
	}
	let fixture = try LanguageServerMatrixFixture()
	defer { fixture.cleanup() }
	for language in BundledLanguageInventory.languages where requested?.contains(language.grammarID) ?? true {
		try await runCanonicalScenario(language: language, fixture: fixture)
	}
}

private func requestedMatrixGrammarIDs(environment: [String: String] = ProcessInfo.processInfo.environment) -> Set<String>? {
	guard let raw = environment["ITSY_LSP_MATRIX_LANGUAGES"], !raw.isEmpty else {
		return nil
	}
	return Set(raw.split(separator: ",").map { String($0) })
}

private func runCanonicalScenario(language: BundledLanguage, fixture: LanguageServerMatrixFixture) async throws {
	if let server = language.server {
		let sourceURL = try fixture.sourceURL(for: language)
		let executable = try fixture.executable(named: server.executableProbe)
		let config = LSPServerConfig(languageId: language.languageID, command: executable.path, args: server.args, rootPatterns: [".git"])
		let launches = MatrixLaunchRecorder()
		let manager = LSPManager(registry: LSPServerRegistry(configs: [config]), clientFactory: launches.factory)
		_ = try await manager.ensureClient(for: sourceURL)
		#expect(launches.configs == [config])
	} else if !language.fileExtensions.isEmpty {
		let sourceURL = try fixture.sourceURL(for: language)
		let manager = LSPManager(registry: LSPServerRegistry(configs: []))
		#expect(await manager.unsupportedLanguage(for: sourceURL) != nil)
	}
	try await runProtocolScenario(language: language)
}

private func runProtocolScenario(language: BundledLanguage, content: String? = nil) async throws {
	let transport = MatrixLSPTransport()
	let session = LSPClientSession(transport: transport)
	let uri = "file:///tmp/itsy-lsp-matrix/fixture.\(language.fileExtensions.first ?? "txt")"
	let initialize = Task {
		try await session.initialize(LSPInitializeParams(processId: nil, rootUri: "file:///tmp/itsy-lsp-matrix"))
	}
	try await transport.wait(for: 1)
	let initializeRequest = try transport.message(at: 0).request
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: initializeRequest.id, result: .object(["capabilities": .object([:])])))))
	_ = try await initialize.value

	let open = LSPDidOpenTextDocumentParams(textDocument: LSPTextDocumentItem(uri: uri, languageId: language.languageID, version: 1, text: content ?? language.fixture))
	try await session.sendNotification(method: LSPMethod.textDocumentDidOpen, params: try LSPAny(encoding: open))
	try await transport.wait(for: 3)
	let openNotification = try transport.message(at: 2).notification
	#expect(openNotification.method == LSPMethod.textDocumentDidOpen)
	let decodedOpen = try decode(LSPDidOpenTextDocumentParams.self, from: openNotification.params)
	#expect(decodedOpen.textDocument.languageId == language.languageID)

	let diagnostics = LSPPublishDiagnosticsParams(uri: uri, diagnostics: [])
	let events = try await session.receive(LSPMessageFramer.frame(message: .notification(JSONRPCNotificationMessage(
		method: LSPMethod.textDocumentPublishDiagnostics,
		params: try LSPAny(encoding: diagnostics)
	))))
	#expect(events == [.notification(JSONRPCNotificationMessage(method: LSPMethod.textDocumentPublishDiagnostics, params: try LSPAny(encoding: diagnostics)))])

	let position = LSPPosition(line: 0, character: 0)
	let completion = Task {
		try await session.sendRequest(method: LSPMethod.textDocumentCompletion, params: try LSPAny(encoding: LSPCompletionParams(
			textDocument: LSPTextDocumentIdentifier(uri: uri),
			position: position
		)))
	}
	let completionRequest = try await nextRequest(from: transport, after: 3)
	#expect(completionRequest.method == LSPMethod.textDocumentCompletion)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: completionRequest.id, result: .array([])))))
	_ = try await completion.value

	let definition = Task { try await session.definition(uri: uri, position: position) }
	let definitionRequest = try await nextRequest(from: transport, after: 4)
	#expect(definitionRequest.method == LSPMethod.textDocumentDefinition)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: definitionRequest.id, result: .null))))
	#expect(try await definition.value == .none)

	let rename = Task { try await session.rename(uri: uri, position: position, newName: "renamed") }
	let renameRequest = try await nextRequest(from: transport, after: 5)
	#expect(renameRequest.method == LSPMethod.textDocumentRename)
	_ = try await session.receive(LSPMessageFramer.frame(message: .response(JSONRPCResponseMessage(id: renameRequest.id, result: .null))))
	#expect(try await rename.value == nil)
}

private func nextRequest(from transport: MatrixLSPTransport, after count: Int) async throws -> JSONRPCRequestMessage {
	try await transport.wait(for: count + 1)
	return try transport.message(at: count).request
}

private func decode<Value: Decodable>(_ type: Value.Type, from value: LSPAny?) throws -> Value {
	try JSONDecoder().decode(type, from: JSONEncoder().encode(value ?? .null))
}

private final class MatrixLaunchRecorder: @unchecked Sendable {
	private let lock = NSLock()
	private var launched: [LSPServerConfig] = []

	var configs: [LSPServerConfig] {
		lock.lock()
		defer { lock.unlock() }
		return launched
	}

	var factory: LSPManager.ClientFactory {
		{ [weak self] config, _ in
			self?.lock.lock()
			self?.launched.append(config)
			self?.lock.unlock()
			return LSPProcessClient(executableURL: URL(fileURLWithPath: "/usr/bin/true"))
		}
	}
}

private final class MatrixLSPTransport: LSPClientTransport, @unchecked Sendable {
	private let lock = NSLock()
	private var writes: [Data] = []

	func write(_ data: Data) throws {
		lock.lock()
		writes.append(data)
		lock.unlock()
	}

	func wait(for count: Int) async throws {
		for _ in 0 ..< 100 {
			if hasWriteCount(count) {
				return
			}
			try await Task.sleep(nanoseconds: 1_000_000)
		}
		throw MatrixProtocolError.timeout(count)
	}

	private func hasWriteCount(_ count: Int) -> Bool {
		lock.lock()
		defer { lock.unlock() }
		return writes.count >= count
	}

	func message(at index: Int) throws -> JSONRPCMessage {
		lock.lock()
		let data = writes.indices.contains(index) ? writes[index] : nil
		lock.unlock()
		guard let data else {
			throw MatrixProtocolError.missingWrite(index)
		}
		var framer = LSPMessageFramer()
		let payloads = try framer.append(data)
		guard let payload = payloads.first else {
			throw MatrixProtocolError.invalidFrame(index)
		}
		return try JSONDecoder().decode(JSONRPCMessage.self, from: payload)
	}
}

private extension JSONRPCMessage {
	var request: JSONRPCRequestMessage {
		get throws {
			guard case let .request(request) = self else {
				throw MatrixProtocolError.expectedRequest
			}
			return request
		}
	}

	var notification: JSONRPCNotificationMessage {
		get throws {
			guard case let .notification(notification) = self else {
				throw MatrixProtocolError.expectedNotification
			}
			return notification
		}
	}
}

private enum MatrixProtocolError: Error {
	case timeout(Int)
	case missingWrite(Int)
	case invalidFrame(Int)
	case expectedRequest
	case expectedNotification
}

private final class LanguageServerMatrixFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lsp-matrix-\(UUID().uuidString)", isDirectory: true)

	init() throws {
		try FileManager.default.createDirectory(at: root.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
	}

	func sourceURL(for language: BundledLanguage) throws -> URL {
		let ext = try #require(language.fileExtensions.first)
		let url = root.appendingPathComponent("fixture.\(ext)")
		try language.fixture.write(to: url, atomically: true, encoding: .utf8)
		return url
	}

	func executable(named name: String) throws -> URL {
		let url = root.appendingPathComponent("bin").appendingPathComponent(name)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		if !FileManager.default.fileExists(atPath: url.path) {
			try "#!/bin/sh\nexit 0\n".write(to: url, atomically: true, encoding: .utf8)
			try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		}
		return url
	}

	func cleanup() {
		try? FileManager.default.removeItem(at: root)
	}
}
