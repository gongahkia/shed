import Foundation
import ItsyDebugger
import Testing

@Test func debugLaunchConfigDecodesTodoSchema() throws {
	let data = Data(#"""
	{
	  "adapters": [
	    { "id": "lldb", "command": "/usr/bin/lldb-dap", "type": "executable", "args": [] }
	  ],
	  "configurations": [
	    { "name": "Debug Itsy", "type": "lldb", "request": "launch", "program": ".build/debug/ItsyApp" }
	  ]
	}
	"""#.utf8)

	let config = try JSONDecoder().decode(DebugLaunchConfig.self, from: data)

	#expect(config.adapters == [
		DebugAdapterConfig(id: "lldb", command: "/usr/bin/lldb-dap", type: DebugAdapterType.executable, args: []),
	])
	#expect(config.configurations == [
		DebugLaunchConfiguration(name: "Debug Itsy", type: "lldb", request: DebugLaunchRequest.launch, program: ".build/debug/ItsyApp"),
	])
}

@Test func debugLaunchConfigLoaderMergesUserAndWorkspaceFiles() throws {
	let fixture = try DebugLaunchConfigFixture()
	defer {
		fixture.cleanup()
	}
	try fixture.writeUserConfig(DebugLaunchConfig(
		adapters: [
			DebugAdapterConfig(id: "lldb", command: "/usr/bin/lldb-dap"),
			DebugAdapterConfig(id: "mock", command: "/tmp/mock-dap"),
		],
		configurations: [
			DebugLaunchConfiguration(name: "Debug Shared", type: "lldb", request: DebugLaunchRequest.launch, program: "/usr/bin/true"),
			DebugLaunchConfiguration(name: "Attach", type: "lldb", request: DebugLaunchRequest.attach),
		]
	))
	try fixture.writeWorkspaceConfig(DebugLaunchConfig(
		adapters: [
			DebugAdapterConfig(id: "lldb", command: "/Applications/Xcode.app/lldb-dap", args: ["--stdio"]),
		],
		configurations: [
			DebugLaunchConfiguration(name: "Debug Shared", type: "lldb", request: DebugLaunchRequest.launch, program: ".build/debug/App", args: ["--dev"]),
			DebugLaunchConfiguration(name: "Debug Local", type: "mock", request: DebugLaunchRequest.launch, program: "./local"),
		]
	))
	let loader = DebugLaunchConfigLoader(userConfigURL: fixture.userConfigURL)

	let config = try loader.load(workspaceRoot: fixture.workspaceRoot)

	#expect(config.adapters == [
		DebugAdapterConfig(id: "lldb", command: "/Applications/Xcode.app/lldb-dap", args: ["--stdio"]),
		DebugAdapterConfig(id: "mock", command: "/tmp/mock-dap"),
	])
	#expect(config.configurations == [
		DebugLaunchConfiguration(name: "Debug Shared", type: "lldb", request: DebugLaunchRequest.launch, program: ".build/debug/App", args: ["--dev"]),
		DebugLaunchConfiguration(name: "Attach", type: "lldb", request: DebugLaunchRequest.attach),
		DebugLaunchConfiguration(name: "Debug Local", type: "mock", request: DebugLaunchRequest.launch, program: "./local"),
	])
}

@Test func debugLaunchConfigLoaderReturnsEmptyConfigWhenFilesAreMissing() throws {
	let fixture = try DebugLaunchConfigFixture()
	defer {
		fixture.cleanup()
	}
	let loader = DebugLaunchConfigLoader(userConfigURL: fixture.userConfigURL)

	let config = try loader.load(workspaceRoot: fixture.workspaceRoot)

	#expect(config == DebugLaunchConfig())
}

private struct DebugLaunchConfigFixture {
	let root: URL
	let workspaceRoot: URL
	let userConfigURL: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-debug-config-\(UUID().uuidString)", isDirectory: true)
		workspaceRoot = root.appendingPathComponent("workspace", isDirectory: true)
		userConfigURL = root
			.appendingPathComponent("home", isDirectory: true)
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("debug.json")
		try fileManager.createDirectory(at: workspaceRoot, withIntermediateDirectories: true)
	}

	func writeUserConfig(_ config: DebugLaunchConfig, fileManager: FileManager = .default) throws {
		try write(config, to: userConfigURL, fileManager: fileManager)
	}

	func writeWorkspaceConfig(_ config: DebugLaunchConfig, fileManager: FileManager = .default) throws {
		let url = workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("debug.json")
		try write(config, to: url, fileManager: fileManager)
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}

	private func write(_ config: DebugLaunchConfig, to url: URL, fileManager: FileManager) throws {
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try JSONEncoder().encode(config).write(to: url)
	}
}
