import Foundation
import ItsyConfig
import ItsyLSP

public struct LSPSessionKey: Hashable, Sendable {
	public let languageID: String
	public let workspaceRoot: URL

	public init(languageID: String, workspaceRoot: URL) {
		self.languageID = languageID
		self.workspaceRoot = workspaceRoot.standardizedFileURL
	}
}

public enum LSPSessionStatus: Equatable, Sendable {
	case idle
	case starting
	case running
	case failed
	case exited
	case disabled
}

public enum LSPManagerError: Error, Equatable, Sendable {
	case noConfigForDocument
	case unsupportedLanguage(LSPServerRegistry.UnsupportedLanguage)
	case workspaceRootNotFound
	case missingBinary(LSPServerRegistry.MissingBinary)
	case retryLimitExceeded
	case serverDisabled(LSPSessionKey)
	case languageDisabled(String)
}

public actor LSPManager {
	public typealias ClientFactory = @Sendable (LSPServerConfig, URL) throws -> LSPProcessClient

	public static let defaultRetryWindow: TimeInterval = 60
	public static let defaultMaxSpawnsPerWindow = 3

	public let retryWindow: TimeInterval
	public let maxSpawnsPerWindow: Int

	private var registry: LSPServerRegistry
	private var lspSettings: ItsySettings.LSPSettings
	private let clientFactory: ClientFactory
	private var clients: [LSPSessionKey: LSPProcessClient] = [:]
	private var statuses: [LSPSessionKey: LSPSessionStatus] = [:]
	private var spawnTimestamps: [LSPSessionKey: [Date]] = [:]
	private var disabledKeys: Set<LSPSessionKey> = []
	private var synchronizedDocuments: [LSPSessionKey: Set<URL>] = [:]

	public init(
		registry: LSPServerRegistry = LSPServerRegistry(),
		lspSettings: ItsySettings.LSPSettings = .init(),
		retryWindow: TimeInterval = LSPManager.defaultRetryWindow,
		maxSpawnsPerWindow: Int = LSPManager.defaultMaxSpawnsPerWindow,
		clientFactory: @escaping ClientFactory = LSPManager.defaultClientFactory
	) {
		self.registry = registry
		self.lspSettings = lspSettings
		self.retryWindow = retryWindow
		self.maxSpawnsPerWindow = maxSpawnsPerWindow
		self.clientFactory = clientFactory
	}

	public static let defaultClientFactory: ClientFactory = { config, root in
		if requiresNodeRuntime(config), let runtime = nodeRuntime(for: config) {
			return LSPProcessClient(
				executableURL: runtime.executableURL,
				arguments: [config.command] + config.args,
				currentDirectoryURL: root,
				environment: NodeRuntimeDetector.launchEnvironment(for: runtime)
			)
		}
		let executableURL: URL
		let arguments: [String]
		if config.command.hasPrefix("/") {
			executableURL = URL(fileURLWithPath: config.command)
			arguments = config.args
		} else {
			executableURL = URL(fileURLWithPath: "/usr/bin/env")
			arguments = [config.command] + config.args
		}
		return LSPProcessClient(executableURL: executableURL, arguments: arguments, currentDirectoryURL: root)
	}

	public func sessionKey(for url: URL) -> LSPSessionKey? {
		guard mode(for: url) != .disabled else { return nil }
		guard let config = registry.config(for: url) else {
			return nil
		}
		guard let root = registry.discoverWorkspaceRoot(for: url) else {
			return nil
		}
		return LSPSessionKey(languageID: config.languageId, workspaceRoot: root)
	}

	public func status(of key: LSPSessionKey) -> LSPSessionStatus {
		statuses[key] ?? .idle
	}

	public func existingClient(for key: LSPSessionKey) -> LSPProcessClient? {
		clients[key]
	}

	public func missingBinary(for url: URL) -> LSPServerRegistry.MissingBinary? {
		let registry = effectiveRegistry(for: url)
		let mode = mode(for: url, registry: registry)
		if let config = registry.resolvedConfig(for: url, mode: mode), Self.requiresNodeRuntime(config), Self.nodeRuntime(for: config) == nil {
			return LSPServerRegistry.MissingBinary(
				languageID: config.languageId,
				command: "node",
				hint: Self.nodeRuntimeHint(for: config)
			)
		}
		return registry.missingBinary(for: url, mode: mode)
	}

	public func unsupportedLanguage(for url: URL) -> LSPServerRegistry.UnsupportedLanguage? {
		effectiveRegistry(for: url).unsupportedLanguage(for: url)
	}

	public func config(for url: URL) -> LSPServerConfig? {
		effectiveRegistry(for: url).config(for: url)
	}

	public func ensureClient(for url: URL, now: Date = .init()) throws -> LSPProcessClient {
		let effectiveRegistry = effectiveRegistry(for: url)
		let mode = mode(for: url, registry: effectiveRegistry)
		guard mode != .disabled else {
			throw LSPManagerError.languageDisabled(effectiveRegistry.languageID(for: url) ?? url.pathExtension)
		}
		guard let config = effectiveRegistry.resolvedConfig(for: url, mode: mode) else {
			if let missingBinary = effectiveRegistry.missingBinary(for: url, mode: mode) {
				throw LSPManagerError.missingBinary(missingBinary)
			}
			if let unsupportedLanguage = effectiveRegistry.unsupportedLanguage(for: url) {
				throw LSPManagerError.unsupportedLanguage(unsupportedLanguage)
			}
			throw LSPManagerError.noConfigForDocument
		}
		if Self.requiresNodeRuntime(config), Self.nodeRuntime(for: config) == nil {
			throw LSPManagerError.missingBinary(.init(
				languageID: config.languageId,
				command: "node",
				hint: Self.nodeRuntimeHint(for: config)
			))
		}
		guard let root = effectiveRegistry.discoverWorkspaceRoot(for: url) else {
			throw LSPManagerError.workspaceRootNotFound
		}
		let key = LSPSessionKey(languageID: config.languageId, workspaceRoot: root)
		if disabledKeys.contains(key) {
			throw LSPManagerError.serverDisabled(key)
		}
		if let existing = clients[key] {
			return existing
		}
		guard registerSpawnAttempt(for: key, now: now) else {
			disabledKeys.insert(key)
			statuses[key] = .disabled
			throw LSPManagerError.retryLimitExceeded
		}
		let client = try clientFactory(config, root)
		clients[key] = client
		statuses[key] = .starting
		return client
	}

	public func symbols(matching query: String, in url: URL) async throws -> [LSPWorkspaceSymbol] {
		let effectiveRegistry = effectiveRegistry(for: url)
		guard mode(for: url, registry: effectiveRegistry) != .disabled else {
			throw LSPManagerError.languageDisabled(effectiveRegistry.languageID(for: url) ?? url.pathExtension)
		}
		guard effectiveRegistry.config(for: url) != nil else {
			throw LSPManagerError.noConfigForDocument
		}
		guard effectiveRegistry.discoverWorkspaceRoot(for: url) != nil else {
			throw LSPManagerError.workspaceRootNotFound
		}
		let client = try ensureClient(for: url)
		return try await client.workspaceSymbol(query: query).workspaceSymbols
	}

	public func markRunning(_ key: LSPSessionKey) {
		statuses[key] = .running
	}

	public func markFailed(_ key: LSPSessionKey, matching client: LSPProcessClient? = nil) {
		guard client == nil || clients[key] === client else {
			return
		}
		statuses[key] = disabledKeys.contains(key) ? .disabled : .failed
		clients.removeValue(forKey: key)?.terminate()
		synchronizedDocuments[key] = nil
	}

	public func stopSession(_ key: LSPSessionKey) {
		clients.removeValue(forKey: key)?.terminate()
		statuses[key] = .exited
		synchronizedDocuments[key] = nil
	}

	public func restartSession(_ key: LSPSessionKey) {
		clients.removeValue(forKey: key)?.terminate()
		statuses[key] = .idle
		spawnTimestamps[key] = []
		disabledKeys.remove(key)
		synchronizedDocuments[key] = nil
	}

	public func registerSynchronizedDocument(_ url: URL, for key: LSPSessionKey) {
		synchronizedDocuments[key, default: []].insert(url.standardizedFileURL)
	}

	public func closeSynchronizedDocument(
		_ url: URL,
		for key: LSPSessionKey,
		using coordinator: LSPDocumentSyncCoordinator,
		stoppingSessionOnLastDocument: Bool = true
	) async -> Bool {
		let url = url.standardizedFileURL
		guard synchronizedDocuments[key]?.remove(url) != nil else {
			return false
		}
		try? await coordinator.didClose(url: url)
		if synchronizedDocuments[key]?.isEmpty == true {
			if stoppingSessionOnLastDocument {
				stopSession(key)
			}
			return true
		}
		return false
	}

	public func enableSession(_ key: LSPSessionKey) {
		disabledKeys.remove(key)
		spawnTimestamps[key] = []
		statuses[key] = .idle
	}

	public func replaceRegistry(_ registry: LSPServerRegistry) async {
		await shutdownAll()
		self.registry = registry
		statuses.removeAll()
		spawnTimestamps.removeAll()
		disabledKeys.removeAll()
		synchronizedDocuments.removeAll()
	}

	public func replaceLSPSettings(_ settings: ItsySettings.LSPSettings) async {
		guard lspSettings != settings else { return }
		await shutdownAll()
		lspSettings = settings
		statuses.removeAll()
		spawnTimestamps.removeAll()
		disabledKeys.removeAll()
		synchronizedDocuments.removeAll()
	}

	public func shutdownAll() async {
		let keys = Array(clients.keys)
		for key in keys {
			guard let client = clients[key] else {
				continue
			}
			statuses[key] = .exited
			client.terminate()
		}
		clients.removeAll()
		synchronizedDocuments.removeAll()
	}

	public func recentSpawnCount(for key: LSPSessionKey, now: Date = .init()) -> Int {
		let cutoff = now.addingTimeInterval(-retryWindow)
		return (spawnTimestamps[key] ?? []).filter { $0 > cutoff }.count
	}

	private func registerSpawnAttempt(for key: LSPSessionKey, now: Date) -> Bool {
		let cutoff = now.addingTimeInterval(-retryWindow)
		var recent = (spawnTimestamps[key] ?? []).filter { $0 > cutoff }
		guard recent.count < maxSpawnsPerWindow else {
			spawnTimestamps[key] = recent
			return false
		}
		recent.append(now)
		spawnTimestamps[key] = recent
		return true
	}

	private func effectiveRegistry(for url: URL) -> LSPServerRegistry {
		guard let root = registry.discoverWorkspaceRoot(for: url) else {
			return registry
		}
		let overrideURL = root
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("lsp.toml")
		guard let override = try? LSPServerRegistryLoader.loadTOML(from: overrideURL) else {
			return registry
		}
		return registry.merging(override)
	}

	private func mode(for url: URL, registry: LSPServerRegistry? = nil) -> ItsySettings.LSPMode {
		(registry ?? self.registry).languageID(for: url).map(lspSettings.mode(for:)) ?? .automatic
	}

	private static func requiresNodeRuntime(_ config: LSPServerConfig) -> Bool {
		["js", "mjs"].contains(URL(fileURLWithPath: config.command).pathExtension.lowercased())
	}

	private static func nodeRuntime(for config: LSPServerConfig) -> NodeRuntime? {
		let managedRoot = ManagedSupportInstaller.defaultInstallRoot().standardizedFileURL.path + "/"
		if config.command.hasPrefix(managedRoot) {
			return NodeRuntimeDetector.managedRuntime()
		}
		return NodeRuntimeDetector.resolve()
	}

	private static func nodeRuntimeHint(for config: LSPServerConfig) -> String {
		let managedRoot = ManagedSupportInstaller.defaultInstallRoot().standardizedFileURL.path + "/"
		return config.command.hasPrefix(managedRoot)
			? "Open Language & Debugger Support in Itsy to repair the private Node.js runtime."
			: "Install Node.js 20 or newer, then restart Itsy."
	}
}
