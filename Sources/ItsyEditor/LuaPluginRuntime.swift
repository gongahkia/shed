import CLua
import Foundation

public enum LuaPluginRuntimePhase: String, Equatable, Sendable {
	case discovery
	case trust
	case initialize
	case load
	case execute
	case registration
}

public struct LuaPluginRuntimeDiagnostic: Equatable, Sendable {
	public let pluginIdentifier: String?
	public let scope: LuaPluginScope?
	public let path: URL?
	public let phase: LuaPluginRuntimePhase
	public let message: String

	public init(pluginIdentifier: String?, scope: LuaPluginScope?, path: URL?, phase: LuaPluginRuntimePhase, message: String) {
		self.pluginIdentifier = pluginIdentifier
		self.scope = scope
		self.path = path?.standardizedFileURL
		self.phase = phase
		self.message = message
	}
}

public struct LuaPluginRuntimePlugin: Equatable, Sendable {
	public let identifier: String
	public let version: LuaPluginVersion
	public let scope: LuaPluginScope
	public let packageRoot: URL
	public let entrypointURL: URL

	public init(identifier: String, version: LuaPluginVersion, scope: LuaPluginScope, packageRoot: URL, entrypointURL: URL) {
		self.identifier = identifier
		self.version = version
		self.scope = scope
		self.packageRoot = packageRoot.standardizedFileURL
		self.entrypointURL = entrypointURL.standardizedFileURL
	}
}

public struct LuaPluginRuntimeSnapshot: Equatable, Sendable {
	public let activePlugins: [LuaPluginRuntimePlugin]
	public let diagnostics: [LuaPluginRuntimeDiagnostic]

	public init(activePlugins: [LuaPluginRuntimePlugin], diagnostics: [LuaPluginRuntimeDiagnostic]) {
		self.activePlugins = activePlugins
		self.diagnostics = diagnostics
	}
}

public struct LuaPluginRuntimeConfiguration: Sendable {
	public let repoRoot: URL
	public let workspaceRoot: URL
	public let homeDirectory: URL
	public let vouchEvidence: (@Sendable (VouchSubject) -> VouchDecision?)?
	public let settingValue: @Sendable (String) -> String?
	public let activeEditorDocument: @Sendable () -> URL?

	public init(
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		vouchEvidence: (@Sendable (VouchSubject) -> VouchDecision?)? = nil,
		settingValue: @escaping @Sendable (String) -> String? = { _ in nil },
		activeEditorDocument: @escaping @Sendable () -> URL? = { nil }
	) {
		self.repoRoot = repoRoot.standardizedFileURL
		self.workspaceRoot = workspaceRoot.standardizedFileURL
		self.homeDirectory = homeDirectory.standardizedFileURL
		self.vouchEvidence = vouchEvidence
		self.settingValue = settingValue
		self.activeEditorDocument = activeEditorDocument
	}
}

