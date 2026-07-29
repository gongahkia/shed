import Foundation
import ItsyEditor
import Testing

@Test func taskDryRunExpandsDependenciesWithoutStartingCommands() throws {
	let root = URL(fileURLWithPath: "/tmp/itsy-dry-run", isDirectory: true)
	let prepare = WorkspaceTask(
		id: "project:prepare",
		label: "Prepare",
		source: .workspaceTaskFile,
		command: "/bin/echo",
		arguments: ["${env:SECRET}"],
		environment: ["TOKEN": "${env:SECRET}"]
	)
	let build = WorkspaceTask(
		id: "project:build",
		label: "Build",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "touch ${workspace}/must-not-exist ${input:target}"],
		inputs: [WorkspaceTaskInput(id: "target", prompt: "Target", secret: true)],
		dependsOn: ["prepare"],
		watch: WorkspaceTaskWatch(paths: ["Sources"], policy: .onChange)
	)
	let context = WorkspaceTaskExpansionContext(workspaceRoot: root, environment: ["SECRET": "not-for-preview"])

	let dryRun = try WorkspaceTaskDryRunInspector.inspect(task: build, in: [prepare, build], context: context)

	#expect(dryRun.steps.map(\.taskID) == ["project:prepare", "project:build"])
	#expect(dryRun.steps[0].commandLine.contains("<env:SECRET>"))
	#expect(dryRun.steps[0].environmentKeys == ["TOKEN"])
	#expect(dryRun.steps[1].commandLine.contains("••••"))
	#expect(dryRun.steps[1].unresolvedInputs == ["target"])
	#expect(dryRun.steps[1].watchPolicy == .onChange)
	#expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("must-not-exist").path))
}

@Test func taskWatchDefaultsToManualPolicy() {
	#expect(WorkspaceTaskWatch(paths: ["Sources"]).policy == .manual)
}
