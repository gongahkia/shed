import Foundation
import ItsyEditor
import Testing

@Test func luaPluginRuntimeLoadsTrustedPluginsAndExplicitlyTearsDown() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	let global = try fixture.writePlugin(scope: .global, identifier: "dev.example.global", source: "assert(2 + 2 == 4)\n")
	let workspace = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.workspace", source: "local value = 'loaded'\n")
	try fixture.vouch(global, scope: .global)
	try fixture.vouch(workspace, scope: .workspace)
	let runtime = LuaPluginRuntime(configuration: fixture.configuration)

	let loaded = await runtime.reload()

	#expect(loaded.diagnostics.isEmpty)
	#expect(loaded.activePlugins.map(\.identifier) == ["dev.example.global", "dev.example.workspace"])
	await runtime.teardown()
	let tornDown = await runtime.snapshot()
	#expect(tornDown.activePlugins.isEmpty)
}

@Test func luaPluginRuntimeReportsExecutionFailureAndRecoversOnReload() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.broken", source: "error('boom')\n")
	try fixture.vouch(packageRoot, scope: .workspace)
	let runtime = LuaPluginRuntime(configuration: fixture.configuration)

	let failed = await runtime.reload()

	#expect(failed.activePlugins.isEmpty)
	#expect(failed.diagnostics.count == 1)
	#expect(failed.diagnostics[0].phase == .execute)
	#expect(failed.diagnostics[0].message.contains("boom"))

	_ = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.broken", source: "local repaired = true\n")
	try fixture.vouch(packageRoot, scope: .workspace)
	let recovered = await runtime.reload()

	#expect(recovered.diagnostics.isEmpty)
	#expect(recovered.activePlugins.map(\.identifier) == ["dev.example.broken"])
}

@Test func luaPluginRuntimeReportsMissingTrustWithoutExecutingPlugin() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	_ = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.untrusted", source: "error('must not execute')\n")
	let runtime = LuaPluginRuntime(configuration: fixture.configuration)

	let snapshot = await runtime.reload()

	#expect(snapshot.activePlugins.isEmpty)
	#expect(snapshot.diagnostics.map(\.phase) == [.trust])
	#expect(snapshot.diagnostics[0].message == "plugin trust is missing")
}

@Test func luaPluginRuntimeReportsLuaSyntaxDiagnostics() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.syntax", source: "local =\n")
	try fixture.vouch(packageRoot, scope: .workspace)
	let runtime = LuaPluginRuntime(configuration: fixture.configuration)

	let snapshot = await runtime.reload()

	#expect(snapshot.activePlugins.isEmpty)
	#expect(snapshot.diagnostics.map(\.phase) == [.load])
	#expect(snapshot.diagnostics[0].message.contains("syntax"))
}

private final class LuaPluginRuntimeFixture {
	let root: URL
	let repo: URL
	let workspace: URL
	let home: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lua-runtime-\(UUID().uuidString)", isDirectory: true)
		repo = root.appendingPathComponent("repo", isDirectory: true)
		workspace = root.appendingPathComponent("workspace", isDirectory: true)
		home = root.appendingPathComponent("home", isDirectory: true)
		try FileManager.default.createDirectory(at: repo, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
	}

	var configuration: LuaPluginRuntimeConfiguration {
		LuaPluginRuntimeConfiguration(repoRoot: repo, workspaceRoot: workspace, homeDirectory: home)
	}

	func writePlugin(scope: LuaPluginScope, identifier: String, source: String) throws -> URL {
		let root = switch scope {
		case .global: LuaPluginDiscovery.globalRoot(homeDirectory: home)
		case .workspace: LuaPluginDiscovery.workspaceRoot(workspaceRoot: workspace)
		}
		let packageRoot = root.appendingPathComponent(identifier, isDirectory: true)
		try write(source, to: packageRoot.appendingPathComponent("main.lua"))
		try write("""
			return {
				manifest_version = 1,
				id = "\(identifier)",
				version = "1.0.0",
				api = ">=1.0.0 <2.0.0",
				entrypoint = "main.lua",
			}
			""", to: packageRoot.appendingPathComponent("itsy.lua"))
		return packageRoot
	}

	func vouch(_ packageRoot: URL, scope: LuaPluginScope) throws {
		let subject = try LuaPluginTrust.subject(packageRoot: packageRoot, scope: scope)
		let record = "allow sha256:\(subject.sha256) id:\(subject.identifier) version:\(subject.version) signer:test kind:lua-plugin scope:\(scope.rawValue)\n"
		let url = workspace.appendingPathComponent(".itsy/VOUCHED")
		let existing = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
		try write(existing + record, to: url)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}

	private func write(_ contents: String, to url: URL) throws {
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
