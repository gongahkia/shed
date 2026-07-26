import Darwin
import Dispatch
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

@Test func workspaceTaskDiscoveryLoadsTaskFileMetadata() throws {
	let fixture = try TemporaryTaskFixture()
	try fixture.write(".itsy/tasks.json", """
	{
	  "tasks": [
	    {
	      "id": "build",
	      "label": "Build",
	      "command": "/bin/echo",
	      "arguments": ["build"],
	      "depends_on": ["prepare"],
	      "is_background": true,
	      "watch": { "paths": ["Sources"], "debounce_ms": 50 }
	    }
	  ]
	}
	""")

	let task = try #require(WorkspaceTaskDiscovery.discover(root: fixture.root).first)

	#expect(task.id == "workspace:build")
	#expect(task.dependsOn == ["prepare"])
	#expect(task.isBackground)
	#expect(task.watch == WorkspaceTaskWatch(paths: ["Sources"], debounceMillis: 50))
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

@Test func workspaceTaskRunnerRunsDependenciesBeforeRootTask() throws {
	let fixture = try TemporaryTaskFixture()
	let prepare = WorkspaceTask(
		id: "workspace:prepare",
		label: "prepare",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf prepare"]
	)
	let compile = WorkspaceTask(
		id: "workspace:compile",
		label: "compile",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf compile"],
		dependsOn: ["prepare"]
	)
	let build = WorkspaceTask(
		id: "workspace:build",
		label: "build",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf build"],
		dependsOn: ["compile"]
	)

	let result = try WorkspaceTaskRunner().run(build, root: fixture.root, availableTasks: [build, compile, prepare])

	#expect(result.succeeded)
	#expect(result.task == build)
	#expect(result.stdout == "preparecompilebuild")
}

@Test func workspaceTaskRunnerStopsCompoundAfterFailedDependency() throws {
	let fixture = try TemporaryTaskFixture()
	let dependency = WorkspaceTask(
		id: "workspace:dependency",
		label: "dependency",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf dependency; exit 23"]
	)
	let root = WorkspaceTask(
		id: "workspace:root",
		label: "root",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf root"],
		dependsOn: ["dependency"]
	)

	let result = try WorkspaceTaskRunner().run(root, root: fixture.root, availableTasks: [root, dependency])

	#expect(result.task == root)
	#expect(result.exitStatus == 23)
	#expect(result.stdout == "dependency")
	#expect(!result.succeeded)
}

@Test func workspaceTaskRunnerDrainsLargeOutputWithoutDeadlock() throws {
	let fixture = try TemporaryTaskFixture()
	let task = WorkspaceTask(
		id: "large-output",
		label: "large output",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "dd if=/dev/zero bs=65536 count=16 2>/dev/null | tr '\\000' o; dd if=/dev/zero bs=65536 count=16 2>/dev/null | tr '\\000' e >&2"]
	)

	let result = try WorkspaceTaskRunner().run(task, root: fixture.root)

	#expect(result.succeeded)
	#expect(result.stdout.utf8.count == 1_048_576)
	#expect(result.stderr.utf8.count == 1_048_576)
}

@Test func workspaceTaskPlannerRejectsMissingAndCyclicDependencies() throws {
	let missing = WorkspaceTask(id: "workspace:missing", label: "missing", source: .workspaceTaskFile, command: "/bin/true", dependsOn: ["absent"])
	#expect(throws: WorkspaceTaskPlanError.missingDependency("absent")) {
		_ = try WorkspaceTaskPlanner.executionPlan(for: missing, in: [missing])
	}

	let first = WorkspaceTask(id: "workspace:first", label: "first", source: .workspaceTaskFile, command: "/bin/true", dependsOn: ["second"])
	let second = WorkspaceTask(id: "workspace:second", label: "second", source: .workspaceTaskFile, command: "/bin/true", dependsOn: ["first"])
	#expect(throws: WorkspaceTaskPlanError.self) {
		_ = try WorkspaceTaskPlanner.executionPlan(for: first, in: [first, second])
	}
}

