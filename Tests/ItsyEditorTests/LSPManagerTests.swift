import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func lspManagerDerivesSessionKeyFromExtensionAndRootPattern() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let manager = LSPManager(registry: try fixture.swiftRegistry())
	let key = await manager.sessionKey(for: fixture.root.appendingPathComponent("ws/Sources/App.swift"))
	#expect(key?.languageID == "swift")
	#expect(key?.workspaceRoot.standardizedFileURL.path == fixture.root.appendingPathComponent("ws").standardizedFileURL.path)
}

@Test func lspManagerReturnsNilSessionKeyWhenNoConfigOrRoot() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("loose.swift", "")
	let manager = LSPManager()
	let none = await manager.sessionKey(for: fixture.root.appendingPathComponent("loose.swift"))
	#expect(none == nil)
}

@Test func lspManagerReplacesConfigurationWithoutRestartingAndResetsSessionState() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let manager = LSPManager(registry: LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "typescript", command: "typescript-language-server", rootPatterns: ["package.json"]),
	]))
	#expect(await manager.sessionKey(for: url) == nil)

	await manager.replaceRegistry(try fixture.swiftRegistry())
	let key = try #require(await manager.sessionKey(for: url))
	await manager.markFailed(key)
	#expect(await manager.status(of: key) == .failed)

	await manager.replaceRegistry(try fixture.swiftRegistry())
	#expect(await manager.sessionKey(for: url) == key)
	#expect(await manager.status(of: key) == .idle)
}

@Test func lspManagerLazilySpawnsAndReusesClientPerKey() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/A.swift", "")
	try fixture.write("ws/Sources/B.swift", "")
	let counter = SpawnCounter()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: counter.makeFactory())
	let firstURL = fixture.root.appendingPathComponent("ws/Sources/A.swift")
	let secondURL = fixture.root.appendingPathComponent("ws/Sources/B.swift")
	_ = try await manager.ensureClient(for: firstURL)
	_ = try await manager.ensureClient(for: secondURL)
	#expect(counter.count == 1)
}

@Test func lspManagerTransitionsThroughStartFailureStopAndShutdownWithFakeTransport() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let factory = LifecycleClientFactory()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: factory.makeFactory())
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let key = try #require(await manager.sessionKey(for: url))

	let starting = try await manager.ensureClient(for: url)
	#expect(await manager.status(of: key) == .starting)
	#expect(await manager.existingClient(for: key) === starting)
	await manager.markRunning(key)
	#expect(await manager.status(of: key) == .running)
	await manager.markFailed(key)
	#expect(await manager.status(of: key) == .failed)
	#expect(await manager.existingClient(for: key) == nil)

	_ = try await manager.ensureClient(for: url)
	await manager.stopSession(key)
	#expect(await manager.status(of: key) == .exited)
	#expect(await manager.existingClient(for: key) == nil)

	_ = try await manager.ensureClient(for: url)
	await manager.shutdownAll()
	#expect(await manager.status(of: key) == .exited)
	#expect(await manager.existingClient(for: key) == nil)
	#expect(factory.count == 3)
}

@Test func lspManagerRestartReplacesTheClientAndRejectsStaleFailure() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let factory = LifecycleClientFactory()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: factory.makeFactory())
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let key = try #require(await manager.sessionKey(for: url))

	let first = try await manager.ensureClient(for: url)
	await manager.registerSynchronizedDocument(url, for: key)
	await manager.restartSession(key)
	#expect(await manager.status(of: key) == .idle)
	#expect(await manager.existingClient(for: key) == nil)

	let replacement = try await manager.ensureClient(for: url)
	await manager.markFailed(key, matching: first)
	#expect(await manager.existingClient(for: key) === replacement)
	#expect(await manager.status(of: key) == .starting)
	#expect(factory.count == 2)
}

