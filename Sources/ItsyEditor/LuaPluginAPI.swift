import CLua
import Foundation

public enum LuaPluginAPI {
	public static let version = "1.0.0"
}

public struct LuaPluginCommand: Equatable, Sendable {
	public let identifier: String
	public let title: String
	public let pluginIdentifier: String

	public init(identifier: String, title: String, pluginIdentifier: String) {
		self.identifier = identifier
		self.title = title
		self.pluginIdentifier = pluginIdentifier
	}
}

public enum LuaPluginContributionKind: String, CaseIterable, Equatable, Sendable {
	case ui
	case task
	case terminal
	case lsp
	case dap
	case git
	case github
	case process
	case network

	var requiredCapability: LuaPluginCapability? {
		switch self {
		case .task, .terminal, .process: .process
		case .github, .network: .network
		case .ui, .lsp, .dap, .git: nil
		}
	}
}

public struct LuaPluginContribution: Equatable, Sendable {
	public let kind: LuaPluginContributionKind
	public let identifier: String
	public let title: String
	public let action: String
	public let target: String?
	public let pluginIdentifier: String

	public init(
		kind: LuaPluginContributionKind,
		identifier: String,
		title: String,
		action: String,
		target: String? = nil,
		pluginIdentifier: String
	) {
		self.kind = kind
		self.identifier = identifier
		self.title = title
		self.action = action
		self.target = target
		self.pluginIdentifier = pluginIdentifier
	}
}

final class LuaPluginAPIBridge: @unchecked Sendable {
	let pluginIdentifier: String
	let workspaceRoot: URL
	let capabilities: Set<LuaPluginCapability>
	let settingValue: @Sendable (String) -> String?
	let activeEditorDocument: @Sendable () -> URL?
	private let filesystem: LuaPluginWorkspaceFileSystem
	private var commandReferences: [String: Int32] = [:]
	private var eventReferencesByName: [String: [Int32]] = [:]
	private var commandTitles: [String: String] = [:]
	private var contributionsByKey: [String: LuaPluginContribution] = [:]
	private(set) var registrationDiagnostics: [String] = []

	init(
		pluginIdentifier: String,
		workspaceRoot: URL,
		capabilities: Set<LuaPluginCapability>,
		settingValue: @escaping @Sendable (String) -> String?,
		activeEditorDocument: @escaping @Sendable () -> URL?
	) throws {
		self.pluginIdentifier = pluginIdentifier
		self.workspaceRoot = workspaceRoot
		self.capabilities = capabilities
		self.settingValue = settingValue
		self.activeEditorDocument = activeEditorDocument
		filesystem = try LuaPluginWorkspaceFileSystem(workspaceRoot: workspaceRoot)
	}

	var commands: [LuaPluginCommand] {
		commandTitles.map { .init(identifier: $0.key, title: $0.value, pluginIdentifier: pluginIdentifier) }
	}

	var contributions: [LuaPluginContribution] {
		contributionsByKey.values.sorted {
			let kind = $0.kind.rawValue.localizedStandardCompare($1.kind.rawValue)
			return kind == .orderedSame
				? $0.identifier.localizedStandardCompare($1.identifier) == .orderedAscending
				: kind == .orderedAscending
		}
	}

	func registerCommand(identifier: String, title: String, reference: Int32) -> Bool {
		guard commandReferences[identifier] == nil else {
			registrationDiagnostics.append("duplicate command registration: \(identifier)")
			return false
		}
		commandReferences[identifier] = reference
		commandTitles[identifier] = title
		return true
	}

	func registerEvent(name: String, reference: Int32) {
		eventReferencesByName[name, default: []].append(reference)
	}

