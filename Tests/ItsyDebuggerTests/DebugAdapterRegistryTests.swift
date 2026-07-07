import Foundation
import ItsyDebugger
import Testing

@Test func debugAdapterRegistryBundledDefaultsIncludeCommonAdapters() {
	let registry = DebugAdapterRegistry()

	#expect(registry.adapter(id: "lldb-dap") == DebugAdapterConfig(id: "lldb-dap", command: "lldb-dap"))
	#expect(registry.adapter(id: "lldb") == DebugAdapterConfig(id: "lldb", command: "lldb-dap"))
	#expect(registry.adapter(id: "debugpy") == DebugAdapterConfig(id: "debugpy", command: "python3", args: ["-m", "debugpy.adapter"]))
	#expect(registry.adapter(id: "delve") == DebugAdapterConfig(id: "delve", command: "dlv", args: ["dap"]))
	#expect(registry.adapter(id: "vscode-js-debug") == DebugAdapterConfig(id: "vscode-js-debug", command: "js-debug-adapter"))
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
	#expect(registry.adapter(id: "custom") == DebugAdapterConfig(id: "custom", command: "/user/custom-dap"))
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
