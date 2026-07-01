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
	#expect(await manager.status(of: key) == .failed)
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

private final class SpawnCounter: @unchecked Sendable {
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
			return LSPProcessClient(executableURL: URL(fileURLWithPath: "/usr/bin/true"))
		}
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

	private func executable(_ relativePath: String) throws -> URL {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try "#!/bin/sh\nexit 0\n".write(to: url, atomically: true, encoding: .utf8)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		return url
	}
}