@Test func workspaceTaskRunnerStreamsOutputAndCancelsProcess() async throws {
	let fixture = try TemporaryTaskFixture()
	let recorder = TaskRunRecorder()
	let task = WorkspaceTask(
		id: "background",
		label: "background",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf ready; sleep 10"],
		isBackground: true
	)

	let handle = try WorkspaceTaskRunner().start(
		task,
		root: fixture.root,
		onOutput: { output in
			Task {
				await recorder.append(output.text)
			}
		},
		onFinish: { result in
			Task {
				await recorder.finish(result)
			}
		},
		onReady: { _ in
			Task {
				await recorder.ready()
			}
		}
	)
	try await recorder.waitForReady()
	#expect(handle.wasReady)
	#expect(handle.state == .running)
	try await recorder.waitForOutput(containing: "ready")
	handle.cancel(escalationDelay: 0.05)
	let result = try await recorder.waitForFinish()

	#expect(result.stdout == "ready")
	#expect(result.exitStatus != 0)
	#expect(result.wasCancelled)
	#expect(result.wasReady)
	#expect(handle.state == .cancelled(result.exitStatus))
}

@Test func workspaceTaskHandleCancelsBeforeLaunch() async throws {
	let fixture = try TemporaryTaskFixture()
	let recorder = TaskRunRecorder()
	let task = WorkspaceTask(
		id: "deferred",
		label: "deferred",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf should-not-run"]
	)
	let handle = WorkspaceTaskRunner().prepare(
		task,
		root: fixture.root,
		onOutput: { output in
			Task {
				await recorder.append(output.text)
			}
		},
		onFinish: { result in
			Task {
				await recorder.finish(result)
			}
		}
	)

	#expect(handle.state == .pending)
	handle.cancel()
	#expect(handle.state == .cancelling)
	#expect(!handle.isRunning)
	try handle.start()
	let result = try await recorder.waitForFinish()

	#expect(result.exitStatus == SIGTERM)
	#expect(result.stdout.isEmpty)
	#expect(result.wasCancelled)
	#expect(!result.succeeded)
	#expect(handle.state == .cancelled(SIGTERM))
}

@Test func workspaceTaskWatcherDebouncesFileChanges() async throws {
	let fixture = try TemporaryTaskFixture()
	try fixture.write("watched.txt", "before")
	let counter = WatchCounter()
	let watcher = WorkspaceTaskWatcher(
		root: fixture.root,
		watch: WorkspaceTaskWatch(paths: ["watched.txt"], debounceMillis: 50),
		queue: DispatchQueue(label: "dev.itsy.tests.task-watch")
	) {
		Task {
			await counter.increment()
		}
	}
	watcher.start()
	defer {
		watcher.stop()
	}

	try "after".write(to: fixture.root.appendingPathComponent("watched.txt"), atomically: false, encoding: .utf8)

	try await counter.waitForCount(1)
}

@Test func workspaceTaskWatcherObservesPathCreatedAfterStart() async throws {
	let fixture = try TemporaryTaskFixture()
	let counter = WatchCounter()
	let watcher = WorkspaceTaskWatcher(
		root: fixture.root,
		watch: WorkspaceTaskWatch(paths: ["generated/output.txt"], debounceMillis: 50),
		queue: DispatchQueue(label: "dev.itsy.tests.task-watch-created")
	) {
		Task {
			await counter.increment()
		}
	}
	watcher.start()
	defer {
		watcher.stop()
	}

	try fixture.write("generated/output.txt", "created")

	try await counter.waitForCount(1)
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

private actor TaskRunRecorder {
	private var output = ""
	private var result: WorkspaceTaskResult?
	private var didBecomeReady = false

	func append(_ text: String) {
		output += text
	}

	func finish(_ result: WorkspaceTaskResult) {
		self.result = result
	}

	func ready() {
		didBecomeReady = true
	}

	func waitForReady() async throws {
		for _ in 0 ..< 200 {
			if didBecomeReady {
				return
			}
			try await Task.sleep(nanoseconds: 10_000_000)
		}
		throw TaskTestError.readyTimeout
	}

	func waitForOutput(containing needle: String) async throws {
		for _ in 0 ..< 200 {
			if output.contains(needle) {
				return
			}
			try await Task.sleep(nanoseconds: 10_000_000)
		}
		throw TaskTestError.outputTimeout
	}

	func waitForFinish() async throws -> WorkspaceTaskResult {
		for _ in 0 ..< 300 {
			if let result {
				return result
			}
			try await Task.sleep(nanoseconds: 10_000_000)
		}
		throw TaskTestError.finishTimeout
	}
}

private actor WatchCounter {
	private var count = 0

	func increment() {
		count += 1
	}

	func waitForCount(_ expected: Int) async throws {
		for _ in 0 ..< 200 {
			if count >= expected {
				return
			}
			try await Task.sleep(nanoseconds: 10_000_000)
		}
		throw TaskTestError.watchTimeout
	}
}

private enum TaskTestError: Error {
	case outputTimeout
	case finishTimeout
	case readyTimeout
	case watchTimeout
}
