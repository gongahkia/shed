import Foundation
import ItsyEditor
import Testing

@Test func luaPluginManifestLoadsLiteralPackageMetadataAndEntrypoint() throws {
	let fixture = try LuaPluginManifestFixture()
	defer { fixture.remove() }
	try fixture.write("main.lua", "return {}\n")
	try fixture.write("itsy.lua", """
		-- declarative plugin manifest
		return {
			manifest_version = 1,
			id = "dev.example.hello",
			version = "1.2.3",
			api = ">=1.0.0 <2.0.0",
			entrypoint = "main.lua",
		}
		""")

	let manifest = try LuaPluginManifestLoader.load(packageRoot: fixture.root)

	#expect(manifest.manifestVersion == 1)
	#expect(manifest.identifier == "dev.example.hello")
	#expect(manifest.version == LuaPluginVersion(major: 1, minor: 2, patch: 3))
	#expect(manifest.apiRange.contains(LuaPluginManifest.currentAPIVersion))
	#expect(manifest.entrypointURL == fixture.root.appendingPathComponent("main.lua"))
}

@Test func luaPluginManifestRejectsUnsupportedAPIAndUnsafeEntrypoints() throws {
	let fixture = try LuaPluginManifestFixture()
	defer { fixture.remove() }
	try fixture.write("main.lua", "return {}\n")
	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev.example.future",
			version = "1.0.0",
			api = ">=2.0.0 <3.0.0",
			entrypoint = "main.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.incompatibleAPIRange(">=2.0.0 <3.0.0")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}

	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev.example.unsafe",
			version = "1.0.0",
			api = "1.0.0",
			entrypoint = "../outside.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.parentDirectoryEntrypoint("../outside.lua")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}
}

@Test func luaPluginManifestRejectsExecutableSyntaxAndMissingEntrypoint() throws {
	let fixture = try LuaPluginManifestFixture()
	defer { fixture.remove() }
	try fixture.write("itsy.lua", """
		return function()
			return {}
		end
		""")
	#expect(throws: LuaPluginManifestError.invalidSyntax) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}

	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev.example.missing",
			version = "1.0.0",
			api = "1.0.0",
			entrypoint = "missing.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.missingEntrypoint("missing.lua")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}
}

@Test func luaPluginManifestRejectsMalformedIdentityVersionAndAPIRange() throws {
	let fixture = try LuaPluginManifestFixture()
	defer { fixture.remove() }
	try fixture.write("main.lua", "return {}\n")
	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev/example",
			version = "1.0.0",
			api = "1.0.0",
			entrypoint = "main.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.invalidIdentifier("dev/example")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}

	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev.example.invalid",
			version = "1.0",
			api = "1.0.0",
			entrypoint = "main.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.invalidPackageVersion("1.0")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}

	try fixture.write("itsy.lua", """
		return {
			manifest_version = 1,
			id = "dev.example.invalid",
			version = "1.0.0",
			api = ">=next",
			entrypoint = "main.lua",
		}
		""")
	#expect(throws: LuaPluginManifestError.invalidAPIRange(">=next")) {
		_ = try LuaPluginManifestLoader.load(packageRoot: fixture.root)
	}
}

private final class LuaPluginManifestFixture {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lua-plugin-\(UUID().uuidString)", isDirectory: true)

	init() throws {
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}

	func remove() {
		try? FileManager.default.removeItem(at: root)
	}
}
