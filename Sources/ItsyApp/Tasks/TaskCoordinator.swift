import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyWorkbenchLayout

@MainActor final class TaskCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let problemsCoordinator: ProblemsCoordinator
	private let activeDocumentProvider: () -> NSDocument?
	private var taskSurface: WorkbenchPanelSurface?
	private var taskStatusLabel: NSTextField?
	private var taskTableView: NSTableView?
	private var taskOutputTextView: NSTextView?
	private var cancelButton: NSButton?
	private var workspaceTasks: [WorkspaceTask] = []
	private var taskRunGeneration = 0
	private var activeTaskHandle: WorkspaceTaskHandle?
	private var backgroundTaskHandles: [WorkspaceTaskHandle] = []
	private var taskWatcher: WorkspaceTaskWatcher?

	init(
		problemsCoordinator: ProblemsCoordinator,
		activeDocumentProvider: @escaping () -> NSDocument? = { nil }
	) {
		self.problemsCoordinator = problemsCoordinator
		self.activeDocumentProvider = activeDocumentProvider
	}

	@objc func showTasks(_ sender: Any?) {
		toggleTasks(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc func refreshTasks(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setTasks([], status: L10n.string("Open a folder first"), output: "", isError: true)
			return
		}
		let tasks = WorkspaceTaskDiscovery.discover(root: root)
		setTasks(tasks, status: L10n.string("\(tasks.count) tasks"), output: taskOutputTextView?.string ?? "", isError: false)
	}

	private func toggleTasks(relativeTo hostWindow: NSWindow?) {
		if taskSurface?.isVisible == true {
			closeTasks()
			return
		}
		showTasks(relativeTo: hostWindow)
	}

	private func closeTasks() {
		taskSurface?.close()
	}

	private func showTasks(relativeTo hostWindow: NSWindow?) {
		let surface = makeTaskSurfaceIfNeeded()
		centerTaskPanel(surface.panel, relativeTo: hostWindow)
		surface.show()
		refreshTasks(nil)
	}

	private func makeTaskSurfaceIfNeeded() -> WorkbenchPanelSurface {
		if let surface = taskSurface {
			return surface
		}
		let surface = WorkbenchPanelSurface(id: .tasks, title: L10n.string("Tasks"), size: NSSize(width: 720, height: 520))
		configureTaskView(surface.contentView)
		taskSurface = surface
		return surface
	}

	private func configureTaskView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshTasks(_:)))
		let runButton = NSButton(title: L10n.string("Run"), target: self, action: #selector(runSelectedTask(_:)))
		let dryRunButton = NSButton(title: L10n.string("Dry Run"), target: self, action: #selector(dryRunSelectedTask(_:)))
		let cancelButton = NSButton(title: L10n.string("Cancel"), target: self, action: #selector(cancelRunningTask(_:)))
		cancelButton.isEnabled = false
		let buttonStack = NSStackView(views: [refreshButton, dryRunButton, runButton, cancelButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("task"))
		column.title = L10n.string("Tasks")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(runSelectedTask(_:))
		let taskScrollView = NSScrollView()
		taskScrollView.documentView = tableView
		taskScrollView.hasVerticalScroller = true
		taskScrollView.drawsBackground = false
		let outputTextView = NSTextView()
		outputTextView.isEditable = false
		outputTextView.isSelectable = true
		outputTextView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		outputTextView.string = ""
		let outputScrollView = NSScrollView()
		outputScrollView.documentView = outputTextView
		outputScrollView.hasVerticalScroller = true
		outputScrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		taskScrollView.translatesAutoresizingMaskIntoConstraints = false
		outputScrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(taskScrollView)
		contentView.addSubview(outputScrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			taskScrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			taskScrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			taskScrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			taskScrollView.heightAnchor.constraint(equalToConstant: 180),
			outputScrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			outputScrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			outputScrollView.topAnchor.constraint(equalTo: taskScrollView.bottomAnchor, constant: 1),
			outputScrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		taskStatusLabel = statusLabel
		taskTableView = tableView
		taskOutputTextView = outputTextView
		self.cancelButton = cancelButton
	}

	private func centerTaskPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(780, max(560, hostFrame.width - 100))
		let height = min(560, max(360, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func setTasks(_ tasks: [WorkspaceTask], status: String, output: String, isError: Bool) {
		workspaceTasks = tasks
		taskStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		taskStatusLabel?.stringValue = status
		taskOutputTextView?.string = output
		taskTableView?.reloadData()
		if !tasks.isEmpty {
			taskTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	@objc private func runSelectedTask(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL,
		      let task = selectedTask()
		else {
			return
		}
		startTask(task, root: root, preserveWatch: false)
	}

	@objc private func dryRunSelectedTask(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL, let task = selectedTask() else {
			return
		}
		do {
			let dryRun = try WorkspaceTaskDryRunInspector.inspect(task: task, in: workspaceTasks, context: taskExpansionContext(root: root))
			taskStatusLabel?.textColor = .secondaryLabelColor
			taskStatusLabel?.stringValue = L10n.string("Dry run · no command executed")
			taskOutputTextView?.string = dryRunOutput(dryRun)
		} catch {
			applyTaskResult(.failure(error), root: root, task: task)
		}
	}

	@objc private func cancelRunningTask(_ sender: Any?) {
		taskRunGeneration += 1
		taskWatcher?.stop()
		taskWatcher = nil
		cancelActiveTaskHandles()
		cancelButton?.isEnabled = false
		taskStatusLabel?.textColor = .secondaryLabelColor
		taskStatusLabel?.stringValue = L10n.string("Cancelled")
	}

	private func selectedTask() -> WorkspaceTask? {
		guard let tableView = taskTableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < workspaceTasks.count
		else {
			return nil
		}
		return workspaceTasks[tableView.selectedRow]
	}

	private func startTask(_ task: WorkspaceTask, root: URL, preserveWatch: Bool) {
		taskRunGeneration += 1
		let generation = taskRunGeneration
		cancelActiveTaskHandles()
		let plan: [WorkspaceTask]
		do {
			plan = try WorkspaceTaskPlanner.executionPlan(for: task, in: workspaceTasks)
		} catch {
			if !preserveWatch {
				taskWatcher?.stop()
				taskWatcher = nil
			}
			cancelButton?.isEnabled = false
			applyTaskResult(.failure(error), root: root, task: task)
			reportTaskHealth(task, root: root, lifecycle: .stopped, state: .degraded, lastError: String(describing: error), remediation: "Review the task configuration and retry.")
			return
		}
		if !preserveWatch {
			installWatchIfNeeded(for: task, root: root)
		}
		cancelButton?.isEnabled = true
		taskStatusLabel?.textColor = .secondaryLabelColor
		taskStatusLabel?.stringValue = L10n.string("Running \(task.label)")
		reportTaskHealth(task, root: root, lifecycle: .running, state: .healthy)
		if preserveWatch {
			taskOutputTextView?.string += "\n"
		} else {
			taskOutputTextView?.string = ""
		}
		startPlannedTask(plan: plan, index: 0, root: root, generation: generation, rootTask: task, stdout: "", stderr: "")
	}

	private func startPlannedTask(
		plan: [WorkspaceTask],
		index: Int,
		root: URL,
		generation: Int,
		rootTask: WorkspaceTask,
		stdout: String,
		stderr: String
	) {
		guard index < plan.count else {
			return
		}
		let sourceTask = plan[index]
		let expansion: WorkspaceTaskExpansion
		do {
			expansion = try WorkspaceTaskExpander.expand(
				sourceTask,
				context: taskExpansionContext(root: root),
				inputResolver: promptForTaskInput
			)
		} catch WorkspaceTaskExpansionError.inputCancelled {
			taskRunGeneration += 1
			taskWatcher?.stop()
			taskWatcher = nil
			cancelActiveTaskHandles()
			cancelButton?.isEnabled = false
			taskStatusLabel?.textColor = .secondaryLabelColor
			taskStatusLabel?.stringValue = L10n.string("Cancelled")
			reportTaskHealth(rootTask, root: root, lifecycle: .stopped, state: .healthy)
			return
		} catch {
			activeTaskHandle = nil
			cancelButton?.isEnabled = taskWatcher != nil || !backgroundTaskHandles.isEmpty
			applyTaskResult(.failure(error), root: root, task: rootTask)
			return
		}
		let task = expansion.task
		let commandLine = sourceTask.presentation.showResolvedCommand ? expansion.previewCommandLine : sourceTask.commandLine
		taskOutputTextView?.string += "$ \(commandLine)\n"
		let outputIdentifier = "\(root.standardizedFileURL.path):\(rootTask.id)"
		Task {
			await IntegrationOutputConsole.shared.append(service: .task, identifier: outputIdentifier, kind: .command, text: commandLine, errorReference: "task://\(outputIdentifier)")
		}
		do {
			activeTaskHandle = try WorkspaceTaskRunner().start(
				task,
				root: root,
				onOutput: { [weak self] output in
					Task {
						await IntegrationOutputConsole.shared.append(
							service: .task,
							identifier: outputIdentifier,
							kind: output.kind == .stderr ? .standardError : .standardOutput,
							text: output.text,
							errorReference: output.kind == .stderr ? "task://\(outputIdentifier)" : nil
						)
					}
					Task { @MainActor [weak self] in
						guard let self, self.taskRunGeneration == generation else {
							return
						}
						self.taskOutputTextView?.string += output.text
					}
				},
				onFinish: { [weak self] result in
					Task { @MainActor [weak self] in
						guard let self, self.taskRunGeneration == generation else {
							return
						}
						if task.isBackground, result.wasReady, index + 1 < plan.count {
							self.backgroundTaskHandles.removeAll { !$0.isRunning }
							guard !result.succeeded else {
								return
							}
							self.taskRunGeneration += 1
							self.cancelActiveTaskHandles()
							self.cancelButton?.isEnabled = false
							self.applyTaskResult(.success(WorkspaceTaskResult(
								task: rootTask,
								exitStatus: result.exitStatus,
								stdout: stdout + result.stdout,
								stderr: stderr + result.stderr,
								wasCancelled: result.wasCancelled,
								wasReady: result.wasReady
							)), root: root)
							return
						}
						let nextStdout = stdout + result.stdout
						let nextStderr = stderr + result.stderr
						if result.succeeded, index + 1 < plan.count {
							self.startPlannedTask(
								plan: plan,
								index: index + 1,
								root: root,
								generation: generation,
								rootTask: rootTask,
								stdout: nextStdout,
								stderr: nextStderr
							)
							return
						}
						self.activeTaskHandle = nil
						self.cancelButton?.isEnabled = self.taskWatcher != nil || !self.backgroundTaskHandles.isEmpty
						self.applyTaskResult(.success(WorkspaceTaskResult(
							task: rootTask,
							exitStatus: result.exitStatus,
							stdout: nextStdout,
							stderr: nextStderr
						)), root: root)
						if self.taskWatcher != nil {
							self.taskStatusLabel?.stringValue = L10n.string("Watching \(rootTask.label)")
						} else if !self.backgroundTaskHandles.isEmpty {
							self.taskStatusLabel?.stringValue = L10n.string("Background task running")
						}
					}
				},
				onReady: { [weak self] handle in
					Task { @MainActor [weak self] in
						guard let self,
						      self.taskRunGeneration == generation,
						      task.isBackground,
						      index + 1 < plan.count,
						      !self.backgroundTaskHandles.contains(where: { $0 === handle })
						else {
							return
						}
						if handle.isRunning {
							self.backgroundTaskHandles.append(handle)
						}
						if self.activeTaskHandle === handle {
							self.activeTaskHandle = nil
						}
						self.startPlannedTask(
							plan: plan,
							index: index + 1,
							root: root,
							generation: generation,
							rootTask: rootTask,
							stdout: stdout,
							stderr: stderr
						)
					}
				}
			)
		} catch {
			activeTaskHandle = nil
			cancelButton?.isEnabled = taskWatcher != nil
			applyTaskResult(.failure(error), root: root, task: rootTask)
		}
	}

	private func cancelActiveTaskHandles() {
		activeTaskHandle?.cancel()
		activeTaskHandle = nil
		for handle in backgroundTaskHandles {
			handle.cancel()
		}
		backgroundTaskHandles.removeAll()
	}

	private func taskExpansionContext(root: URL) -> WorkspaceTaskExpansionContext {
		guard let document = activeDocumentProvider() as? ItsyDocument else {
			return WorkspaceTaskExpansionContext(workspaceRoot: root)
		}
		let selection = document.editor.selections.primary.range
		let length = document.editor.textStorage.length
		let boundedSelection = max(0, min(selection.lowerBound, length)) ..< max(0, min(selection.upperBound, length))
		return WorkspaceTaskExpansionContext(
			workspaceRoot: root,
			fileURL: document.fileURL,
			selectedText: document.editor.textStorage.substring(boundedSelection)
		)
	}

	private func promptForTaskInput(_ input: WorkspaceTaskInput) -> WorkspaceTaskInputResolution {
		let alert = NSAlert()
		alert.messageText = input.prompt
		alert.informativeText = L10n.string("Task input: \(input.id)")
		alert.addButton(withTitle: L10n.string("Continue"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		let field: NSTextField = input.secret ? NSSecureTextField() : NSTextField()
		field.stringValue = input.defaultValue ?? ""
		field.placeholderString = input.prompt
		field.frame = NSRect(x: 0, y: 0, width: 320, height: 24)
		alert.accessoryView = field
		guard alert.runModal() == .alertFirstButtonReturn else {
			return .cancelled
		}
		return .value(field.stringValue)
	}

	private func installWatchIfNeeded(for task: WorkspaceTask, root: URL) {
		taskWatcher?.stop()
		taskWatcher = nil
		guard let watch = task.watch, watch.policy == .onChange, !watch.paths.isEmpty else {
			return
		}
		let watcher = WorkspaceTaskWatcher(root: root, watch: watch) { [weak self] in
			Task { @MainActor [weak self] in
				guard let self else {
					return
				}
				self.startTask(task, root: root, preserveWatch: true)
			}
		}
		taskWatcher = watcher
		watcher.start()
	}

	private func dryRunOutput(_ dryRun: WorkspaceTaskDryRun) -> String {
		([L10n.string("Dry run — no command executed")] + dryRun.steps.flatMap { step -> [String] in
			var lines = ["$ \(step.commandLine)", "cwd: \(step.workingDirectory.path)"]
			if !step.environmentKeys.isEmpty {
				lines.append("env keys: \(step.environmentKeys.joined(separator: ", "))")
			}
			if let watchPolicy = step.watchPolicy {
				lines.append("file-event policy: \(watchPolicy.rawValue)")
			}
			if !step.unresolvedInputs.isEmpty {
				lines.append("inputs required at run time: \(step.unresolvedInputs.joined(separator: ", "))")
			}
			return lines
		}).joined(separator: "\n")
	}

	private func applyTaskResult(_ result: Result<WorkspaceTaskResult, Error>, root: URL?, task: WorkspaceTask? = nil) {
		switch result {
		case let .success(taskResult):
			taskStatusLabel?.textColor = taskResult.succeeded ? .secondaryLabelColor : .systemRed
			taskStatusLabel?.stringValue = L10n.string("\(taskResult.task.label) exited \(taskResult.exitStatus)")
			taskOutputTextView?.string = [
				"$ \(taskResult.task.commandLine)",
				taskResult.stdout,
				taskResult.stderr,
			].filter { !$0.isEmpty }.joined(separator: "\n")
			if let root {
				let matchers = WorkspaceProblemMatcherDiscovery.matchers(for: taskResult.task, root: root)
				problemsCoordinator.setProblems(
					WorkspaceProblemParser.parse(taskResult.stdout + "\n" + taskResult.stderr, root: root, matchers: matchers),
					sourceID: "task:\(root.standardizedFileURL.path):\(taskResult.task.id)"
				)
				reportTaskHealth(
					taskResult.task,
					root: root,
					lifecycle: .stopped,
					state: taskResult.succeeded ? .healthy : .degraded,
					lastError: taskResult.succeeded ? nil : taskResult.stderr,
					remediation: taskResult.succeeded ? nil : "Review the task output and retry."
				)
			}
		case let .failure(error):
			taskStatusLabel?.textColor = .systemRed
			taskStatusLabel?.stringValue = String(describing: error)
			if let root, let task {
				problemsCoordinator.setProblems(
					WorkspaceProblemSnapshot(root: root, problems: []),
					sourceID: "task:\(root.standardizedFileURL.path):\(task.id)"
				)
				reportTaskHealth(task, root: root, lifecycle: .stopped, state: .degraded, lastError: String(describing: error), remediation: "Review the task configuration and retry.")
			}
		}
	}

	private func reportTaskHealth(_ task: WorkspaceTask, root: URL, lifecycle: IntegrationLifecycle, state: IntegrationHealthState, lastError: String? = nil, remediation: String? = nil) {
		let rootPath = root.standardizedFileURL.path
		Task {
			await IntegrationHealthStore.shared.report(
				service: .task,
				identifier: "\(rootPath):\(task.id)",
				lifecycle: lifecycle,
				state: state,
				lastError: lastError,
				remediation: remediation,
				detailLogReference: "task://\(rootPath)/\(task.id)"
			)
		}
	}

	private func taskTitle(_ task: WorkspaceTask) -> String {
		"\(task.label)  [\(task.source.rawValue)]"
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === taskTableView ? workspaceTasks.count : 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === taskTableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("TaskCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .systemFont(ofSize: 12)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = taskTitle(workspaceTasks[row])
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}

	#if DEBUG
	func applyTaskResultForTesting(_ result: WorkspaceTaskResult, root: URL) {
		applyTaskResult(.success(result), root: root)
	}

	func applyTaskFailureForTesting(_ error: Error, task: WorkspaceTask, root: URL) {
		applyTaskResult(.failure(error), root: root, task: task)
	}
	#endif
}
