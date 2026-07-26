import Foundation
import ItsyEditor
import Testing

@Test func workspaceTaskExpansionPreservesArgumentBoundariesForContextValues() throws {
	let root = URL(fileURLWithPath: "/tmp/Itsy Workspace", isDirectory: true)
	let file = root.appendingPathComponent("Sources/File Name.swift")
	let task = WorkspaceTask(
		id: "project:context",
		label: "context",
		source: .workspaceTaskFile,
		command: "/usr/bin/printf",
		arguments: ["%s", "${workspaceFolder}", "${file}", "${fileDirname}", "${relativeFile}", "${selectedText}", "${env:MODE}", "${input:target}"],
		environment: ["OUTPUT": "${input:target}"],
		inputs: [WorkspaceTaskInput(id: "target", prompt: "Target")]
	)
	let expansion = try WorkspaceTaskExpander.expand(
		task,
		context: WorkspaceTaskExpansionContext(
			workspaceRoot: root,
			fileURL: file,
			selectedText: "two words",
			environment: ["MODE": "debug mode"]
		),
		inputResolver: { _ in .value("app target") }
	)

	#expect(expansion.task.arguments == [
		"%s",
		"/tmp/Itsy Workspace",
		"/tmp/Itsy Workspace/Sources/File Name.swift",
		"/tmp/Itsy Workspace/Sources",
		"Sources/File Name.swift",
		"two words",
		"debug mode",
		"app target",
	])
	#expect(expansion.task.environment == ["OUTPUT": "app target"])
	#expect(expansion.previewCommandLine.contains("'/tmp/Itsy Workspace'"))
	#expect(expansion.previewCommandLine.contains("'app target'"))
}

@Test func workspaceTaskExpansionRejectsMissingAndCancelledValues() {
	let root = URL(fileURLWithPath: "/tmp/itsy", isDirectory: true)
	let noFileTask = WorkspaceTask(id: "missing-file", label: "missing file", source: .workspaceTaskFile, command: "/usr/bin/true", arguments: ["${file}"])
	#expect(throws: WorkspaceTaskExpansionError.missingContextValue("file")) {
		_ = try WorkspaceTaskExpander.expand(noFileTask, context: WorkspaceTaskExpansionContext(workspaceRoot: root))
	}

	let noEnvironmentTask = WorkspaceTask(id: "missing-env", label: "missing env", source: .workspaceTaskFile, command: "/usr/bin/true", arguments: ["${env:ABSENT}"])
	#expect(throws: WorkspaceTaskExpansionError.missingEnvironment("ABSENT")) {
		_ = try WorkspaceTaskExpander.expand(noEnvironmentTask, context: WorkspaceTaskExpansionContext(workspaceRoot: root, environment: [:]))
	}

	let input = WorkspaceTaskInput(id: "name", prompt: "Name")
	let cancelledTask = WorkspaceTask(id: "cancelled", label: "cancelled", source: .workspaceTaskFile, command: "/usr/bin/true", arguments: ["${input:name}"], inputs: [input])
	#expect(throws: WorkspaceTaskExpansionError.inputCancelled("name")) {
		_ = try WorkspaceTaskExpander.expand(cancelledTask, context: WorkspaceTaskExpansionContext(workspaceRoot: root), inputResolver: { _ in .cancelled })
	}
}

@Test func workspaceTaskExpansionTreatsMaliciousLookingInputAsOneLiteralArgument() throws {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-task-expansion-\(UUID().uuidString)", isDirectory: true)
	let marker = root.appendingPathComponent("executed")
	let malicious = "$(touch \(marker.path)); ; && |"
	try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	let task = WorkspaceTask(
		id: "literal",
		label: "literal",
		source: .workspaceTaskFile,
		command: "/usr/bin/printf",
		arguments: ["%s", "${input:value}"],
		inputs: [WorkspaceTaskInput(id: "value", prompt: "Value", secret: true)]
	)
	defer {
		try? FileManager.default.removeItem(at: root)
	}
	let expansion = try WorkspaceTaskExpander.expand(
		task,
		context: WorkspaceTaskExpansionContext(workspaceRoot: root),
		inputResolver: { _ in .value(malicious) }
	)
	let result = try WorkspaceTaskRunner().run(expansion.task, root: root)

	#expect(result.succeeded)
	#expect(result.stdout == malicious)
	#expect(!FileManager.default.fileExists(atPath: marker.path))
	#expect(expansion.previewCommandLine.contains("••••"))
	#expect(!expansion.previewCommandLine.contains(malicious))
}
