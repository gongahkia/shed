import AppKit
import Foundation
import ItsyDAP
import ItsyDebugger

final class DebugLaunchCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let loader: DebugLaunchConfigLoader
	private var launchConfig = DebugLaunchConfig()
	private var configurations: [DebugLaunchConfiguration] = []
	private var panel: NSPanel?
	private var statusLabel: NSTextField?
	private var tableView: NSTableView?
	private var activeSession: DebugAppSession?
	private var launchGeneration = 0
	private var suppressSelectionLaunch = false

	init(loader: DebugLaunchConfigLoader = DebugLaunchConfigLoader()) {
		self.loader = loader
		super.init()
	}

	@objc func showLaunchConfigPicker(_ sender: Any?) {
		let panel = makePanelIfNeeded()
		center(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshConfigurations()
	}

	func terminate() {
		launchGeneration += 1
		activeSession?.terminate()
		activeSession = nil
	}

	private func refreshConfigurations() {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setConfigurations([], status: L10n.string("Open a folder first"), isError: true)
			return
		}
		do {
			let config = try loader.load(workspaceRoot: root)
			launchConfig = config
			setConfigurations(config.configurations, status: L10n.string("\(config.configurations.count) debug configurations"), isError: false)
		} catch {
			setConfigurations([], status: String(describing: error), isError: true)
		}
	}

	private func setConfigurations(_ newConfigurations: [DebugLaunchConfiguration], status: String, isError: Bool) {
		configurations = newConfigurations
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
		tableView?.reloadData()
		if !newConfigurations.isEmpty {
			suppressSelectionLaunch = true
			tableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
			suppressSelectionLaunch = false
		}
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 640, height: 360),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Debug")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureView(contentView)
		self.panel = panel
		return panel
	}

	private func configureView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshConfigurationsAction(_:)))
		let startButton = NSButton(title: L10n.string("Start"), target: self, action: #selector(startSelectedConfiguration(_:)))
		let buttonStack = NSStackView(views: [refreshButton, startButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("debugConfiguration"))
		column.title = L10n.string("Debug")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(startSelectedConfiguration(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
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
		self.statusLabel = statusLabel
		self.tableView = tableView
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(680, max(520, hostFrame.width - 120))
		let height = min(400, max(280, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	@objc private func refreshConfigurationsAction(_ sender: Any?) {
		refreshConfigurations()
	}

	@objc private func startSelectedConfiguration(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL,
		      let configuration = selectedConfiguration()
		else {
			return
		}
		guard let adapter = launchConfig.adapter(id: configuration.type) else {
			setStatus(String(describing: DebugLaunchError.missingAdapter(configuration.type)), isError: true)
			return
		}
		launchGeneration += 1
		let generation = launchGeneration
		setStatus(L10n.string("Starting \(configuration.name)"), isError: false)
		Task(priority: .userInitiated) { [weak self] in
			do {
				let session = try await DebugAppSession.start(adapter: adapter, configuration: configuration, workspaceRoot: root)
				Task { @MainActor in
					guard let self, self.launchGeneration == generation else {
						session.terminate()
						return
					}
					self.activeSession?.terminate()
					self.activeSession = session
					self.setStatus(L10n.string("Running \(configuration.name)"), isError: false)
				}
			} catch {
				Task { @MainActor in
					guard let self, self.launchGeneration == generation else {
						return
					}
					self.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	private func selectedConfiguration() -> DebugLaunchConfiguration? {
		guard let tableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < configurations.count
		else {
			return nil
		}
		return configurations[tableView.selectedRow]
	}

	private func setStatus(_ status: String, isError: Bool) {
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
	}

	private func title(for configuration: DebugLaunchConfiguration) -> String {
		"\(configuration.name)  [\(configuration.request) / \(configuration.type)]"
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === self.tableView ? configurations.count : 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === self.tableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("DebugConfigurationCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .systemFont(ofSize: 12)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = title(for: configurations[row])
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

	func tableViewSelectionDidChange(_ notification: Notification) {
		guard notification.object as? NSTableView === tableView,
		      !suppressSelectionLaunch
		else {
			return
		}
		startSelectedConfiguration(nil)
	}
}

private final class DebugAppSession: @unchecked Sendable {
	let debugSession: DebugSession
	let configuration: DebugLaunchConfiguration
	let adapter: DebugAdapterConfig
	private let transport: DAPProcessTransport
	private let eventPump: Task<Void, Never>

	private init(debugSession: DebugSession, configuration: DebugLaunchConfiguration, adapter: DebugAdapterConfig, transport: DAPProcessTransport, eventPump: Task<Void, Never>) {
		self.debugSession = debugSession
		self.configuration = configuration
		self.adapter = adapter
		self.transport = transport
		self.eventPump = eventPump
	}

	deinit {
		terminate()
	}

	static func start(adapter: DebugAdapterConfig, configuration: DebugLaunchConfiguration, workspaceRoot: URL) async throws -> DebugAppSession {
		guard adapter.type == DebugAdapterType.executable else {
			throw DebugLaunchError.unsupportedAdapter(adapter.type)
		}
		guard let executableURL = resolveExecutable(adapter.command, workspaceRoot: workspaceRoot) else {
			throw DebugLaunchError.missingExecutable(adapter.command)
		}
		let transport = DAPProcessTransport(
			executableURL: executableURL,
			arguments: adapter.args,
			currentDirectoryURL: workspaceRoot,
			environment: ProcessInfo.processInfo.environment
		)
		let client = DAPClientSession(transport: transport)
		let debugSession = DebugSession(client: client)
		let eventPump = Task.detached(priority: .userInitiated) {
			for await event in transport.events {
				switch event {
				case let .stdout(data):
					do {
						_ = try await client.receive(data)
					} catch {
						NSLog("debug adapter receive failed: \(error)")
					}
				case let .stderr(data):
					if let text = String(data: data, encoding: .utf8), !text.isEmpty {
						NSLog("debug adapter stderr: \(text)")
					}
				case let .terminated(status):
					NSLog("debug adapter terminated: \(status)")
				}
			}
		}
		do {
			try transport.start()
			let initialized = await client.on(event: DAPEvent.initialized)
			let initializedTask = Task {
				try await waitForFirstEvent(initialized)
			}
			try await client.initialize(clientCapabilities: DAPInitializeRequestArguments(
				clientID: "itsy",
				clientName: "Itsy",
				adapterID: adapter.id,
				linesStartAt1: true,
				columnsStartAt1: true,
				pathFormat: "path",
				supportsVariableType: true,
				supportsRunInTerminalRequest: false,
				supportsProgressReporting: true,
				supportsInvalidatedEvent: true
			))
			try await waitForInitialized(initializedTask)
			switch configuration.request {
			case DebugLaunchRequest.launch:
				try await client.launch(arguments: try DAPAny(encoding: launchArguments(for: configuration, workspaceRoot: workspaceRoot)))
			case DebugLaunchRequest.attach:
				try await client.attach(arguments: try DAPAny(encoding: attachArguments(for: configuration, workspaceRoot: workspaceRoot)))
			default:
				throw DebugLaunchError.unsupportedRequest(configuration.request)
			}
			try await client.configurationDone()
			return DebugAppSession(debugSession: debugSession, configuration: configuration, adapter: adapter, transport: transport, eventPump: eventPump)
		} catch {
			eventPump.cancel()
			transport.terminate()
			throw error
		}
	}

	func terminate() {
		eventPump.cancel()
		transport.terminate()
	}

	private static func launchArguments(for configuration: DebugLaunchConfiguration, workspaceRoot: URL) -> DAPLaunchRequestArguments {
		DAPLaunchRequestArguments(
			noDebug: configuration.noDebug,
			program: resolvePath(configuration.program, workspaceRoot: workspaceRoot),
			args: configuration.args.isEmpty ? nil : configuration.args,
			cwd: resolvePath(configuration.cwd, workspaceRoot: workspaceRoot) ?? workspaceRoot.path,
			env: configuration.env.isEmpty ? nil : configuration.env,
			stopOnEntry: configuration.stopOnEntry
		)
	}

	private static func attachArguments(for configuration: DebugLaunchConfiguration, workspaceRoot: URL) -> DAPAttachRequestArguments {
		DAPAttachRequestArguments(program: resolvePath(configuration.program, workspaceRoot: workspaceRoot))
	}

	private static func waitForFirstEvent(_ stream: AsyncStream<DAPEventMessage>) async throws {
		for await _ in stream {
			return
		}
		throw CancellationError()
	}

	private static func waitForInitialized(_ task: Task<Void, Error>) async throws {
		do {
			try await withThrowingTaskGroup(of: Void.self) { group in
				group.addTask {
					try await task.value
				}
				group.addTask {
					try await Task.sleep(nanoseconds: 5_000_000_000)
					throw DebugLaunchError.timedOutWaitingForInitialized
				}
				_ = try await group.next()
				group.cancelAll()
			}
		} catch {
			task.cancel()
			throw error
		}
	}

	private static func resolveExecutable(_ command: String, workspaceRoot: URL) -> URL? {
		let expanded = expandPath(command, workspaceRoot: workspaceRoot)
		if expanded.contains("/") {
			return executableURL(at: expanded)
		}
		let pathValue = ProcessInfo.processInfo.environment["PATH"] ?? ""
		let searchPaths = pathValue.split(separator: ":").map(String.init) + ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"]
		for directory in searchPaths {
			if let url = executableURL(at: URL(fileURLWithPath: directory).appendingPathComponent(expanded).path) {
				return url
			}
		}
		return nil
	}

	private static func executableURL(at path: String) -> URL? {
		let url = URL(fileURLWithPath: path).standardizedFileURL
		return FileManager.default.isExecutableFile(atPath: url.path) ? url : nil
	}

	private static func resolvePath(_ path: String?, workspaceRoot: URL) -> String? {
		guard let path, !path.isEmpty else {
			return nil
		}
		return expandPath(path, workspaceRoot: workspaceRoot)
	}

	private static func expandPath(_ path: String, workspaceRoot: URL) -> String {
		let replaced = path.replacingOccurrences(of: "${workspaceFolder}", with: workspaceRoot.path)
		let expanded = NSString(string: replaced).expandingTildeInPath
		if expanded.hasPrefix("/") {
			return expanded
		}
		return workspaceRoot.appendingPathComponent(expanded).standardizedFileURL.path
	}
}

private enum DebugLaunchError: Error, CustomStringConvertible {
	case missingAdapter(String)
	case missingExecutable(String)
	case timedOutWaitingForInitialized
	case unsupportedAdapter(String)
	case unsupportedRequest(String)

	var description: String {
		switch self {
		case let .missingAdapter(id):
			return L10n.string("Missing debug adapter: \(id)")
		case let .missingExecutable(command):
			return L10n.string("Missing debug adapter executable: \(command)")
		case .timedOutWaitingForInitialized:
			return L10n.string("Timed out waiting for debug adapter initialization")
		case let .unsupportedAdapter(type):
			return L10n.string("Unsupported debug adapter: \(type)")
		case let .unsupportedRequest(request):
			return L10n.string("Unsupported debug request: \(request)")
		}
	}
}
