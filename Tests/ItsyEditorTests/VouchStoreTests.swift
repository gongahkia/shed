import Foundation
import ItsyEditor
import Testing

private let trustedSHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private let deniedSHA = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

@Test func vouchStoreParsesAllowAndDenyRecords() throws {
	let store = try VouchStore.parse("""
	# comment

	allow sha256:\(trustedSHA) id:dev.example.tools version:0.1.0 signer:alice
	deny sha256:\(deniedSHA) id:dev.example.bad reason:known bad archive
	""")

	#expect(store.records.count == 2)
	#expect(store.records[0] == VouchRecord(
		directive: .allow,
		sha256: trustedSHA,
		identifier: "dev.example.tools",
		version: "0.1.0",
		signer: "alice",
		line: 3
	))
	#expect(store.records[1].directive == .deny)
	#expect(store.records[1].reason == "known bad archive")
}

@Test func vouchStoreDeniesBeforeAllowing() throws {
	let store = try VouchStore.parse("""
	allow sha256:\(trustedSHA) id:dev.example.tools version:0.1.0 signer:alice
	deny sha256:\(trustedSHA) id:dev.example.tools reason:revoked
	""")
	let subject = VouchSubject(sha256: trustedSHA, identifier: "dev.example.tools", version: "0.1.0")

	#expect(store.decision(for: subject) == .deny(VouchRecord(
		directive: .deny,
		sha256: trustedSHA,
		identifier: "dev.example.tools",
		reason: "revoked",
		line: 2
	)))
}

@Test func vouchStoreRequiresExactAllowVersion() throws {
	let store = try VouchStore.parse("""
	allow sha256:\(trustedSHA) id:dev.example.tools version:0.1.0 signer:alice
	""")

	#expect(store.decision(for: VouchSubject(
		sha256: trustedSHA,
		identifier: "dev.example.tools",
		version: "0.1.0"
	)) == .allow(VouchRecord(
		directive: .allow,
		sha256: trustedSHA,
		identifier: "dev.example.tools",
		version: "0.1.0",
		signer: "alice",
		line: 1
	)))
	#expect(store.decision(for: VouchSubject(
		sha256: trustedSHA,
		identifier: "dev.example.tools",
		version: "0.2.0"
	)) == .missing)
}

@Test func vouchStoreRequiresExplicitLuaPluginKindAndScopeForAllows() throws {
	let scoped = try VouchStore.parse("""
		allow sha256:\(trustedSHA) id:dev.example.lua version:1.0.0 signer:alice kind:lua-plugin scope:workspace
		""")
	let workspaceSubject = VouchSubject(
		sha256: trustedSHA,
		identifier: "dev.example.lua",
		version: "1.0.0",
		packageKind: .luaPlugin,
		packageScope: .workspace
	)
	let globalSubject = VouchSubject(
		sha256: trustedSHA,
		identifier: "dev.example.lua",
		version: "1.0.0",
		packageKind: .luaPlugin,
		packageScope: .global
	)

	#expect(scoped.decision(for: workspaceSubject) == .allow(VouchRecord(
		directive: .allow,
		sha256: trustedSHA,
		identifier: "dev.example.lua",
		version: "1.0.0",
		packageKind: .luaPlugin,
		packageScope: .workspace,
		signer: "alice",
		line: 1
	)))
	#expect(scoped.decision(for: globalSubject) == .missing)
	#expect(VouchStore(records: [VouchRecord(
		directive: .allow,
		sha256: trustedSHA,
		identifier: "dev.example.lua",
		version: "1.0.0",
		signer: "legacy"
	)]).decision(for: workspaceSubject) == .missing)
	let genericDeny = VouchRecord(
		directive: .deny,
		sha256: trustedSHA,
		identifier: "dev.example.lua",
		reason: "revoked"
	)
	#expect(VouchStore(records: [genericDeny]).decision(for: workspaceSubject) == .deny(genericDeny))
}