	func registerContribution(kind: LuaPluginContributionKind, identifier: String, title: String, action: String, target: String?) -> String? {
		guard kind.requiredCapability.map(capabilities.contains) ?? true else {
			let message = "capability denied: \(kind.requiredCapability!.rawValue)"
			registrationDiagnostics.append(message)
			return message
		}
		guard [identifier, title, action].allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
			let message = "invalid \(kind.rawValue) registration"
			registrationDiagnostics.append(message)
			return message
		}
		if kind == .network, let target, !isHTTPSURL(target) {
			let message = "invalid network target"
			registrationDiagnostics.append(message)
			return message
		}
		if kind == .github, let target, !isGitHubPath(target) {
			let message = "invalid GitHub target"
			registrationDiagnostics.append(message)
			return message
		}
		let key = "\(kind.rawValue):\(identifier)"
		guard contributionsByKey[key] == nil else {
			let message = "duplicate \(kind.rawValue) registration: \(identifier)"
			registrationDiagnostics.append(message)
			return message
		}
		contributionsByKey[key] = .init(
			kind: kind,
			identifier: identifier,
			title: title,
			action: action,
			target: target,
			pluginIdentifier: pluginIdentifier
		)
		return nil
	}

	func readFile(_ relativePath: String) throws -> String {
		try filesystem.read(relativePath)
	}

	func writeFile(_ relativePath: String, contents: String) throws {
		try filesystem.write(relativePath, contents: contents)
	}

	func commandReference(identifier: String) -> Int32? { commandReferences[identifier] }
	func eventReferences(named name: String) -> [Int32] { eventReferencesByName[name] ?? [] }

	static func install(into state: OpaquePointer) {
		lua_createtable(state, 0, 14)
		installNamespace("api", functions: [("version", luaAPIVersion)], into: state)
		installNamespace("commands", functions: [("register", luaCommandRegister)], into: state)
		installNamespace("events", functions: [("on", luaEventOn)], into: state)
		installNamespace("settings", functions: [("get", luaSettingsGet)], into: state)
		installNamespace("editor", functions: [("active_document", luaEditorActiveDocument)], into: state)
		installNamespace("workspace", functions: [("root", luaWorkspaceRoot)], into: state)
		installNamespace("ui", functions: [("register", luaUIRegister)], into: state)
		installNamespace("tasks", functions: [("register", luaTaskRegister)], into: state)
		installNamespace("terminal", functions: [("register", luaTerminalRegister)], into: state)
		installNamespace("lsp", functions: [("register", luaLSPRegister)], into: state)
		installNamespace("dap", functions: [("register", luaDAPRegister)], into: state)
		installNamespace("git", functions: [("register", luaGitRegister)], into: state)
		installNamespace("github", functions: [("register", luaGitHubRegister)], into: state)
		installNamespace("process", functions: [("register", luaProcessRegister)], into: state)
		installNamespace("network", functions: [("register", luaNetworkRegister)], into: state)
		installNamespace("fs", functions: [("read", luaFilesystemRead), ("write", luaFilesystemWrite)], into: state)
		lua_setglobal(state, "itsy")
	}

	private static func installNamespace(
		_ name: String,
		functions: [(String, @convention(c) (OpaquePointer?) -> Int32)],
		into state: OpaquePointer
	) {
		lua_createtable(state, 0, Int32(functions.count))
		for (functionName, function) in functions {
			pushFunction(function, to: state, named: functionName)
		}
		lua_setfield(state, -2, name)
	}

	private static func pushFunction(_ function: @convention(c) (OpaquePointer?) -> Int32, to state: OpaquePointer, named name: String) {
		lua_pushcclosure(state, function, 0)
		lua_setfield(state, -2, name)
	}

	private func isHTTPSURL(_ value: String) -> Bool {
		guard let url = URL(string: value) else { return false }
		return url.scheme?.lowercased() == "https" && url.host != nil
	}

	private func isGitHubPath(_ value: String) -> Bool {
		value.hasPrefix("/") && !value.contains("//") && !value.split(separator: "/").contains("..")
	}
}

enum LuaPluginAPIBridgeRegistry {
	private static let lock = NSLock()
	private static var bridges: [OpaquePointer: LuaPluginAPIBridge] = [:]

	static func register(_ bridge: LuaPluginAPIBridge, for state: OpaquePointer) {
		lock.lock()
		bridges[state] = bridge
		lock.unlock()
	}

	static func remove(_ state: OpaquePointer) {
		lock.lock()
		bridges[state] = nil
		lock.unlock()
	}

	static func bridge(for state: OpaquePointer?) -> LuaPluginAPIBridge? {
		guard let state else { return nil }
		lock.lock()
		let bridge = bridges[state]
		lock.unlock()
		return bridge
	}
}

private func luaAPIVersion(_ state: OpaquePointer?) -> Int32 {
	guard let state else { return 0 }
	pushString(LuaPluginAPI.version, to: state)
	return 1
}

