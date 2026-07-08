import AppKit
import Dispatch
import Foundation
import ItsyEditor

@MainActor final class TaskCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let problemsCoordinator: ProblemsCoordinator
	private var taskPanel: NSPanel?
	private var taskStatusLabel: NSTextField?
	private var taskTableView: NSTableView?
	private var taskOutputTextView: NSTextView?
	private var cancelButton: NSButton?
	private var workspaceTasks: [WorkspaceTask] = []
	private var taskRunGeneration = 0
	private var activeTaskHandle: WorkspaceTaskHandle?
	private var taskWatcher: WorkspaceTaskWatcher?

	init(problemsCoordinator: ProblemsCoordinator) {
		self.problemsCoordinator = problemsCoordinator
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
		if taskPanel?.isVisible == true {
			closeTasks()
			return
		}
		showTasks(relativeTo: hostWindow)
	}

	private func closeTasks() {
		taskPanel?.close()
	}

	private func showTasks(relativeTo hostWindow: NSWindow?) {
		let panel = makeTaskPanelIfNeeded()
		centerTaskPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshTasks(nil)
	}

	private func makeTaskPanelIfNeeded() -> NSPanel {
		if let panel = taskPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 720, height: 520),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Tasks")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureTaskView(contentView)
		taskPanel = panel
		return panel
	}

	private func configureTaskView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshTasks(_:)))
		let runButton = NSButton(title: L10n.string("Run"), target: self, action: #selector(runSelectedTask(_:)))
		let cancelButton = NSButton(title: L10n.string("Cancel"), target: self, action: #selector(cancelRunningTask(_:)))
		cancelButton.isEnabled = false
		let buttonStack = NSStackView(views: [refreshButton, runButton, cancelButton])
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

	@objc private func cancelRunningTask(_ sender: Any?) {
		taskRunGeneration += 1
		taskWatcher?.stop()
		taskWatcher = nil
		activeTaskHandle?.cancel()
		activeTaskHandle = nil
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
		activeTaskHandle?.cancel()
		activeTaskHandle = nil
		let plan: [WorkspaceTask]
		do {
			plan = try WorkspaceTaskPlanner.executionPlan(for: task, in: workspaceTasks)
		} catch {
			if !preserveWatch {
				taskWatcher?.stop()
				taskWatcher = nil
			}
			cancelButton?.isEnabled = false
			applyTaskResult(.failure(error))
			return
		}
		if !preserveWatch {
			installWatchIfNeeded(for: task, root: root)
		}
		cancelButton?.isEnabled = true
		taskStatusLabel?.textColor = .secondaryLabelColor
		taskStatusLabel?.stringValue = L10n.string("Running \(task.label)")
		if preserveWatch {
			taskOutputTextView?.string += "\n$ \(task.commandLine)\n"
		} else {
			taskOutputTextView?.string = "$ \(task.commandLine)\n"
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
		let task = plan[index]
		do {
			activeTaskHandle = try WorkspaceTaskRunner().start(
				task,
				root: root,
				onOutput: { [weak self] output in
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
						self.cancelButton?.isEnabled = self.taskWatcher != nil
						self.applyTaskResult(.success(WorkspaceTaskResult(
							task: rootTask,
							exitStatus: result.exitStatus,
							stdout: nextStdout,
							stderr: nextStderr
						)), root: root)
						if self.taskWatcher != nil {
							self.taskStatusLabel?.stringValue = L10n.string("Watching \(rootTask.label)")
						}
					}
				}
			)
		} catch {
			activeTaskHandle = nil
			cancelButton?.isEnabled = taskWatcher != nil
			applyTaskResult(.failure(error))
		}
	}

	private func installWatchIfNeeded(for task: WorkspaceTask, root: URL) {
		taskWatcher?.stop()
		taskWatcher = nil
		guard let watch = task.watch, !watch.paths.isEmpty else {
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

	private func applyTaskResult(_ result: Result<WorkspaceTaskResult, Error>) {
		applyTaskResult(result, root: ItsyWorkspaceController.currentRootURL)
	}

	private func applyTaskResult(_ result: Result<WorkspaceTaskResult, Error>, root: URL?) {
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
				let matchers = WorkspaceProblemMatcherDiscovery.discover(root: root)
				problemsCoordinator.setProblems(WorkspaceProblemParser.parse(taskResult.stdout + "\n" + taskResult.stderr, root: root, matchers: matchers))
			}
		case let .failure(error):
			taskStatusLabel?.textColor = .systemRed
			taskStatusLabel?.stringValue = String(describing: error)
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
	#endif
}
