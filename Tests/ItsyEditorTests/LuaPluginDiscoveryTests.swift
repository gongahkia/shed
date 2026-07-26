import Foundation
import ItsyEditor
import Testing

@Test func luaPluginDiscoverySelectsWorkspaceOverridesAndLatestGlobalVersion() throws {
	let fixture = try LuaPluginDiscoveryFixture()
	defer { fixture.remove() }
	try fixture.writePlugin(scope: .global, path: "dev.example.alpha/1.0.0", identifier: "dev.example.alpha", version: "1.0.0")
	try fixture.writePlugin(scope: .global, path: "dev.example.alpha/2.0.0", identifier: "dev.example.alpha", version: "2.0.0")
	try fixture.writePlugin(scope: .global, path: "dev.example.beta/1.0.0", identifier: "dev.example.beta", version: "1.0.0")
	try fixture.writePlugin(scope: .workspace, path: "alpha", identifier: "dev.example.alpha", version: "0.5.0")

	let result = LuaPluginDiscovery.discover(workspaceRoot: fixture.workspace, homeDirectory: fixture.home)

	#expect(result.failures.isEmpty)
	#expect(result.plugins.count == 2)
	#expect(result.plugins[0].scope == .workspace)
	#expect(result.plugins[0].manifest.identifier == "dev.example.alpha")
	#expect(result.plugins[0].manifest.version == LuaPluginVersion(major: 0, minor: 5, patch: 0))
	#expect(result.plugins[1].scope == .global)
	#expect(result.plugins[1].manifest.identifier == "dev.example.beta")
	#expect(result.plugins[1].manifest.version == LuaPluginVersion(major: 1, minor: 0, patch: 0))
}

@Test func luaPluginDiscoveryReportsInvalidPackagesWithoutDiscardingValidPlugins() throws {
	let fixture = try LuaPluginDiscoveryFixture()
	defer { fixture.remove() }
	try fixture.writePlugin(scope: .workspace, path: "valid", identifier: "dev.example.valid", version: "1.0.0")
	try fixture.write(scope: .workspace, path: "broken/itsy.lua", contents: """
		return {
			manifest_version = 1,
			id = "dev.example.broken",
			version = "1.0.0",
			api = "1.0.0",
			entrypoint = "missing.lua",
		}
		""")

	let result = LuaPluginDiscovery.discover(workspaceRoot: fixture.workspace, homeDirectory: fixture.home)

	#expect(result.plugins.map(\.manifest.identifier) == ["dev.example.valid"])
	#expect(result.failures == [
		.init(
			scope: .workspace,
			packageRoot: LuaPluginDiscovery.workspaceRoot(workspaceRoot: fixture.workspace).appendingPathComponent("broken"),
			error: .invalidManifest(.missingEntrypoint("missing.lua"))
		),
	])
}

private enum LuaPluginDiscoveryFixtureScope {
	case global
	case workspace
}

private final class LuaPluginDiscoveryFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lua-plugin-discovery-\(UUID().uuidString)", isDirectory: true)
	let home: URL
	let workspace: URL

	init() throws {
		home = root.appendingPathComponent("home", isDirectory: true)
		workspace = root.appendingPathComponent("workspace", isDirectory: true)
		try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
	}

	func writePlugin(scope: LuaPluginDiscoveryFixtureScope, path: String, identifier: String, version: String) throws {
		try write(scope: scope, path: "\(path)/main.lua", contents: "return {}\n")
		try write(scope: scope, path: "\(path)/itsy.lua", contents: """
			return {
				manifest_version = 1,
				id = "\(identifier)",
				version = "\(version)",
				api = ">=1.0.0 <2.0.0",
				entrypoint = "main.lua",
			}
			""")
	}

	func write(scope: LuaPluginDiscoveryFixtureScope, path: String, contents: String) throws {
		let root = switch scope {
		case .global:
			LuaPluginDiscovery.globalRoot(homeDirectory: home)
		case .workspace:
			LuaPluginDiscovery.workspaceRoot(workspaceRoot: workspace)
		}
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}
}
