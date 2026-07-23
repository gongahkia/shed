import Foundation
import ItsyEditor
import Testing

@Test func workspaceTaskConfigurationParsesVersionedProjectAndGlobalDefinitions() throws {
	let data = Data("""
	{
	  "version": 1,
	  "tasks": [{
	    "id": "build.app",
	    "label": "Build App",
	    "command": "swift",
	    "arguments": ["build"],
	    "cwd": "App",
	    "env": { "MODE": "debug" },
	    "inputs": [{ "id": "target", "prompt": "Target", "default": "app" }],
	    "depends_on": ["prepare"],
	    "is_background": true,
	    "watch": { "paths": ["Sources"], "debounce_ms": 75, "policy": "on_change" },
	    "presentation": { "reveal": "always", "focus": true, "dedicated": true, "show_resolved_command": true },
	    "problem_matchers": ["swiftc"]
	  }]
	}
	""".utf8)
	let configuration = try WorkspaceTaskConfigurationParser.parse(data: data, scope: .project)
	let root = URL(fileURLWithPath: "/tmp/itsy-task-schema", isDirectory: true)
	let task = try #require(WorkspaceTaskDiscovery.configuredTasks(data: data, root: root, scope: .project).first)

	#expect(configuration.scope == .project)
	#expect(configuration.tasks[0].environment == ["MODE": "debug"])
	#expect(configuration.tasks[0].presentation == WorkspaceTaskPresentation(reveal: .always, focus: true, dedicated: true, showResolvedCommand: true))
	#expect(task.id == "project:build.app")
	#expect(task.workingDirectory == root.appendingPathComponent("App", isDirectory: true).standardizedFileURL)
	#expect(task.inputs == [WorkspaceTaskInput(id: "target", prompt: "Target", defaultValue: "app")])
	#expect(task.problemMatchers == ["swiftc"])
	#expect(task.watch == WorkspaceTaskWatch(paths: ["Sources"], debounceMillis: 75, policy: .onChange))

	let global = try WorkspaceTaskConfigurationParser.parse(data: data, scope: .global)
	#expect(global.scope == .global)
}

@Test func workspaceTaskConfigurationRejectsUnsafeValuesWithExactDiagnostics() {
	#expect(throws: WorkspaceTaskConfigurationError.unsupportedVersion(2)) {
		_ = try WorkspaceTaskConfigurationParser.parse(data: Data(#"{"version":2,"tasks":[]}"#.utf8), scope: .project)
	}
	#expect(throws: WorkspaceTaskConfigurationError.invalidField(taskID: "build", field: "cwd", reason: "must be a relative path without traversal")) {
		_ = try WorkspaceTaskConfigurationParser.parse(data: Data(#"{"version":1,"tasks":[{"id":"build","command":"swift","cwd":"../outside"}]}"#.utf8), scope: .project)
	}
	#expect(throws: WorkspaceTaskConfigurationError.invalidField(taskID: "build", field: "env.BAD-NAME", reason: "must be a POSIX environment key")) {
		_ = try WorkspaceTaskConfigurationParser.parse(data: Data(#"{"version":1,"tasks":[{"id":"build","command":"swift","env":{"BAD-NAME":"1"}}]}"#.utf8), scope: .project)
	}
	#expect(throws: WorkspaceTaskConfigurationError.invalidField(taskID: "build", field: "arguments", reason: "must not contain control characters")) {
		_ = try WorkspaceTaskConfigurationParser.parse(data: Data(#"{"version":1,"tasks":[{"id":"build","command":"swift","arguments":["bad\nvalue"]}]}"#.utf8), scope: .project)
	}
	#expect(throws: WorkspaceTaskConfigurationError.invalidField(taskID: "build", field: "inputs", reason: "contains duplicate input id")) {
		_ = try WorkspaceTaskConfigurationParser.parse(data: Data(#"{"version":1,"tasks":[{"id":"build","command":"swift","inputs":[{"id":"target","prompt":"Target"},{"id":"target","prompt":"Target again"}]}]}"#.utf8), scope: .project)
	}
}

@Test func legacyWorkspaceTasksRemainRepresentableByTheTypedTaskModel() throws {
	let legacy = WorkspaceTask(
		id: "make:build",
		label: "make build",
		source: .makefile,
		command: "make",
		arguments: ["build"],
		workingDirectory: URL(fileURLWithPath: "/tmp/project"),
		dependsOn: ["prepare"],
		isBackground: true,
		watch: WorkspaceTaskWatch(paths: ["Sources"], debounceMillis: 50)
	)

	#expect(legacy.command == "make")
	#expect(legacy.arguments == ["build"])
	#expect(legacy.workingDirectory == URL(fileURLWithPath: "/tmp/project"))
	#expect(legacy.dependsOn == ["prepare"])
	#expect(legacy.isBackground)
	#expect(legacy.watch == WorkspaceTaskWatch(paths: ["Sources"], debounceMillis: 50))
	#expect(legacy.watch?.policy == .manual)
	#expect(legacy.environment.isEmpty)
	#expect(legacy.inputs.isEmpty)
	#expect(legacy.problemMatchers.isEmpty)
}

@Test func workspaceTaskRunnerAppliesTypedTaskEnvironment() throws {
	let task = WorkspaceTask(
		id: "project:environment",
		label: "environment",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf %s \"$ITSY_SCHEMA_TEST_VALUE\""],
		environment: ["ITSY_SCHEMA_TEST_VALUE": "configured"]
	)
	let result = try WorkspaceTaskRunner().run(task, root: URL(fileURLWithPath: NSTemporaryDirectory()))

	#expect(result.succeeded)
	#expect(result.stdout == "configured")
}
