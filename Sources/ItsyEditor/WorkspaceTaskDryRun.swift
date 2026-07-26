import Foundation

public struct WorkspaceTaskDryRunStep: Equatable, Sendable {
	public var taskID: String
	public var label: String
	public var commandLine: String
	public var workingDirectory: URL
	public var environmentKeys: [String]
	public var watchPolicy: WorkspaceTaskWatchPolicy?
	public var unresolvedInputs: [String]

	public init(taskID: String, label: String, commandLine: String, workingDirectory: URL, environmentKeys: [String], watchPolicy: WorkspaceTaskWatchPolicy?, unresolvedInputs: [String]) {
		self.taskID = taskID
		self.label = label
		self.commandLine = commandLine
		self.workingDirectory = workingDirectory
		self.environmentKeys = environmentKeys
		self.watchPolicy = watchPolicy
		self.unresolvedInputs = unresolvedInputs
	}
}

public struct WorkspaceTaskDryRun: Equatable, Sendable {
	public var steps: [WorkspaceTaskDryRunStep]

	public init(steps: [WorkspaceTaskDryRunStep]) {
		self.steps = steps
	}
}

public enum WorkspaceTaskDryRunInspector {
	public static func inspect(task: WorkspaceTask, in tasks: [WorkspaceTask], context: WorkspaceTaskExpansionContext) throws -> WorkspaceTaskDryRun {
		let plan = try WorkspaceTaskPlanner.executionPlan(for: task, in: tasks)
		let safeEnvironment = Dictionary(uniqueKeysWithValues: context.environment.keys.map { ($0, "<env:\($0)>") })
		let steps = try plan.map { task in
			var previewContext = context
			previewContext.environment = safeEnvironment
			let unresolvedInputs = task.inputs.filter { $0.defaultValue == nil }.map(\.id).sorted()
			for inputID in unresolvedInputs {
				previewContext.inputValues[inputID] = "<input:\(inputID)>"
			}
			let expansion = try WorkspaceTaskExpander.expand(task, context: previewContext) { input in
				.value(input.defaultValue ?? "<input:\(input.id)>")
			}
			return WorkspaceTaskDryRunStep(
				taskID: task.id,
				label: task.label,
				commandLine: expansion.previewCommandLine,
				workingDirectory: expansion.task.workingDirectory ?? previewContext.workspaceRoot,
				environmentKeys: expansion.task.environment.keys.sorted(),
				watchPolicy: task.watch?.policy,
				unresolvedInputs: unresolvedInputs
			)
		}
		return WorkspaceTaskDryRun(steps: steps)
	}
}