@Test func lspManagerClosesDocumentsAndStopsOnlyAfterTheLastDocument() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/A.swift", "")
	try fixture.write("ws/Sources/B.swift", "")
	let factory = LifecycleClientFactory()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: factory.makeFactory())
	let first = fixture.root.appendingPathComponent("ws/Sources/A.swift")
	let second = fixture.root.appendingPathComponent("ws/Sources/B.swift")
	let key = try #require(await manager.sessionKey(for: first))
	_ = try await manager.ensureClient(for: first)
	let sink = DocumentCloseSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	try await coordinator.didOpen(url: first, languageID: key.languageID, content: "")
	try await coordinator.didOpen(url: second, languageID: key.languageID, content: "")
	await manager.registerSynchronizedDocument(first, for: key)
	await manager.registerSynchronizedDocument(second, for: key)

	#expect(await manager.closeSynchronizedDocument(first, for: key, using: coordinator) == false)
	#expect(await manager.status(of: key) == .starting)
	#expect(await manager.existingClient(for: key) != nil)
	#expect(await manager.closeSynchronizedDocument(second, for: key, using: coordinator))
	#expect(await manager.status(of: key) == .exited)
	#expect(await manager.existingClient(for: key) == nil)
	#expect(await sink.methods == [LSPMethod.textDocumentDidOpen, LSPMethod.textDocumentDidOpen, LSPMethod.textDocumentDidClose, LSPMethod.textDocumentDidClose])
}

@Test func lspManagerCanCloseTheLastDocumentBeforeTheCallerStopsTheSession() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let factory = LifecycleClientFactory()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: factory.makeFactory())
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let key = try #require(await manager.sessionKey(for: url))
	_ = try await manager.ensureClient(for: url)
	let sink = DocumentCloseSink()
	let coordinator = LSPDocumentSyncCoordinator(sink: sink, debounceMillis: 0)
	try await coordinator.didOpen(url: url, languageID: key.languageID, content: "")
	await manager.registerSynchronizedDocument(url, for: key)

	#expect(await manager.closeSynchronizedDocument(
		url,
		for: key,
		using: coordinator,
		stoppingSessionOnLastDocument: false
	))
	#expect(await manager.status(of: key) == .starting)
	#expect(await manager.existingClient(for: key) != nil)
	await manager.stopSession(key)
	#expect(await manager.status(of: key) == .exited)
}

@Test func lspManagerUsesWorkspaceTOMLOverride() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let override = try fixture.executable("override/sourcekit-lsp")
	try fixture.write("ws/.itsy/lsp.toml", """
	[swift]
	command = "\(override.path)"
	root_patterns = ["Package.swift", ".git"]
	""")
	let counter = SpawnCounter()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: counter.makeFactory())
	_ = try await manager.ensureClient(for: fixture.root.appendingPathComponent("ws/Sources/App.swift"))
	#expect(counter.configs.first?.command == override.path)
}

@Test func lspManagerCapsSpawnsWithinRetryWindow() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let counter = SpawnCounter()
	let manager = LSPManager(registry: try fixture.swiftRegistry(), clientFactory: counter.makeFactory())
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: fixture.root.appendingPathComponent("ws"))
	let now = Date()
	for offset in 0 ..< 3 {
		_ = try await manager.ensureClient(for: url, now: now.addingTimeInterval(Double(offset)))
		await manager.markFailed(key)
	}
	do {
		_ = try await manager.ensureClient(for: url, now: now.addingTimeInterval(4))
		Issue.record("expected retryLimitExceeded")
	} catch let error as LSPManagerError {
		#expect(error == .retryLimitExceeded)
	}
	#expect(await manager.status(of: key) == .disabled)
	do {
		_ = try await manager.ensureClient(for: url, now: now.addingTimeInterval(5))
		Issue.record("expected serverDisabled")
	} catch let error as LSPManagerError {
		#expect(error == .serverDisabled(key))
	}
	await manager.enableSession(key)
	_ = try await manager.ensureClient(for: url, now: now.addingTimeInterval(6))
	#expect(await manager.status(of: key) == .starting)
}

@Test func lspManagerAllowsRetryAfterWindowExpires() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/Package.swift", "")
	try fixture.write("ws/Sources/App.swift", "")
	let counter = SpawnCounter()
	let manager = LSPManager(
		registry: try fixture.swiftRegistry(),
		retryWindow: 30,
		maxSpawnsPerWindow: 2,
		clientFactory: counter.makeFactory()
	)
	let url = fixture.root.appendingPathComponent("ws/Sources/App.swift")
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: fixture.root.appendingPathComponent("ws"))
	let base = Date()
	_ = try await manager.ensureClient(for: url, now: base)
	await manager.markFailed(key)
	_ = try await manager.ensureClient(for: url, now: base.addingTimeInterval(5))
	await manager.markFailed(key)
	#expect(await manager.recentSpawnCount(for: key, now: base.addingTimeInterval(60)) == 0)
	_ = try await manager.ensureClient(for: url, now: base.addingTimeInterval(60))
	#expect(counter.count == 3)
}