@Test func vouchStoreScopesLuaPluginCapabilitiesToExplicitAllows() throws {
	let store = try VouchStore.parse("""
		allow sha256:\(trustedSHA) id:dev.example.lua version:1.0.0 signer:alice kind:lua-plugin scope:workspace capabilities:process,network
		""")
	#expect(store.records[0].capabilities == [.process, .network])
	#expect(throws: VouchParseError.invalidCapability(line: 1, value: "filesystem")) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.lua version:1.0.0 signer:alice kind:lua-plugin scope:workspace capabilities:filesystem")
	}
	#expect(throws: VouchParseError.capabilitiesRequireAllow(line: 1)) {
		_ = try VouchStore.parse("deny sha256:\(trustedSHA) id:dev.example.lua kind:lua-plugin scope:workspace capabilities:process")
	}
	#expect(throws: VouchParseError.capabilitiesRequireLuaPlugin(line: 1)) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.extension version:1.0.0 signer:alice capabilities:process")
	}
}

@Test func vouchStoreLoadsDefaultURLOrder() throws {
	let fixture = try TemporaryVouchFixture()
	let repo = fixture.root.appendingPathComponent("repo", isDirectory: true)
	let workspace = fixture.root.appendingPathComponent("workspace", isDirectory: true)
	let home = fixture.root.appendingPathComponent("home", isDirectory: true)
	try FileManager.default.createDirectory(at: repo, withIntermediateDirectories: true)
	try FileManager.default.createDirectory(at: workspace.appendingPathComponent(".itsy", isDirectory: true), withIntermediateDirectories: true)
	try FileManager.default.createDirectory(at: home.appendingPathComponent(".config/itsy", isDirectory: true), withIntermediateDirectories: true)
	try fixture.write("repo/VOUCHED", "allow sha256:\(trustedSHA) id:dev.example.tools version:0.1.0 signer:repo\n")
	try fixture.write("home/.config/itsy/VOUCHED", "deny sha256:\(deniedSHA) id:dev.example.bad reason:user deny\n")
	try fixture.write("workspace/.itsy/VOUCHED", "allow sha256:\(deniedSHA) id:dev.example.bad version:0.1.0 signer:workspace\n")

	let urls = VouchStore.defaultURLs(repoRoot: repo, workspaceRoot: workspace, homeDirectory: home)
	let store = try VouchStore.load(urls: urls)

	#expect(urls.map(\.path).map { $0.replacingOccurrences(of: fixture.root.path + "/", with: "") } == [
		"repo/VOUCHED",
		"home/.config/itsy/VOUCHED",
		"workspace/.itsy/VOUCHED",
	])
	#expect(store.records.map(\.signer) == ["repo", nil, "workspace"])
	#expect(store.decision(for: VouchSubject(
		sha256: deniedSHA,
		identifier: "dev.example.bad",
		version: "0.1.0"
	)) == .deny(VouchRecord(
		directive: .deny,
		sha256: deniedSHA,
		identifier: "dev.example.bad",
		reason: "user deny",
		source: home.appendingPathComponent(".config/itsy/VOUCHED"),
		line: 1
	)))
}

@Test func vouchStoreRejectsMalformedRecords() {
	#expect(throws: VouchParseError.unknownDirective(line: 1, value: "maybe")) {
		_ = try VouchStore.parse("maybe sha256:\(trustedSHA) id:dev.example.tools")
	}
	#expect(throws: VouchParseError.invalidSHA256(line: 1, value: "abc")) {
		_ = try VouchStore.parse("deny sha256:abc id:dev.example.tools")
	}
	#expect(throws: VouchParseError.missingField(line: 1, field: "signer")) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.tools version:0.1.0")
	}
	#expect(throws: VouchParseError.luaPluginScopeRequired(line: 1)) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.lua version:1.0.0 signer:alice kind:lua-plugin")
	}
	#expect(throws: VouchParseError.scopeRequiresLuaPlugin(line: 1)) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.tools version:1.0.0 signer:alice kind:extension scope:workspace")
	}
	#expect(throws: VouchParseError.duplicateField(line: 1, field: "kind")) {
		_ = try VouchStore.parse("allow sha256:\(trustedSHA) id:dev.example.lua version:1.0.0 signer:alice kind:lua-plugin kind:extension scope:workspace")
	}
}

@Test func vouchStorePropertyRejectsInvalidSHA256Shapes() {
	let invalidValues = [
		String(repeating: "0", count: 63),
		String(repeating: "0", count: 65),
		String(repeating: "g", count: 64),
	]

	for value in invalidValues {
		#expect(throws: VouchParseError.invalidSHA256(line: 1, value: value)) {
			_ = try VouchStore.parse("deny sha256:\(value) id:dev.example.tools")
		}
	}
}

private final class TemporaryVouchFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-vouch-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func write(_ path: String, _ contents: String) throws {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
	}
}
