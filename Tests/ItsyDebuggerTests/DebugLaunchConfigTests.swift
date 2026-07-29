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
	    { "name": "Debug Itsy", "type": "lldb", "request": "launch", "program": ".build/debug/ItsyApp", "exceptionFilters": ["filter-a", "filter-b"] }
	  ]
	}
	"""#.utf8)

	let config = try JSONDecoder().decode(DebugLaunchConfig.self, from: data)

	#expect(config.adapters == [
		DebugAdapterConfig(id: "lldb", command: "/usr/bin/lldb-dap", type: DebugAdapterType.executable, args: []),
	])
	#expect(config.configurations == [
		DebugLaunchConfiguration(name: "Debug Itsy", type: "lldb", request: DebugLaunchRequest.launch, program: ".build/debug/ItsyApp", exceptionFilters: ["filter-a", "filter-b"]),
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

@Test func debugLaunchConfigParserValidatesExecutableCWDEnvironmentAndSourceMaps() throws {
	let valid = Data(#"""
	{
	  "adapters": [{ "id": "lldb", "command": "/usr/bin/lldb-dap", "kind": "lldb" }],
	  "configurations": [{
	    "name": "Debug",
	    "type": "lldb",
	    "request": "launch",
	    "program": ".build/debug/App",
	    "cwd": "${workspaceFolder}",
	    "env": { "MODE": "debug" },
	    "sourceMap": { "/remote/src": "${workspaceFolder}/Sources" },
	    "adapterOptions": { "type": "lldb", "sourceLanguages": ["rust"] }
	  }]
	}
	"""#.utf8)
	let config = try DebugLaunchConfigParser.parse(data: valid)
	#expect(config.adapters[0].kind == .lldb)
	#expect(config.configurations[0].sourceMap == ["/remote/src": "${workspaceFolder}/Sources"])
	#expect(config.configurations[0].adapterOptions == ["type": .string("lldb"), "sourceLanguages": .array([.string("rust")])])

	let invalid = Data(#"""
	{
	  "configurations": [{
	    "name": "Debug",
	    "type": "lldb",
	    "request": "launch",
	    "program": "/usr/bin/true",
	    "cwd": "bad\npath",
	    "env": { "BAD-NAME": "1" },
	    "sourceMap": { "": "/local" }
	  }]
	}
	"""#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidConfiguration(name: "Debug", field: "cwd", reason: "must be non-empty without control characters")) {
		_ = try DebugLaunchConfigParser.parse(data: invalid)
	}

	let missingProgram = Data(#"{"configurations":[{"name":"Debug","type":"lldb","request":"launch"}]}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidConfiguration(name: "Debug", field: "program", reason: "is required for launch and must not contain control characters")) {
		_ = try DebugLaunchConfigParser.parse(data: missingProgram)
	}

	let reservedOption = Data(#"{"configurations":[{"name":"Debug","type":"lldb","request":"launch","program":"/usr/bin/true","adapterOptions":{"program":"/tmp/override"}}]}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidConfiguration(name: "Debug", field: "adapterOptions.program", reason: "must use a non-reserved adapter option key")) {
		_ = try DebugLaunchConfigParser.parse(data: reservedOption)
	}

	let unknownField = Data(#"{"configurations":[{"name":"Debug","type":"lldb","request":"launch","program":"/usr/bin/true","unsupported":true}]}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidJSON) {
		_ = try DebugLaunchConfigParser.parse(data: unknownField)
	}
	let unknownAdapterField = Data(#"{"adapters":[{"id":"lldb","command":"/usr/bin/lldb-dap","unsupported":true}]}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidJSON) {
		_ = try DebugLaunchConfigParser.parse(data: unknownAdapterField)
	}
	let unknownTopLevelField = Data(#"{"unsupported":true}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidJSON) {
		_ = try DebugLaunchConfigParser.parse(data: unknownTopLevelField)
	}

	let invalidExceptionFilter = Data(#"{"configurations":[{"name":"Debug","type":"lldb","request":"launch","program":"/usr/bin/true","exceptionFilters":[""]}]}"#.utf8)
	#expect(throws: DebugLaunchConfigError.invalidConfiguration(name: "Debug", field: "exceptionFilters", reason: "must be non-empty without control characters")) {
		_ = try DebugLaunchConfigParser.parse(data: invalidExceptionFilter)
	}
}

@Test func debugLaunchConfigStorePersistsValidatedUserAndWorkspaceConfigs() throws {
	let fixture = try DebugLaunchConfigFixture()
	defer { fixture.cleanup() }
	let store = DebugLaunchConfigStore(userConfigURL: fixture.userConfigURL)
	let user = DebugLaunchConfig(adapters: [DebugAdapterConfig(id: "lldb", command: "/usr/bin/lldb-dap")])
	let workspace = DebugLaunchConfig(configurations: [DebugLaunchConfiguration(name: "Debug", type: "lldb", request: DebugLaunchRequest.launch, program: "/usr/bin/true")])

	try store.saveUser(user)
	try store.saveWorkspace(workspace, workspaceRoot: fixture.workspaceRoot)

	#expect(try DebugLaunchConfigParser.parse(data: Data(contentsOf: fixture.userConfigURL)) == user)
	#expect(try DebugLaunchConfigParser.parse(data: Data(contentsOf: store.workspaceConfigURL(for: fixture.workspaceRoot))) == workspace)
	#expect(try DebugLaunchConfigLoader(userConfigURL: fixture.userConfigURL).load(workspaceRoot: fixture.workspaceRoot) == user.merging(workspace))
}

@Test func debugLaunchConfigStoreRejectsInvalidConfigWithoutReplacingPersistedData() throws {
	let fixture = try DebugLaunchConfigFixture()
	defer { fixture.cleanup() }
	let store = DebugLaunchConfigStore(userConfigURL: fixture.userConfigURL)
	let valid = DebugLaunchConfig(configurations: [DebugLaunchConfiguration(name: "Debug", type: "lldb", request: DebugLaunchRequest.launch, program: "/usr/bin/true")])
	try store.saveUser(valid)
	let persisted = try Data(contentsOf: fixture.userConfigURL)
	let invalid = DebugLaunchConfig(configurations: [DebugLaunchConfiguration(name: "", type: "lldb", request: DebugLaunchRequest.launch, program: "/usr/bin/true")])

	#expect(throws: DebugLaunchConfigError.invalidConfiguration(name: "", field: "name", reason: "must be non-empty without control characters")) {
		try store.saveUser(invalid)
	}
	#expect(try Data(contentsOf: fixture.userConfigURL) == persisted)
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