private func luaCommandRegister(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state),
		let identifier = luaString(state, index: 1), let title = luaString(state, index: 2), lua_type(state, 3) == 6
	else { return 0 }
	lua_pushvalue(state, 3)
	let reference = luaL_ref(state, -1001000)
	if !bridge.registerCommand(identifier: identifier, title: title, reference: reference) {
		luaL_unref(state, -1001000, reference)
	}
	return 0
}

private func luaEventOn(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state),
		let name = luaString(state, index: 1), lua_type(state, 2) == 6
	else { return 0 }
	lua_pushvalue(state, 2)
	bridge.registerEvent(name: name, reference: luaL_ref(state, -1001000))
	return 0
}

private func luaSettingsGet(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state), let key = luaString(state, index: 1) else { return 0 }
	guard let value = bridge.settingValue(key) else {
		lua_pushnil(state)
		return 1
	}
	pushString(value, to: state)
	return 1
}

private func luaEditorActiveDocument(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state), let url = bridge.activeEditorDocument() else { return 0 }
	pushString(url.path, to: state)
	return 1
}

private func luaWorkspaceRoot(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state) else { return 0 }
	pushString(bridge.workspaceRoot.path, to: state)
	return 1
}

private func luaUIRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .ui)
}

private func luaTaskRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .task)
}

private func luaTerminalRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .terminal)
}

private func luaLSPRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .lsp)
}

private func luaDAPRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .dap)
}

private func luaGitRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .git)
}

private func luaGitHubRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .github, targetIndex: 4)
}

private func luaProcessRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .process)
}

private func luaNetworkRegister(_ state: OpaquePointer?) -> Int32 {
	luaRegisterContribution(state, kind: .network, targetIndex: 4)
}

private func luaRegisterContribution(_ state: OpaquePointer?, kind: LuaPluginContributionKind, targetIndex: Int32? = nil) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state),
		let identifier = luaString(state, index: 1), let title = luaString(state, index: 2), let action = luaString(state, index: 3)
	else { return 0 }
	let target = targetIndex.flatMap { luaString(state, index: $0) }
	if targetIndex != nil, target == nil {
		return pushFailure("invalid \(kind.rawValue) registration", to: state)
	}
	if let error = bridge.registerContribution(kind: kind, identifier: identifier, title: title, action: action, target: target) {
		return pushFailure(error, to: state)
	}
	lua_pushboolean(state, 1)
	return 1
}

private func luaFilesystemRead(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state), let path = luaString(state, index: 1) else { return 0 }
	do {
		pushString(try bridge.readFile(path), to: state)
		return 1
	} catch {
		return pushFailure(filesystemMessage(error), to: state)
	}
}

private func luaFilesystemWrite(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state),
		let path = luaString(state, index: 1), let contents = luaString(state, index: 2)
	else { return 0 }
	do {
		try bridge.writeFile(path, contents: contents)
		lua_pushboolean(state, 1)
		return 1
	} catch {
		return pushFailure(filesystemMessage(error), to: state)
	}
}

private func filesystemMessage(_ error: Error) -> String {
	switch error {
	case let error as LuaPluginWorkspaceFileSystemError:
		switch error {
		case .invalidPath: "invalid workspace path"
		case .workspaceUnavailable: "workspace is unavailable"
		case .missing: "workspace file is missing"
		case .symbolicLinkRejected: "workspace symbolic link is not allowed"
		case .notRegularFile: "workspace path is not a regular file"
		case .invalidUTF8: "workspace file is not UTF-8"
		case .ioFailure: "workspace file operation failed"
		}
	default:
		"workspace file operation failed"
	}
}

private func pushFailure(_ message: String, to state: OpaquePointer) -> Int32 {
	lua_pushnil(state)
	pushString(message, to: state)
	return 2
}

private func pushString(_ value: String, to state: OpaquePointer) {
	_ = value.withCString { lua_pushstring(state, $0) }
}

private func luaString(_ state: OpaquePointer, index: Int32) -> String? {
	var length = 0
	guard let pointer = lua_tolstring(state, index, &length) else { return nil }
	return String(bytes: UnsafeRawBufferPointer(start: pointer, count: length), encoding: .utf8)
}
