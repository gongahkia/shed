import AppKit
import Foundation
import ItsyConfig
import ItsyDebugger

@MainActor final class DebuggerCoordinator: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
	private let documentController: ItsyDocumentController
	private lazy var launchCoordinator = DebugLaunchCoordinator(
		onSessionStarted: { [weak self] session in
			self?.debugSessionDidStart(session)
		},
		onSessionTerminated: { [weak self] status in
			self?.debugSessionDidTerminate(status)
		}
	)
	private lazy var variablesCoordinator = DebugVariablesCoordinator { [weak self] in
		self?.activeSession
	}
	private lazy var watchesCoordinator = DebugWatchesCoordinator { [weak self] in
		self?.activeSession
	}
	private lazy var consoleCoordinator = DebugConsoleCoordinator { [weak self] in
		self?.activeSession
	}
	private var activeSession: DebugAppSession?
	private var callStackPanel: NSPanel?
	private var callStackContentView: NSView?
	private var callStackEmbeddingConstraints: [NSLayoutConstraint] = []
	private var embeddedCallStackVisible = false
	private let settingsProvider: () -> ItsySettings.DebuggerSettings
	private let embeddedHostProvider: () -> NSView?
	private let setEmbeddedDebuggerVisible: (Bool) -> Void
	private var callStackStatusLabel: NSTextField?
	private var callStackOutlineView: NSOutlineView?
	private var callStackNodes: [DebugCallStackThreadNode] = []
	private var reverseContinueButton: NSButton?
	private var stepBackButton: NSButton?
	private var restartButton: NSButton?
	private var callStackGeneration = 0

	init(
		documentController: ItsyDocumentController,
		settingsProvider: @escaping () -> ItsySettings.DebuggerSettings = { ItsySettings.DebuggerSettings() },
		embeddedHostProvider: @escaping () -> NSView? = { nil },
		setEmbeddedDebuggerVisible: @escaping (Bool) -> Void = { _ in }
	) {
		self.documentController = documentController
		self.settingsProvider = settingsProvider
		self.embeddedHostProvider = embeddedHostProvider
		self.setEmbeddedDebuggerVisible = setEmbeddedDebuggerVisible
		super.init()
	}

	@objc func showLaunchConfigPicker(_ sender: Any?) {
		launchCoordinator.showLaunchConfigPicker(sender)
	}

	@objc func showCallStack(_ sender: Any?) {
		toggleCallStack(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc func showVariables(_ sender: Any?) {
		variablesCoordinator.showVariables(sender)
	}

	@objc func showWatches(_ sender: Any?) {
		watchesCoordinator.showWatches(sender)
	}

	@objc func showConsole(_ sender: Any?) {
		consoleCoordinator.showConsole(sender)
	}

	@objc func continueDebug(_ sender: Any?) {
		runControl(.continueExecution)
	}

	@objc func stepOverDebug(_ sender: Any?) {
		runControl(.next)
	}

	@objc func stepInDebug(_ sender: Any?) {
		runControl(.stepIn)
	}

	@objc func stepOutDebug(_ sender: Any?) {
		runControl(.stepOut)
	}

	@objc func stepBackDebug(_ sender: Any?) {
		runControl(.stepBack)
	}

	@objc func reverseContinueDebug(_ sender: Any?) {
		runControl(.reverseContinue)
	}

	@objc func pauseDebug(_ sender: Any?) {
		runControl(.pause)
	}

	@objc func restartDebug(_ sender: Any?) {
		runControl(.restart)
	}

	@objc func stopDebug(_ sender: Any?) {
		runControl(.stop)
	}

	func terminate() {
		callStackGeneration += 1
		activeSession = nil
		launchCoordinator.terminate()
		updateDebugControls()
		variablesCoordinator.clear()
		watchesCoordinator.clear()
		consoleCoordinator.clear()
	}

	func applyDebuggerSettings(_ settings: ItsySettings.DebuggerSettings) {
		if settings.presentation == .window, embeddedCallStackVisible {
			embeddedCallStackVisible = false
			setEmbeddedDebuggerVisible(false)
			detachEmbeddedCallStackContent()
		}
	}

	private func debugSessionDidStart(_ session: DebugAppSession) {
		activeSession = session
		updateDebugControls()
		if callStackPanel?.isVisible == true || embeddedCallStackVisible {
			refreshCallStack(nil)
		}
		variablesCoordinator.refreshIfVisible()
		watchesCoordinator.sessionDidStart(session)
		consoleCoordinator.sessionDidStart(session)
	}

	private func makeCallStackPanelIfNeeded() -> NSPanel {
		let panel: NSPanel
		if let callStackPanel {
			panel = callStackPanel
		} else {
			panel = NSPanel(
				contentRect: NSRect(x: 0, y: 0, width: 560, height: 460),
				styleMask: [.titled, .closable, .resizable, .utilityWindow],
				backing: .buffered,
				defer: false
			)
			panel.title = L10n.string("Debugger")
			panel.isReleasedWhenClosed = false
			callStackPanel = panel
		}
		let contentView = makeCallStackContentViewIfNeeded()
		detachEmbeddedCallStackContent()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		return panel
	}

	private func toggleCallStack(relativeTo hostWindow: NSWindow?) {
		switch settingsProvider().presentation {
		case .sidebar:
			toggleEmbeddedCallStack(relativeTo: hostWindow)
		case .window:
			if callStackPanel?.isVisible == true {
				callStackPanel?.close()
				return
			}
			showDetachedCallStack(relativeTo: hostWindow)
		}
	}

	private func showDetachedCallStack(relativeTo hostWindow: NSWindow?) {
		embeddedCallStackVisible = false
		setEmbeddedDebuggerVisible(false)
		let panel = makeCallStackPanelIfNeeded()
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshCallStack(nil)
	}

	private func toggleEmbeddedCallStack(relativeTo hostWindow: NSWindow?) {
		guard let host = embeddedHostProvider() else {
			showDetachedCallStack(relativeTo: hostWindow)
			return
		}
		if embeddedCallStackVisible, callStackContentView?.superview === host {
			embeddedCallStackVisible = false
			setEmbeddedDebuggerVisible(false)
			return
		}
		showEmbeddedCallStack(relativeTo: hostWindow)
	}

	private func showEmbeddedCallStack(relativeTo hostWindow: NSWindow?) {
		guard let host = embeddedHostProvider() else {
			showDetachedCallStack(relativeTo: hostWindow)
			return
		}
		let contentView = makeCallStackContentViewIfNeeded()
		callStackPanel?.orderOut(nil)
		embedCallStackContent(contentView, in: host)
		embeddedCallStackVisible = true
		setEmbeddedDebuggerVisible(true)
		refreshCallStack(nil)
	}

	private func makeCallStackContentViewIfNeeded() -> NSView {
		if let callStackContentView {
			return callStackContentView
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 560, height: 460))
		configureCallStackView(contentView)
		callStackContentView = contentView
		return contentView
	}

	private func embedCallStackContent(_ contentView: NSView, in host: NSView) {
		detachEmbeddedCallStackContent()
		contentView.removeFromSuperview()
		contentView.translatesAutoresizingMaskIntoConstraints = false
		host.addSubview(contentView)
		callStackEmbeddingConstraints = [
			contentView.leadingAnchor.constraint(equalTo: host.leadingAnchor),
			contentView.trailingAnchor.constraint(equalTo: host.trailingAnchor),
			contentView.topAnchor.constraint(equalTo: host.topAnchor),
			contentView.bottomAnchor.constraint(equalTo: host.bottomAnchor),
		]
		NSLayoutConstraint.activate(callStackEmbeddingConstraints)
	}

	private func detachEmbeddedCallStackContent() {
		NSLayoutConstraint.deactivate(callStackEmbeddingConstraints)
		callStackEmbeddingConstraints = []
	}

	private func configureCallStackView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let continueButton = NSButton(title: L10n.string("Continue"), target: self, action: #selector(continueDebug(_:)))
		let overButton = NSButton(title: L10n.string("Over"), target: self, action: #selector(stepOverDebug(_:)))
		let inButton = NSButton(title: L10n.string("In"), target: self, action: #selector(stepInDebug(_:)))
		let outButton = NSButton(title: L10n.string("Out"), target: self, action: #selector(stepOutDebug(_:)))
		let reverseButton = NSButton(title: L10n.string("Reverse"), target: self, action: #selector(reverseContinueDebug(_:)))
		let backButton = NSButton(title: L10n.string("Back"), target: self, action: #selector(stepBackDebug(_:)))
		let pauseButton = NSButton(title: L10n.string("Pause"), target: self, action: #selector(pauseDebug(_:)))
		let restartButton = NSButton(title: L10n.string("Restart"), target: self, action: #selector(restartDebug(_:)))
		let stopButton = NSButton(title: L10n.string("Stop"), target: self, action: #selector(stopDebug(_:)))
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshCallStack(_:)))
		reverseContinueButton = reverseButton
		stepBackButton = backButton
		self.restartButton = restartButton
		updateDebugControls()
		let primaryControls = NSStackView(views: [continueButton, overButton, inButton, outButton, pauseButton])
		primaryControls.orientation = .horizontal
		primaryControls.spacing = 6
		let secondaryControls = NSStackView(views: [reverseButton, backButton, restartButton, stopButton, refreshButton])
		secondaryControls.orientation = .horizontal
		secondaryControls.spacing = 6
		let buttonStack = NSStackView(views: [primaryControls, secondaryControls])
		buttonStack.orientation = .vertical
		buttonStack.alignment = .leading
		buttonStack.spacing = 6
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .vertical
		header.alignment = .leading
		header.spacing = 8
		let outlineView = NSOutlineView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("callStack"))
		column.title = L10n.string("Call Stack")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.rowSizeStyle = .small
		outlineView.dataSource = self
		outlineView.delegate = self
		let scrollView = NSScrollView()
		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		callStackStatusLabel = statusLabel
		callStackOutlineView = outlineView
	}

	private func runControl(_ control: DebugControl) {
		guard let session = activeSession else {
			setCallStackStatus(L10n.string("No active debug session"), isError: true)
			return
		}
		guard control.isAvailable(in: session) else {
			setCallStackStatus(DebugControlError.unsupportedReverseDebug.description, isError: true)
			return
		}
		setCallStackStatus(control.runningStatus, isError: false)
		Task(priority: .userInitiated) { [weak self] in
			do {
				try await self?.send(control, session: session)
				Task { @MainActor in
					guard let self else {
						return
					}
					if control == .stop {
						self.launchCoordinator.terminate()
						self.activeSession = nil
						self.variablesCoordinator.clear()
						self.watchesCoordinator.clear()
						self.consoleCoordinator.clear()
						self.updateDebugControls()
					}
					self.setCallStackStatus(control.doneStatus, isError: false)
				}
			} catch {
				Task { @MainActor in
					self?.setCallStackStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	private func send(_ control: DebugControl, session: DebugAppSession) async throws {
		switch control {
		case .continueExecution:
			try await session.debugSession.continueExecution(threadID: try await focusedThreadID(session))
		case .next:
			try await session.debugSession.next(threadID: try await focusedThreadID(session))
		case .stepIn:
			try await session.debugSession.stepIn(threadID: try await focusedThreadID(session))
		case .stepOut:
			try await session.debugSession.stepOut(threadID: try await focusedThreadID(session))
		case .stepBack:
			try await session.debugSession.stepBack(threadID: try await focusedThreadID(session))
		case .reverseContinue:
			try await session.debugSession.reverseContinue(threadID: try await focusedThreadID(session))
		case .pause:
			try await session.debugSession.pause(threadID: try await focusedThreadID(session))
		case .restart:
			try await session.debugSession.restart()
		case .stop:
			if session.supportsTerminate {
				try await session.debugSession.terminate()
			} else {
				try await session.debugSession.disconnect()
			}
		}
	}

	private func focusedThreadID(_ session: DebugAppSession) async throws -> Int {
		guard let threadID = await session.debugSession.focusedThreadID else {
			throw DebugControlError.missingFocusedThread
		}
		return threadID
	}

	private func updateDebugControls() {
		configureCapabilityButton(reverseContinueButton, enabled: activeSession?.supportsReverseContinue == true)
		configureCapabilityButton(stepBackButton, enabled: activeSession?.supportsStepBack == true)
		configureCapabilityButton(restartButton, enabled: activeSession?.supportsRestart == true)
	}

	private func configureCapabilityButton(_ button: NSButton?, enabled: Bool) {
		button?.isEnabled = enabled
		button?.isHidden = !enabled
	}

	private func debugSessionDidTerminate(_ status: Int32) {
		callStackGeneration += 1
		activeSession = nil
		updateDebugControls()
		variablesCoordinator.clear()
		watchesCoordinator.clear()
		consoleCoordinator.clear()
		setCallStackStatus(L10n.string("Debug adapter terminated (\(status))"), isError: true)
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(600, max(460, hostFrame.width - 140))
		let height = min(520, max(320, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	@objc private func refreshCallStack(_ sender: Any?) {
		guard let session = activeSession else {
			setCallStack([], status: L10n.string("No active debug session"), isError: true)
			return
		}
		callStackGeneration += 1
		let generation = callStackGeneration
		setCallStackStatus(L10n.string("Refreshing"), isError: false)
		Task(priority: .userInitiated) { [weak self] in
			do {
				let nodes = try await Self.loadCallStack(session: session)
				Task { @MainActor in
					guard let self, self.callStackGeneration == generation else {
						return
					}
					self.setCallStack(nodes, status: L10n.string("\(nodes.count) threads"), isError: false)
				}
			} catch {
				Task { @MainActor in
					guard let self, self.callStackGeneration == generation else {
						return
					}
					self.setCallStack([], status: String(describing: error), isError: true)
				}
			}
		}
	}

	private static func loadCallStack(session: DebugAppSession) async throws -> [DebugCallStackThreadNode] {
		let threads = try await session.debugSession.refreshThreads()
		var nodes: [DebugCallStackThreadNode] = []
		for thread in threads {
			let frames = try await session.debugSession.stackFrames(for: thread.id)
			nodes.append(DebugCallStackThreadNode(
				thread: thread,
				frames: frames.map { DebugCallStackFrameNode(threadID: thread.id, frame: $0) }
			))
		}
		return nodes
	}

	private func setCallStack(_ nodes: [DebugCallStackThreadNode], status: String, isError: Bool) {
		callStackNodes = nodes
		setCallStackStatus(status, isError: isError)
		callStackOutlineView?.reloadData()
		for node in nodes {
			callStackOutlineView?.expandItem(node)
		}
	}

	private func setCallStackStatus(_ status: String, isError: Bool) {
		callStackStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		callStackStatusLabel?.stringValue = status
	}

	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		if item == nil {
			return callStackNodes.count
		}
		if let node = item as? DebugCallStackThreadNode {
			return node.frames.count
		}
		return 0
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if item == nil {
			return callStackNodes[index]
		}
		if let node = item as? DebugCallStackThreadNode {
			return node.frames[index]
		}
		return DebugCallStackFrameNode(threadID: -1, frame: DebugStackFrame(id: -1, name: "", line: 0, column: 0))
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		(item as? DebugCallStackThreadNode)?.frames.isEmpty == false
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		let identifier = NSUserInterfaceItemIdentifier("CallStackCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .systemFont(ofSize: 12)
		textField.lineBreakMode = .byTruncatingMiddle
		textField.stringValue = title(for: item)
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -4),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}

	func outlineViewSelectionDidChange(_ notification: Notification) {
		guard let outlineView = callStackOutlineView,
		      outlineView.selectedRow >= 0,
		      let node = outlineView.item(atRow: outlineView.selectedRow) as? DebugCallStackFrameNode,
		      let session = activeSession
		else {
			return
		}
		Task {
			await session.debugSession.focus(threadID: node.threadID, frameID: node.frame.id)
			Task { @MainActor in
				self.variablesCoordinator.refreshIfVisible()
				self.watchesCoordinator.refreshIfVisible()
			}
		}
		openFrameSource(node.frame)
		setCallStackStatus(frameStatus(node.frame), isError: false)
	}

	private func title(for item: Any) -> String {
		if let node = item as? DebugCallStackThreadNode {
			return "Thread \(node.thread.id)  \(node.thread.name)"
		}
		if let node = item as? DebugCallStackFrameNode {
			return frameStatus(node.frame)
		}
		return ""
	}

	private func frameStatus(_ frame: DebugStackFrame) -> String {
		let source = frame.sourceName ?? frame.sourcePath?.components(separatedBy: "/").last
		if let source {
			return "\(frame.name)  \(source):\(frame.line)"
		}
		return "\(frame.name):\(frame.line)"
	}

	private func openFrameSource(_ frame: DebugStackFrame) {
		guard let sourcePath = frame.sourcePath else {
			return
		}
		_ = documentController.openDocument(at: URL(fileURLWithPath: sourcePath), line: frame.line, column: frame.column)
	}
}

private final class DebugCallStackThreadNode: NSObject {
	let thread: DebugThread
	let frames: [DebugCallStackFrameNode]

	init(thread: DebugThread, frames: [DebugCallStackFrameNode]) {
		self.thread = thread
		self.frames = frames
	}
}

private final class DebugCallStackFrameNode: NSObject {
	let threadID: Int
	let frame: DebugStackFrame

	init(threadID: Int, frame: DebugStackFrame) {
		self.threadID = threadID
		self.frame = frame
	}
}

private enum DebugControl {
	case continueExecution
	case next
	case stepIn
	case stepOut
	case stepBack
	case reverseContinue
	case pause
	case restart
	case stop

	var runningStatus: String {
		switch self {
		case .continueExecution:
			return L10n.string("Continuing")
		case .next:
			return L10n.string("Stepping over")
		case .stepIn:
			return L10n.string("Stepping in")
		case .stepOut:
			return L10n.string("Stepping out")
		case .stepBack:
			return L10n.string("Stepping back")
		case .reverseContinue:
			return L10n.string("Reversing")
		case .pause:
			return L10n.string("Pausing")
		case .restart:
			return L10n.string("Restarting")
		case .stop:
			return L10n.string("Stopping")
		}
	}

	var doneStatus: String {
		switch self {
		case .continueExecution:
			return L10n.string("Continued")
		case .next:
			return L10n.string("Stepped over")
		case .stepIn:
			return L10n.string("Stepped in")
		case .stepOut:
			return L10n.string("Stepped out")
		case .stepBack:
			return L10n.string("Stepped back")
		case .reverseContinue:
			return L10n.string("Reversed")
		case .pause:
			return L10n.string("Paused")
		case .restart:
			return L10n.string("Restarted")
		case .stop:
			return L10n.string("Stopped")
		}
	}

	func isAvailable(in session: DebugAppSession) -> Bool {
		switch self {
		case .stepBack, .reverseContinue:
			return self == .stepBack ? session.supportsStepBack : session.supportsReverseContinue
		case .restart:
			return session.supportsRestart
		default:
			return true
		}
	}
}

private enum DebugControlError: Error, CustomStringConvertible {
	case missingFocusedThread
	case unsupportedReverseDebug

	var description: String {
		switch self {
		case .missingFocusedThread:
			return L10n.string("Select a thread first")
		case .unsupportedReverseDebug:
			return L10n.string("Reverse debugging unavailable")
		}
	}
}