@Test func lspManagerSurfacesMissingBinaryBeforeSpawn() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/package.json", "{}")
	try fixture.write("ws/src/app.ts", "")
	let registry = LSPServerRegistry(configs: [
		LSPServerConfig(
			languageId: "typescript",
			command: fixture.root.appendingPathComponent("missing-typescript-language-server").path,
			args: ["--stdio"],
			rootPatterns: ["package.json"]
		)
	])
	let counter = SpawnCounter()
	let manager = LSPManager(registry: registry, clientFactory: counter.makeFactory())
	let key = await manager.sessionKey(for: fixture.root.appendingPathComponent("ws/src/app.ts"))
	#expect(key?.languageID == "typescript")
	do {
		_ = try await manager.ensureClient(for: fixture.root.appendingPathComponent("ws/src/app.ts"))
		Issue.record("expected missingBinary")
	} catch let error as LSPManagerError {
		#expect(error == .missingBinary(LSPServerRegistry.MissingBinary(
			languageID: "typescript",
			command: fixture.root.appendingPathComponent("missing-typescript-language-server").path,
			hint: "install `\(fixture.root.appendingPathComponent("missing-typescript-language-server").path)` and ensure it is executable"
		)))
	}
	#expect(counter.count == 0)
}

@Test func lspManagerReportsMissingBinaryWithoutSpawning() async throws {
	let fixture = try TemporaryLSPManagerFixture()
	try fixture.write("ws/package.json", "{}")
	try fixture.write("ws/src/app.ts", "")
	let command = fixture.root.appendingPathComponent("missing-typescript-language-server").path
	let manager = LSPManager(registry: LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "typescript", command: command, rootPatterns: ["package.json"])
	]))
	let missing = await manager.missingBinary(for: fixture.root.appendingPathComponent("ws/src/app.ts"))
	#expect(missing == LSPServerRegistry.MissingBinary(
		languageID: "typescript",
		command: command,
		hint: "install `\(command)` and ensure it is executable"
	))
}

private final class SpawnCounter: @unchecked Sendable {
	private let lock = NSLock()
	private var value = 0
	private var seenConfigs: [LSPServerConfig] = []

	var count: Int {
		lock.lock()
		defer { lock.unlock() }
		return value
	}

	var configs: [LSPServerConfig] {
		lock.lock()
		defer { lock.unlock() }
		return seenConfigs
	}

	func makeFactory() -> LSPManager.ClientFactory {
		{ [self] config, _ in
			lock.lock()
			value += 1
			seenConfigs.append(config)
			lock.unlock()
			return LSPProcessClient(executableURL: URL(fileURLWithPath: "/usr/bin/true"))
		}
	}
}

private final class LifecycleClientFactory: @unchecked Sendable {
	private let lock = NSLock()
	private var value = 0

	var count: Int {
		lock.lock()
		defer { lock.unlock() }
		return value
	}

	func makeFactory() -> LSPManager.ClientFactory {
		{ [self] _, _ in
			lock.lock()
			value += 1
			lock.unlock()
			return LSPProcessClient(transport: LifecycleTransport())
		}
	}
}

private final class LifecycleTransport: LSPProcessClientTransport, @unchecked Sendable {
	let events: AsyncStream<LSPProcessTransportEvent>
	let executableURL = URL(fileURLWithPath: "/usr/bin/true")
	let arguments: [String] = []
	let processIdentifier: Int32? = nil
	let startDate: Date? = nil

	init() {
		var continuation: AsyncStream<LSPProcessTransportEvent>.Continuation?
		events = AsyncStream { continuation = $0 }
		continuation?.finish()
	}

	func write(_: Data) throws {}
	func start() throws {}
	func terminate() {}
}

private actor DocumentCloseSink: LSPNotificationSink {
	private(set) var methods: [String] = []

	func send(method: String, params _: LSPAny) async throws {
		methods.append(method)
	}
}

private final class TemporaryLSPManagerFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-lsp-manager-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ relativePath: String, _ contents: String) throws {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func swiftRegistry() throws -> LSPServerRegistry {
		let bin = try executable("bin/sourcekit-lsp")
		return LSPServerRegistry(configs: [
			LSPServerConfig(languageId: "swift", command: bin.path, rootPatterns: ["Package.swift", ".git"])
		])
	}

	func executable(_ relativePath: String) throws -> URL {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try "#!/bin/sh\nexit 0\n".write(to: url, atomically: true, encoding: .utf8)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		return url
	}
}
