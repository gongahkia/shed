import Foundation
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
	case workspaceRootNotFound
	case missingBinary(LSPServerRegistry.MissingBinary)
	case retryLimitExceeded
	case serverDisabled(LSPSessionKey)
}

public actor LSPManager {
	public typealias ClientFactory = @Sendable (LSPServerConfig, URL) throws -> LSPProcessClient

	public static let defaultRetryWindow: TimeInterval = 60
	public static let defaultMaxSpawnsPerWindow = 3

	public let retryWindow: TimeInterval
	public let maxSpawnsPerWindow: Int

	private let registry: LSPServerRegistry
	private let clientFactory: ClientFactory
	private var clients: [LSPSessionKey: LSPProcessClient] = [:]
	private var statuses: [LSPSessionKey: LSPSessionStatus] = [:]
	private var spawnTimestamps: [LSPSessionKey: [Date]] = [:]
	private var disabledKeys: Set<LSPSessionKey> = []

	public init(
		registry: LSPServerRegistry = LSPServerRegistry(),
		retryWindow: TimeInterval = LSPManager.defaultRetryWindow,
		maxSpawnsPerWindow: Int = LSPManager.defaultMaxSpawnsPerWindow,
		clientFactory: @escaping ClientFactory = LSPManager.defaultClientFactory
	) {
		self.registry = registry
		self.retryWindow = retryWindow
		self.maxSpawnsPerWindow = maxSpawnsPerWindow
		self.clientFactory = clientFactory
	}

	public static let defaultClientFactory: ClientFactory = { config, root in
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
		registry.missingBinary(for: url)
	}

	public func ensureClient(for url: URL, now: Date = .init()) throws -> LSPProcessClient {
		guard let config = registry.resolvedConfig(for: url) else {
			if let missingBinary = registry.missingBinary(for: url) {
				throw LSPManagerError.missingBinary(missingBinary)
			}
			throw LSPManagerError.noConfigForDocument
		}
		guard let root = registry.discoverWorkspaceRoot(for: url) else {
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
		guard registry.config(for: url) != nil else {
			throw LSPManagerError.noConfigForDocument
		}
		guard registry.discoverWorkspaceRoot(for: url) != nil else {
			throw LSPManagerError.workspaceRootNotFound
		}
		let client = try ensureClient(for: url)
		return try await client.workspaceSymbol(query: query).workspaceSymbols
	}

	public func markRunning(_ key: LSPSessionKey) {
		statuses[key] = .running
	}

	public func markFailed(_ key: LSPSessionKey) {
		statuses[key] = disabledKeys.contains(key) ? .disabled : .failed
		clients.removeValue(forKey: key)
	}

	public func enableSession(_ key: LSPSessionKey) {
		disabledKeys.remove(key)
		spawnTimestamps[key] = []
		statuses[key] = .idle
	}

	public func shutdownAll() async {
		let keys = Array(clients.keys)
		for key in keys {
			guard let client = clients[key] else {
				continue
			}
			statuses[key] = .exited
			try? await client.shutdown()
			client.terminate()
		}
		clients.removeAll()
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
}
