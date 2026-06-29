import Foundation
import ItsyEditor
import Testing

@Test func workspaceTaskDiscoveryFindsCommonProjectTasks() throws {
	let fixture = try TemporaryTaskFixture()
	try fixture.write("Package.swift", "// swift package\n")
	try fixture.write("package.json", #"{"scripts":{"lint":"eslint .","test":"node test.js"}}"#)
	try fixture.write("Makefile", "build:\n\t@echo build\nclean:\n\t@echo clean\n.PHONY: build\n")
	try fixture.write("scripts/verify.sh", "echo verify\n")
	try fixture.write("local.sh", "echo local\n")

	let tasks = WorkspaceTaskDiscovery.discover(root: fixture.root)
	let ids = Set(tasks.map(\.id))

	#expect(ids.contains("swift:build"))
	#expect(ids.contains("swift:test"))
	#expect(ids.contains("npm:lint"))
	#expect(ids.contains("npm:test"))
	#expect(ids.contains("make:build"))
	#expect(ids.contains("make:clean"))
	#expect(ids.contains("script:scripts/verify.sh"))
	#expect(ids.contains("script:local.sh"))
}

@Test func workspaceTaskRunnerCapturesOutputAndExitStatus() throws {
	let fixture = try TemporaryTaskFixture()
	let task = WorkspaceTask(
		id: "test:echo",
		label: "echo",
		source: .shellScript,
		command: "/bin/sh",
		arguments: ["-c", "printf out; printf err >&2; exit 7"],
		workingDirectory: fixture.root
	)

	let result = try WorkspaceTaskRunner().run(task, root: fixture.root)

	#expect(result.exitStatus == 7)
	#expect(result.stdout == "out")
	#expect(result.stderr == "err")
	#expect(!result.succeeded)
}

private final class TemporaryTaskFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-task-\(UUID().uuidString)", isDirectory: true)
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
