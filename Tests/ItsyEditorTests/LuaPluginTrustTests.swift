import Foundation
import ItsyEditor
import Testing

@Test func luaPluginTrustRequiresMatchingContentAndScope() throws {
	let fixture = try LuaPluginTrustFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .workspace, contents: "return { value = 1 }\n")
	let subject = try LuaPluginTrust.subject(packageRoot: packageRoot, scope: .workspace)
	try fixture.write(
		"workspace/.itsy/VOUCHED",
		"allow sha256:\(subject.sha256) id:\(subject.identifier) version:\(subject.version) signer:test kind:lua-plugin scope:workspace\n"
	)

	#expect(try LuaPluginTrust.requireTrust(
		packageRoot: packageRoot,
		scope: .workspace,
		repoRoot: fixture.repo,
		workspaceRoot: fixture.workspace,
		homeDirectory: fixture.home
	) == .allow(VouchRecord(
		directive: .allow,
		sha256: subject.sha256,
		identifier: subject.identifier,
		version: subject.version,
		packageKind: .luaPlugin,
		packageScope: .workspace,
		signer: "test",
		source: fixture.workspace.appendingPathComponent(".itsy/VOUCHED"),
		line: 1
	)))
	#expect(throws: LuaPluginTrustError.trustMissing(VouchSubject(
		sha256: subject.sha256,
		identifier: subject.identifier,
		version: subject.version,
		packageKind: .luaPlugin,
		packageScope: .global
	))) {
		_ = try LuaPluginTrust.requireTrust(
			packageRoot: packageRoot,
			scope: .global,
			repoRoot: fixture.repo,
			workspaceRoot: fixture.workspace,
			homeDirectory: fixture.home
		)
	}

	_ = try fixture.writePlugin(scope: .workspace, contents: "return { value = 2 }\n")
	#expect(throws: LuaPluginTrustError.self) {
		_ = try LuaPluginTrust.requireTrust(
			packageRoot: packageRoot,
			scope: .workspace,
			repoRoot: fixture.repo,
			workspaceRoot: fixture.workspace,
			homeDirectory: fixture.home
		)
	}
}

@Test func luaPluginTrustRejectsSymbolicLinks() throws {
	let fixture = try LuaPluginTrustFixture()
	defer { fixture.remove() }
	let packageRoot = try fixture.writePlugin(scope: .global, contents: "return {}\n")
	let link = packageRoot.appendingPathComponent("linked.lua")
	try FileManager.default.createSymbolicLink(at: link, withDestinationURL: packageRoot.appendingPathComponent("main.lua"))

	#expect(throws: LuaPluginTrustError.symbolicLinkRejected("linked.lua")) {
		_ = try LuaPluginTrust.subject(packageRoot: packageRoot, scope: .global)
	}
}

private enum LuaPluginTrustFixtureScope {
	case global
	case workspace
}

private final class LuaPluginTrustFixture {
	let root: URL
	let repo: URL
	let workspace: URL
	let home: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lua-plugin-trust-\(UUID().uuidString)", isDirectory: true)
		repo = root.appendingPathComponent("repo", isDirectory: true)
		workspace = root.appendingPathComponent("workspace", isDirectory: true)
		home = root.appendingPathComponent("home", isDirectory: true)
		try FileManager.default.createDirectory(at: repo, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
		try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
	}

	func writePlugin(scope: LuaPluginTrustFixtureScope, contents: String) throws -> URL {
		let root = switch scope {
		case .global: LuaPluginDiscovery.globalRoot(homeDirectory: home).appendingPathComponent("dev.example.plugin")
		case .workspace: LuaPluginDiscovery.workspaceRoot(workspaceRoot: workspace).appendingPathComponent("dev.example.plugin")
		}
		try writeFile(root.appendingPathComponent("main.lua"), contents)
		try writeFile(root.appendingPathComponent("itsy.lua"), """
			return {
				manifest_version = 1,
				id = "dev.example.plugin",
				version = "1.0.0",
				api = ">=1.0.0 <2.0.0",
				entrypoint = "main.lua",
			}
			""")
		return root
	}

	func write(_ path: String, _ contents: String) throws {
		try writeFile(root.appendingPathComponent(path), contents)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}

	private func writeFile(_ url: URL, _ contents: String) throws {
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
