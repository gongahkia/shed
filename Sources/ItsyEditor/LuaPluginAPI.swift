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

final class LuaPluginAPIBridge: @unchecked Sendable {
	let pluginIdentifier: String
	let workspaceRoot: URL
	let settingValue: @Sendable (String) -> String?
	let activeEditorDocument: @Sendable () -> URL?
	private var commandReferences: [String: Int32] = [:]
	private var eventReferencesByName: [String: [Int32]] = [:]
	private var commandTitles: [String: String] = [:]
	private(set) var registrationDiagnostics: [String] = []

	init(pluginIdentifier: String, workspaceRoot: URL, settingValue: @escaping @Sendable (String) -> String?, activeEditorDocument: @escaping @Sendable () -> URL?) {
		self.pluginIdentifier = pluginIdentifier
		self.workspaceRoot = workspaceRoot
		self.settingValue = settingValue
		self.activeEditorDocument = activeEditorDocument
	}

	var commands: [LuaPluginCommand] {
		commandTitles.map { .init(identifier: $0.key, title: $0.value, pluginIdentifier: pluginIdentifier) }
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

	func commandReference(identifier: String) -> Int32? { commandReferences[identifier] }
	func eventReferences(named name: String) -> [Int32] { eventReferencesByName[name] ?? [] }

	static func install(into state: OpaquePointer) {
		lua_createtable(state, 0, 5)
		lua_createtable(state, 0, 1)
		pushFunction(luaAPIVersion, to: state, named: "version")
		lua_setfield(state, -2, "api")
		lua_createtable(state, 0, 1)
		pushFunction(luaCommandRegister, to: state, named: "register")
		lua_setfield(state, -2, "commands")
		lua_createtable(state, 0, 1)
		pushFunction(luaEventOn, to: state, named: "on")
		lua_setfield(state, -2, "events")
		lua_createtable(state, 0, 1)
		pushFunction(luaSettingsGet, to: state, named: "get")
		lua_setfield(state, -2, "settings")
		lua_createtable(state, 0, 1)
		pushFunction(luaEditorActiveDocument, to: state, named: "active_document")
		lua_setfield(state, -2, "editor")
		lua_createtable(state, 0, 1)
		pushFunction(luaWorkspaceRoot, to: state, named: "root")
		lua_setfield(state, -2, "workspace")
		lua_setglobal(state, "itsy")
	}

	private static func pushFunction(_ function: @convention(c) (OpaquePointer?) -> Int32, to state: OpaquePointer, named name: String) {
		lua_pushcclosure(state, function, 0)
		lua_setfield(state, -2, name)
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
	_ = LuaPluginAPI.version.withCString { lua_pushstring(state, $0) }
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
	_ = value.withCString { lua_pushstring(state, $0) }
	return 1
}

private func luaEditorActiveDocument(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state), let url = bridge.activeEditorDocument() else { return 0 }
	_ = url.path.withCString { lua_pushstring(state, $0) }
	return 1
}

private func luaWorkspaceRoot(_ state: OpaquePointer?) -> Int32 {
	guard let state, let bridge = LuaPluginAPIBridgeRegistry.bridge(for: state) else { return 0 }
	_ = bridge.workspaceRoot.path.withCString { lua_pushstring(state, $0) }
	return 1
}

private func luaString(_ state: OpaquePointer, index: Int32) -> String? {
	guard let pointer = lua_tolstring(state, index, nil) else { return nil }
	return String(cString: pointer)
}
