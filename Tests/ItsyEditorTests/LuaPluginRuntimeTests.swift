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
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

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
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

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
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

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
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

	let snapshot = await runtime.reload()

	#expect(snapshot.activePlugins.isEmpty)
	#expect(snapshot.diagnostics.map(\.phase) == [.load])
	#expect(snapshot.diagnostics[0].message.contains("syntax"))
}

@Test func luaPluginRuntimeExposesVersionedCommandEventSettingsEditorAndWorkspaceAPIs() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.api", source: """
		assert(itsy.api.version() == "1.0.0")
		assert(io == nil and os == nil and package == nil and debug == nil)
		assert(dofile == nil and loadfile == nil)
		assert(itsy.settings.get("theme") == "night")
		assert(itsy.workspace.root() == "\(fixture.workspace.path)")
		assert(itsy.editor.active_document() == "\(fixture.workspace.appendingPathComponent("main.swift").path)")
		itsy.commands.register("dev.example.api.hello", "Hello", function() end)
		itsy.events.on("workspace.opened", function(event) assert(event == "workspace.opened") end)
		""")
	try fixture.vouch(packageRoot, scope: .workspace)
	let documentURL = fixture.workspace.appendingPathComponent("main.swift")
	let runtime = LuaPluginRuntime(configuration: fixture.configuration(
		settingValue: { $0 == "theme" ? "night" : nil },
		activeEditorDocument: { documentURL }
	))

	let loaded = await runtime.reload()

	#expect(loaded.diagnostics.isEmpty)
	let commands = await runtime.commands()
	#expect(commands == [
		LuaPluginCommand(identifier: "dev.example.api.hello", title: "Hello", pluginIdentifier: "dev.example.api"),
	])
	let commandSnapshot = await runtime.invokeCommand(identifier: "dev.example.api.hello")
	let eventSnapshot = await runtime.publish(event: "workspace.opened")
	#expect(commandSnapshot.diagnostics.isEmpty)
	#expect(eventSnapshot.diagnostics.isEmpty)
}

@Test func luaPluginRuntimePublishesScopedContributionsAndWorkspaceFilesystem() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	try fixture.writeWorkspace("input.txt", contents: "input")
	let outside = fixture.root.appendingPathComponent("outside.txt")
	try "outside".write(to: outside, atomically: true, encoding: .utf8)
	try FileManager.default.createSymbolicLink(
		at: fixture.workspace.appendingPathComponent("linked.txt"),
		withDestinationURL: outside
	)
	let packageRoot = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.capabilities", source: """
		assert(itsy.fs.read("input.txt") == "input")
		assert(itsy.fs.write("generated.txt", "generated"))
		assert(itsy.fs.read("generated.txt") == "generated")
		assert(itsy.fs.write("nul.txt", "one\\0two"))
		assert(itsy.fs.read("nul.txt") == "one\\0two")
		local escaped, escaped_message = itsy.fs.read("../outside.txt")
		assert(escaped == nil and escaped_message == "invalid workspace path")
		local linked, linked_message = itsy.fs.read("linked.txt")
		assert(linked == nil and linked_message == "workspace symbolic link is not allowed")
		local vouch, vouch_message = itsy.fs.read(".ITSY/VOUCHED")
		assert(vouch == nil and vouch_message == "workspace control path is not allowed")
		local git, git_message = itsy.fs.write(".git/config", "[core]")
		assert(git == nil and git_message == "workspace control path is not allowed")
		assert(itsy.ui.register("ui", "UI", "toolbar"))
		assert(itsy.tasks.register("task", "Task", "swift test"))
		assert(itsy.terminal.register("terminal", "Terminal", "zsh"))
		assert(itsy.lsp.register("lsp", "LSP", "workspace/symbol"))
		assert(itsy.dap.register("dap", "DAP", "threads"))
		assert(itsy.git.register("git", "Git", "status"))
		assert(itsy.github.register("github", "GitHub", "GET", "/repos/org/repo/issues"))
		assert(itsy.process.register("process", "Process", "git status"))
		assert(itsy.network.register("network", "Network", "GET", "https://example.com/api"))
		""")
	try fixture.vouch(packageRoot, scope: .workspace, capabilities: [.process, .network])
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

	let loaded = await runtime.reload()

	#expect(loaded.diagnostics.isEmpty)
	#expect(try String(contentsOf: fixture.workspace.appendingPathComponent("generated.txt"), encoding: .utf8) == "generated")
	let contributions = await runtime.contributions()
	#expect(contributions == [
		.init(kind: .dap, identifier: "dap", title: "DAP", action: "threads", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .git, identifier: "git", title: "Git", action: "status", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .github, identifier: "github", title: "GitHub", action: "GET", target: "/repos/org/repo/issues", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .lsp, identifier: "lsp", title: "LSP", action: "workspace/symbol", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .network, identifier: "network", title: "Network", action: "GET", target: "https://example.com/api", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .process, identifier: "process", title: "Process", action: "git status", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .task, identifier: "task", title: "Task", action: "swift test", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .terminal, identifier: "terminal", title: "Terminal", action: "zsh", pluginIdentifier: "dev.example.capabilities"),
		.init(kind: .ui, identifier: "ui", title: "UI", action: "toolbar", pluginIdentifier: "dev.example.capabilities"),
	])
}

@Test func luaPluginRuntimeDeniesProcessAndNetworkWithoutScopedVouchCapabilities() async throws {
	let fixture = try LuaPluginRuntimeFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .workspace, identifier: "dev.example.restricted", source: """
		local process, process_message = itsy.process.register("process", "Process", "git status")
		assert(process == nil and process_message == "capability denied: process")
		local network, network_message = itsy.network.register("network", "Network", "GET", "https://example.com")
		assert(network == nil and network_message == "capability denied: network")
		assert(itsy.ui.register("ui", "UI", "toolbar"))
		""")
	try fixture.vouch(packageRoot, scope: .workspace)
	let runtime = LuaPluginRuntime(configuration: fixture.configuration())

	let loaded = await runtime.reload()

	#expect(loaded.activePlugins.map(\.identifier) == ["dev.example.restricted"])
	#expect(loaded.diagnostics.map(\.message) == ["capability denied: process", "capability denied: network"])
	#expect(await runtime.contributions() == [
		.init(kind: .ui, identifier: "ui", title: "UI", action: "toolbar", pluginIdentifier: "dev.example.restricted"),
	])
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

	func configuration(
		settingValue: @escaping @Sendable (String) -> String? = { _ in nil },
		activeEditorDocument: @escaping @Sendable () -> URL? = { nil }
	) -> LuaPluginRuntimeConfiguration {
		LuaPluginRuntimeConfiguration(
			repoRoot: repo,
			workspaceRoot: workspace,
			homeDirectory: home,
			settingValue: settingValue,
			activeEditorDocument: activeEditorDocument
		)
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

	func vouch(_ packageRoot: URL, scope: LuaPluginScope, capabilities: Set<LuaPluginCapability> = []) throws {
		let subject = try LuaPluginTrust.subject(packageRoot: packageRoot, scope: scope)
		let capabilityField = capabilities.isEmpty
			? ""
			: " capabilities:\(capabilities.map(\.rawValue).sorted().joined(separator: ","))"
		let record = "allow sha256:\(subject.sha256) id:\(subject.identifier) version:\(subject.version) signer:test kind:lua-plugin scope:\(scope.rawValue)\(capabilityField)\n"
		let url = workspace.appendingPathComponent(".itsy/VOUCHED")
		let existing = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
		try write(existing + record, to: url)
	}

	func writeWorkspace(_ relativePath: String, contents: String) throws {
		try write(contents, to: workspace.appendingPathComponent(relativePath))
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}

	private func write(_ contents: String, to url: URL) throws {
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
