import Foundation
import ItsyDebugger
import ItsyEditor
import Testing

@Test func debugAdapterRegistryBundledDefaultsIncludeCommonAdapters() {
	let registry = DebugAdapterRegistry()

	#expect(registry.adapter(id: "lldb-dap") == DebugAdapterConfig(id: "lldb-dap", command: "lldb-dap"))
	#expect(registry.adapter(id: "lldb") == DebugAdapterConfig(id: "lldb", command: "lldb-dap"))
	#expect(registry.adapter(id: "debugpy") == DebugAdapterConfig(id: "debugpy", command: "python3", args: ["-m", "debugpy.adapter"]))
	#expect(registry.adapter(id: "delve") == DebugAdapterConfig(id: "delve", command: "dlv", args: ["dap"]))
	#expect(registry.adapter(id: "codelldb") == DebugAdapterConfig(id: "codelldb", command: "codelldb", kind: .codeLLDB))
	#expect(registry.adapter(id: "vscode-js-debug") == DebugAdapterConfig(id: "vscode-js-debug", command: "js-debug-adapter"))
	#expect(registry.adapter(id: "lldb")?.kind == .lldb)
	#expect(registry.adapter(id: "debugpy")?.kind == .debugpy)
	#expect(registry.adapter(id: "vscode-js-debug")?.kind == .jsDebug)
	#expect(registry.adapter(id: "delve")?.kind == .delve)
	#expect(registry.adapter(id: "codelldb")?.kind == .codeLLDB)
}

@Test func debugAdapterDetectorReportsExecutableOrActionableRemediation() {
	let available = DebugAdapterDetector.availability(for: DebugAdapterConfig(id: "custom", command: "/usr/bin/true"), environment: [:])
	#expect(available == .available(URL(fileURLWithPath: "/usr/bin/true")))

	let missing = DebugAdapterDetector.availability(for: DebugAdapterConfig(id: "debugpy", command: "missing-debugpy", kind: .debugpy), environment: ["PATH": ""])
	#expect(missing == .missing(DebugAdapterRemediation(
		adapterID: "debugpy",
		command: "missing-debugpy",
		hint: "Open Language & Debugger Support in Itsy."
	)))

	let missingCodeLLDB = DebugAdapterDetector.availability(for: DebugAdapterConfig(id: "codelldb", command: "missing-codelldb", kind: .codeLLDB), environment: ["PATH": ""])
	#expect(missingCodeLLDB == .missing(DebugAdapterRemediation(
		adapterID: "codelldb",
		command: "missing-codelldb",
		hint: "Open Language & Debugger Support in Itsy."
	)))
}

@Test func onDemandDebugAdaptersRequireItsyEnablement() throws {
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "delve"))
	let key = "itsy.support.enabled.\(component.id)"
	let previous = UserDefaults.standard.object(forKey: key)
	defer {
		if let previous {
			UserDefaults.standard.set(previous, forKey: key)
		} else {
			UserDefaults.standard.removeObject(forKey: key)
		}
	}
	ManagedSupportEnablement.setEnabled(false, for: component)
	let fixture = try DebugAdapterRegistryFixture()
	defer { fixture.cleanup() }
	let executable = fixture.workspaceRoot.appendingPathComponent("dlv")
	try "#!/bin/sh\nexit 0\n".write(to: executable, atomically: true, encoding: .utf8)
	try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
	let result = DebugAdapterDetector.availability(for: DebugAdapterConfig(id: "delve", command: "dlv", args: ["dap"]), environment: ["PATH": fixture.workspaceRoot.path])
	#expect(result == .missing(DebugAdapterRemediation(adapterID: "delve", command: "dlv", hint: "Open Language & Debugger Support in Itsy.")))
}

@Test func debugAdapterRegistryLoaderMergesUserAndWorkspaceTOML() throws {
	let fixture = try DebugAdapterRegistryFixture()
	defer {
		fixture.cleanup()
	}
	try fixture.writeUser("""
	[lldb-dap]
	command = "/user/lldb-dap"
	args = ["--stdio"]

	[custom]
	command = "/user/custom-dap"
	kind = "custom"
	remediation = "Install the custom adapter."
	""")
	try fixture.writeWorkspace("""
	[lldb-dap]
	command = "/workspace/lldb-dap"

	[debugpy]
	command = "/workspace/python"
	args = ["-m", "debugpy.adapter"]
	""")
	let loader = DebugAdapterRegistryLoader(userConfigURL: fixture.userConfigURL)

	let registry = try loader.load(workspaceRoot: fixture.workspaceRoot)

	#expect(registry.adapter(id: "lldb-dap") == DebugAdapterConfig(id: "lldb-dap", command: "/workspace/lldb-dap"))
	#expect(registry.adapter(id: "custom") == DebugAdapterConfig(id: "custom", command: "/user/custom-dap", remediation: "Install the custom adapter."))
	#expect(registry.adapter(id: "debugpy") == DebugAdapterConfig(id: "debugpy", command: "/workspace/python", args: ["-m", "debugpy.adapter"]))
	#expect(registry.adapter(id: "delve") == DebugAdapterConfig(id: "delve", command: "dlv", args: ["dap"]))
}

private struct DebugAdapterRegistryFixture {
	let root: URL
	let workspaceRoot: URL
	let userConfigURL: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-dap-registry-\(UUID().uuidString)", isDirectory: true)
		workspaceRoot = root.appendingPathComponent("workspace", isDirectory: true)
		userConfigURL = root
			.appendingPathComponent("home", isDirectory: true)
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("dap.toml")
		try fileManager.createDirectory(at: workspaceRoot, withIntermediateDirectories: true)
	}

	func writeUser(_ text: String, fileManager: FileManager = .default) throws {
		try write(text, to: userConfigURL, fileManager: fileManager)
	}

	func writeWorkspace(_ text: String, fileManager: FileManager = .default) throws {
		try write(text, to: workspaceRoot.appendingPathComponent(".itsy", isDirectory: true).appendingPathComponent("dap.toml"), fileManager: fileManager)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}

	private func write(_ text: String, to url: URL, fileManager: FileManager) throws {
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try text.write(to: url, atomically: true, encoding: .utf8)
	}
}