public actor LuaPluginRuntime {
	private let configuration: LuaPluginRuntimeConfiguration
	private var states: [String: OpaquePointer] = [:]
	private var bridges: [String: LuaPluginAPIBridge] = [:]
	private var activePlugins: [LuaPluginRuntimePlugin] = []
	private var diagnostics: [LuaPluginRuntimeDiagnostic] = []

	public init(configuration: LuaPluginRuntimeConfiguration) {
		self.configuration = configuration
	}

	deinit {
		for state in states.values {
			LuaPluginAPIBridgeRegistry.remove(state)
			lua_close(state)
		}
	}

	public func reload() -> LuaPluginRuntimeSnapshot {
		teardownStates()
		activePlugins = []
		diagnostics = []
		let discovery = LuaPluginDiscovery.discover(
			workspaceRoot: configuration.workspaceRoot,
			homeDirectory: configuration.homeDirectory
		)
		for failure in discovery.failures {
			diagnostics.append(.init(
				pluginIdentifier: nil,
				scope: failure.scope,
				path: failure.packageRoot,
				phase: .discovery,
				message: discoveryMessage(failure.error)
			))
		}
		for plugin in discovery.plugins {
			load(plugin)
		}
		return snapshot()
	}

	public func teardown() {
		teardownStates()
		activePlugins = []
	}

	public func snapshot() -> LuaPluginRuntimeSnapshot {
		LuaPluginRuntimeSnapshot(activePlugins: activePlugins, diagnostics: diagnostics)
	}

	public func commands() -> [LuaPluginCommand] {
		bridges.values.flatMap { $0.commands }.sorted { $0.identifier.localizedStandardCompare($1.identifier) == .orderedAscending }
	}

	public func invokeCommand(identifier: String) -> LuaPluginRuntimeSnapshot {
		for (pluginID, bridge) in bridges where bridge.commands.contains(where: { $0.identifier == identifier }) {
			invoke(pluginID: pluginID, reference: bridge.commandReference(identifier: identifier), argument: nil)
		}
		return snapshot()
	}

	public func publish(event: String) -> LuaPluginRuntimeSnapshot {
		for (pluginID, bridge) in bridges {
			for reference in bridge.eventReferences(named: event) {
				invoke(pluginID: pluginID, reference: reference, argument: event)
			}
		}
		return snapshot()
	}

	private func load(_ plugin: LuaDiscoveredPlugin) {
		let manifest = plugin.manifest
		do {
			_ = try LuaPluginTrust.requireTrust(
				packageRoot: manifest.packageRoot,
				scope: plugin.scope,
				repoRoot: configuration.repoRoot,
				workspaceRoot: configuration.workspaceRoot,
				homeDirectory: configuration.homeDirectory,
				vouchEvidence: configuration.vouchEvidence
			)
		} catch {
			diagnostics.append(diagnostic(plugin, phase: .trust, message: trustMessage(error)))
			return
		}
		guard let state = luaL_newstate() else {
			diagnostics.append(diagnostic(plugin, phase: .initialize, message: "unable to create Lua state"))
			return
		}
		luaL_openlibs(state)
		let bridge = LuaPluginAPIBridge(
			pluginIdentifier: manifest.identifier,
			workspaceRoot: configuration.workspaceRoot,
			settingValue: configuration.settingValue,
			activeEditorDocument: configuration.activeEditorDocument
		)
		LuaPluginAPIBridgeRegistry.register(bridge, for: state)
		LuaPluginAPIBridge.install(into: state)
		let loadStatus = manifest.entrypointURL.path.withCString { entrypoint in
			luaL_loadfilex(state, entrypoint, nil)
		}
		guard loadStatus == 0 else {
			diagnostics.append(diagnostic(plugin, phase: .load, message: luaError(state)))
			lua_close(state)
			LuaPluginAPIBridgeRegistry.remove(state)
			return
		}
		guard lua_pcallk(state, 0, 0, 0, 0, nil) == 0 else {
			diagnostics.append(diagnostic(plugin, phase: .execute, message: luaError(state)))
			lua_close(state)
			LuaPluginAPIBridgeRegistry.remove(state)
			return
		}
		let runtimePlugin = LuaPluginRuntimePlugin(
			identifier: manifest.identifier,
			version: manifest.version,
			scope: plugin.scope,
			packageRoot: manifest.packageRoot,
			entrypointURL: manifest.entrypointURL
		)
		states[manifest.identifier] = state
		bridges[manifest.identifier] = bridge
		for message in bridge.registrationDiagnostics {
			diagnostics.append(diagnostic(plugin, phase: .registration, message: message))
		}
		activePlugins.append(runtimePlugin)
		activePlugins.sort { $0.identifier.localizedStandardCompare($1.identifier) == .orderedAscending }
	}

	private func teardownStates() {
		for state in states.values {
			LuaPluginAPIBridgeRegistry.remove(state)
			lua_close(state)
		}
		states = [:]
		bridges = [:]
	}

	private func diagnostic(_ plugin: LuaDiscoveredPlugin, phase: LuaPluginRuntimePhase, message: String) -> LuaPluginRuntimeDiagnostic {
		.init(
			pluginIdentifier: plugin.manifest.identifier,
			scope: plugin.scope,
			path: plugin.manifest.entrypointURL,
			phase: phase,
			message: message
		)
	}

	private func luaError(_ state: OpaquePointer) -> String {
		guard let pointer = lua_tolstring(state, -1, nil) else {
			return "Lua returned an error without a message"
		}
		let message = String(cString: pointer)
		lua_settop(state, 0)
		return message
	}

	private func discoveryMessage(_ error: LuaPluginDiscoveryError) -> String {
		switch error {
		case .unreadableRoot: "plugin root is unreadable"
		case .packageEscapesRoot: "plugin package escapes its discovery root"
		case let .invalidManifest(error): "invalid manifest: \(error)"
		}
	}

	private func trustMessage(_ error: Error) -> String {
		switch error {
		case let error as LuaPluginTrustError:
			switch error {
			case .trustDenied: return "plugin trust was denied"
			case .trustMissing: return "plugin trust is missing"
			case let .invalidManifest(error): return "invalid manifest: \(error)"
			case .packageNotDirectory: return "plugin package is not a directory"
			case .unreadablePackage: return "plugin package is unreadable"
			case let .symbolicLinkRejected(path): return "plugin contains symbolic link: \(path)"
			}
		default:
			return "plugin trust check failed: \(error)"
		}
	}

	private func invoke(pluginID: String, reference: Int32?, argument: String?) {
		guard let state = states[pluginID], let reference else { return }
		lua_rawgeti(state, -1001000, Int64(reference))
		if let argument {
			_ = argument.withCString { lua_pushstring(state, $0) }
		}
		let argumentCount: Int32 = argument == nil ? 0 : 1
		guard lua_pcallk(state, argumentCount, 0, 0, 0, nil) != 0 else { return }
		let plugin = activePlugins.first { $0.identifier == pluginID }
		diagnostics.append(.init(
			pluginIdentifier: pluginID,
			scope: plugin?.scope,
			path: plugin?.entrypointURL,
			phase: .execute,
			message: luaError(state)
		))
	}
}
