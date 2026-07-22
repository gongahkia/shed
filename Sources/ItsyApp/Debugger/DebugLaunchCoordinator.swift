import AppKit
import Foundation
import ItsyDAP
import ItsyDebugger

@MainActor final class DebugLaunchCoordinator: NSObject, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
	private let loader: DebugLaunchConfigLoader
	private let adapterRegistryLoader: DebugAdapterRegistryLoader
	private var launchConfig = DebugLaunchConfig()
	private var configurations: [DebugLaunchConfiguration] = []
	private var panel: NSPanel?
	private var statusLabel: NSTextField?
	private var tableView: NSTableView?
	private var exceptionFiltersField: NSTextField?
	private var activeSession: DebugAppSession?
	private var launchGeneration = 0
	private var suppressSelectionLaunch = false
	private let onSessionStarted: (DebugAppSession) -> Void
	private let onSessionTerminated: (Int32) -> Void

	init(
		loader: DebugLaunchConfigLoader = DebugLaunchConfigLoader(),
		adapterRegistryLoader: DebugAdapterRegistryLoader = DebugAdapterRegistryLoader(),
		onSessionStarted: @escaping (DebugAppSession) -> Void = { _ in },
		onSessionTerminated: @escaping (Int32) -> Void = { _ in }
	) {
		self.loader = loader
		self.adapterRegistryLoader = adapterRegistryLoader
		self.onSessionStarted = onSessionStarted
		self.onSessionTerminated = onSessionTerminated
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
			let adapterRegistry = try adapterRegistryLoader.load(workspaceRoot: root)
			let mergedAdapters = adapterRegistry
				.merging(DebugAdapterRegistry(adapters: config.adapters))
				.adapters
			launchConfig = DebugLaunchConfig(adapters: mergedAdapters, configurations: config.configurations)
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
		} else {
			tableView?.deselectAll(nil)
		}
		refreshExceptionFiltersField()
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 640, height: 400),
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
		let exceptionFiltersLabel = NSTextField(labelWithString: L10n.string("Exception Filters"))
		exceptionFiltersLabel.font = .systemFont(ofSize: 12)
		exceptionFiltersLabel.textColor = .secondaryLabelColor
		let exceptionFiltersField = NSTextField(string: "")
		exceptionFiltersField.font = .systemFont(ofSize: 12)
		exceptionFiltersField.target = self
		exceptionFiltersField.action = #selector(exceptionFiltersChanged(_:))
		exceptionFiltersField.delegate = self
		let filtersStack = NSStackView(views: [exceptionFiltersLabel, exceptionFiltersField])
		filtersStack.orientation = .horizontal
		filtersStack.alignment = .centerY
		filtersStack.spacing = 8
		exceptionFiltersLabel.setContentHuggingPriority(.required, for: .horizontal)
		header.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		filtersStack.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(scrollView)
		contentView.addSubview(filtersStack)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: filtersStack.topAnchor, constant: -10),
			filtersStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			filtersStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			filtersStack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
		])
		self.statusLabel = statusLabel
		self.tableView = tableView
		self.exceptionFiltersField = exceptionFiltersField
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(680, max(520, hostFrame.width - 120))
		let height = min(440, max(320, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	@objc private func refreshConfigurationsAction(_ sender: Any?) {
		refreshConfigurations()
	}

	@objc private func startSelectedConfiguration(_ sender: Any?) {
		commitExceptionFiltersField()
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
				let session = try await DebugAppSession.start(
					adapter: adapter,
					configuration: configuration,
					workspaceRoot: root,
					onTerminated: { [weak self] status in
						Task { @MainActor in
							self?.sessionDidTerminate(generation: generation, status: status)
						}
					}
				)
				Task { @MainActor in
					guard let self, self.launchGeneration == generation else {
						session.terminate()
						return
					}
					guard !(await session.isTerminated()) else {
						self.setStatus(L10n.string("Debug adapter terminated during startup"), isError: true)
						return
					}
					self.activeSession?.terminate()
					self.activeSession = session
					self.setStatus(L10n.string("Running \(configuration.name)"), isError: false)
					self.onSessionStarted(session)
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

	private func sessionDidTerminate(generation: Int, status: Int32) {
		guard launchGeneration == generation else {
			return
		}
		activeSession = nil
		setStatus(L10n.string("Debug adapter terminated (\(status))"), isError: true)
		onSessionTerminated(status)
	}

	private func selectedConfiguration() -> DebugLaunchConfiguration? {
		guard let index = selectedConfigurationIndex() else {
			return nil
		}
		return configurations[index]
	}

	private func selectedConfigurationIndex() -> Int? {
		guard let tableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < configurations.count
		else {
			return nil
		}
		return tableView.selectedRow
	}

	private func setStatus(_ status: String, isError: Bool) {
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
	}

	private func title(for configuration: DebugLaunchConfiguration) -> String {
		"\(configuration.name)  [\(configuration.request) / \(configuration.type)]"
	}

	@objc private func exceptionFiltersChanged(_ sender: NSTextField) {
		updateSelectedExceptionFilters(sender.stringValue)
	}

	func controlTextDidEndEditing(_ notification: Notification) {
		guard notification.object as? NSTextField === exceptionFiltersField else {
			return
		}
		commitExceptionFiltersField()
	}

	private func commitExceptionFiltersField() {
		guard let exceptionFiltersField else {
			return
		}
		updateSelectedExceptionFilters(exceptionFiltersField.stringValue)
	}

	private func updateSelectedExceptionFilters(_ rawValue: String) {
		guard let index = selectedConfigurationIndex() else {
			return
		}
		configurations[index].exceptionFilters = Self.parseExceptionFilters(rawValue)
	}

	private func refreshExceptionFiltersField() {
		guard let exceptionFiltersField else {
			return
		}
		guard let configuration = selectedConfiguration() else {
			exceptionFiltersField.isEnabled = false
			exceptionFiltersField.stringValue = ""
			return
		}
		exceptionFiltersField.isEnabled = true
		exceptionFiltersField.stringValue = configuration.exceptionFilters.joined(separator: ", ")
	}

	private static func parseExceptionFilters(_ rawValue: String) -> [String] {
		var seen = Set<String>()
		return rawValue.split { $0 == "," || $0.isWhitespace }.compactMap { part in
			let filter = String(part)
			guard seen.insert(filter).inserted else {
				return nil
			}
			return filter
		}
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
		refreshExceptionFiltersField()
	}
}

final class DebugAppSession: @unchecked Sendable {
	let debugSession: DebugSession
	let configuration: DebugLaunchConfiguration
	let adapter: DebugAdapterConfig
	let client: DAPClientSession
	let capabilities: DAPCapabilities
	let supportsSetVariable: Bool
	let supportsStepBack: Bool
	let supportsReverseContinue: Bool
	let supportsRestart: Bool
	let supportsTerminate: Bool
	let breakpointVerificationStore: DebugBreakpointVerificationStore
	private let transport: DAPProcessTransport
	private let eventPump: Task<Void, Never>

	private init(debugSession: DebugSession, configuration: DebugLaunchConfiguration, adapter: DebugAdapterConfig, client: DAPClientSession, capabilities: DAPCapabilities, supportsSetVariable: Bool, breakpointVerificationStore: DebugBreakpointVerificationStore, transport: DAPProcessTransport, eventPump: Task<Void, Never>) {
		self.debugSession = debugSession
		self.configuration = configuration
		self.adapter = adapter
		self.client = client
		self.capabilities = capabilities
		self.supportsSetVariable = supportsSetVariable
		self.supportsStepBack = capabilities.supportsStepBack == true
		self.supportsReverseContinue = (capabilities.supportsReverseContinue ?? capabilities.supportsStepBack) == true
		self.supportsRestart = capabilities.supportsRestartRequest == true
		self.supportsTerminate = capabilities.supportsTerminateRequest == true
		self.breakpointVerificationStore = breakpointVerificationStore
		self.transport = transport
		self.eventPump = eventPump
	}

	deinit {
		terminate()
	}

	static func start(
		adapter: DebugAdapterConfig,
		configuration: DebugLaunchConfiguration,
		workspaceRoot: URL,
		breakpointStore: BreakpointStore = BreakpointStore(),
		onTerminated: @escaping @Sendable (Int32) -> Void = { _ in }
	) async throws -> DebugAppSession {
		guard adapter.type == DebugAdapterType.executable else {
			throw DebugLaunchError.unsupportedAdapter(adapter.type)
		}
		let availability = DebugAdapterDetector.availability(for: adapter, workspaceRoot: workspaceRoot)
		guard case let .available(executableURL) = availability else {
			if case let .missing(remediation) = availability {
				throw DebugLaunchError.missingExecutable(command: remediation.command, remediation: remediation.hint)
			}
			throw DebugLaunchError.missingExecutable(command: adapter.command, remediation: "Configure an executable adapter command.")
		}
		let transport = DAPProcessTransport(
			executableURL: executableURL,
			arguments: adapter.args,
			currentDirectoryURL: workspaceRoot,
			environment: ProcessInfo.processInfo.environment
		)
		let client = DAPClientSession(transport: transport)
		let debugSession = DebugSession(client: client)
		let breakpointVerificationStore = DebugBreakpointVerificationStore()
		let eventPump = Task.detached(priority: .userInitiated) {
			for await event in transport.events {
				switch event {
				case let .stdout(data):
					do {
						let received = try await client.receive(data)
						for clientEvent in received {
							guard case let .event(message) = clientEvent,
							      case let .breakpoint(body) = try? message.typed()
							else {
								continue
							}
							await breakpointVerificationStore.apply(body.breakpoint)
						}
					} catch {
						NSLog("debug adapter receive failed: \(error)")
					}
				case let .stderr(data):
					if let text = String(data: data, encoding: .utf8), !text.isEmpty {
						NSLog("debug adapter stderr: \(text)")
					}
				case let .terminated(status):
					await client.transportDidTerminate(status: status)
					NSLog("debug adapter terminated: \(status)")
					onTerminated(status)
				}
			}
		}
		do {
			try transport.start()
			let initialized = await client.on(event: DAPEvent.initialized)
			let initializedTask = Task {
				try await waitForFirstEvent(initialized)
			}
			let initializeResponse = try await client.initialize(clientCapabilities: DAPInitializeRequestArguments(
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
			let capabilities = Self.capabilities(in: initializeResponse)
			let supportsSetVariable = capabilities.supportsSetVariable == true
			let launchTask: Task<DAPResponse, Error>
			switch configuration.request {
			case DebugLaunchRequest.launch:
				let arguments = try launchArguments(for: configuration, workspaceRoot: workspaceRoot)
				launchTask = Task {
					try await client.launch(arguments: arguments)
				}
			case DebugLaunchRequest.attach:
				let arguments = try attachArguments(for: configuration, workspaceRoot: workspaceRoot)
				launchTask = Task {
					try await client.attach(arguments: arguments)
				}
			default:
				throw DebugLaunchError.unsupportedRequest(configuration.request)
			}
			await Task.yield()
			if await client.state == .initializing {
				do {
					try await waitForInitialized(initializedTask)
				} catch DebugLaunchError.timedOutWaitingForInitialized where adapter.kind == .lldb {
					try await client.allowConfigurationWithoutInitializedEvent()
				} catch {
					throw error
				}
			} else {
				initializedTask.cancel()
			}
			let breakpointTask = Task {
				try await DebugBreakpointSync.syncPersistedBreakpoints(from: breakpointStore, using: client, workspaceRoot: workspaceRoot)
			}
			await Task.yield()
			let exceptionTask = Task {
				try await client.setExceptionBreakpoints(DAPSetExceptionBreakpointsArguments(filters: configuration.exceptionFilters))
			}
			await Task.yield()
			let configurationTask = capabilities.supportsConfigurationDoneRequest == true ? Task {
				try await client.configurationDone()
			} : nil
			await Task.yield()
			let verification = try await breakpointTask.value
			await breakpointVerificationStore.replace(verification)
			_ = try await exceptionTask.value
			if let configurationTask {
				_ = try await configurationTask.value
			}
			_ = try await launchTask.value
			return DebugAppSession(debugSession: debugSession, configuration: configuration, adapter: adapter, client: client, capabilities: capabilities, supportsSetVariable: supportsSetVariable, breakpointVerificationStore: breakpointVerificationStore, transport: transport, eventPump: eventPump)
		} catch {
			eventPump.cancel()
			await client.transportDidTerminate(status: nil)
			transport.terminate()
			throw error
		}
	}

	func terminate() {
		eventPump.cancel()
		transport.terminate()
	}

	func isTerminated() async -> Bool {
		await client.state == .terminated
	}

	private static func launchArguments(for configuration: DebugLaunchConfiguration, workspaceRoot: URL) throws -> DAPAny {
		try requestArguments(DAPLaunchRequestArguments(
			noDebug: configuration.noDebug,
			program: resolvePath(configuration.program, workspaceRoot: workspaceRoot),
			args: configuration.args.isEmpty ? nil : configuration.args,
			cwd: resolvePath(configuration.cwd, workspaceRoot: workspaceRoot) ?? workspaceRoot.path,
			env: configuration.env.isEmpty ? nil : configuration.env,
			stopOnEntry: configuration.stopOnEntry
		), configuration: configuration, workspaceRoot: workspaceRoot)
	}

	private static func attachArguments(for configuration: DebugLaunchConfiguration, workspaceRoot: URL) throws -> DAPAny {
		try requestArguments(DAPAttachRequestArguments(program: resolvePath(configuration.program, workspaceRoot: workspaceRoot)), configuration: configuration, workspaceRoot: workspaceRoot)
	}

	private static func requestArguments<Value: Encodable>(_ base: Value, configuration: DebugLaunchConfiguration, workspaceRoot: URL) throws -> DAPAny {
		guard case var .object(arguments) = try DAPAny(encoding: base) else {
			return try DAPAny(encoding: base)
		}
		for (key, value) in configuration.adapterOptions {
			arguments[key] = value
		}
		if !configuration.sourceMap.isEmpty, arguments["sourceMap"] == nil {
			arguments["sourceMap"] = .object(configuration.sourceMap.mapValues { .string(expandPath($0, workspaceRoot: workspaceRoot)) })
		}
		return .object(arguments)
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

	private static func capabilities(in response: DAPResponse) -> DAPCapabilities {
		guard let body = response.body,
		      let data = try? JSONEncoder().encode(body),
		      let capabilities = try? JSONDecoder().decode(DAPCapabilities.self, from: data)
		else {
			return DAPCapabilities()
		}
		return capabilities
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
	case missingExecutable(command: String, remediation: String)
	case timedOutWaitingForInitialized
	case unsupportedAdapter(String)
	case unsupportedRequest(String)

	var description: String {
		switch self {
		case let .missingAdapter(id):
			return L10n.string("Missing debug adapter: \(id). Configure it in .itsy/dap.toml.")
		case let .missingExecutable(command, remediation):
			return L10n.string("Missing debug adapter executable: \(command). Fix: \(remediation)")
		case .timedOutWaitingForInitialized:
			return L10n.string("Timed out waiting for debug adapter initialization")
		case let .unsupportedAdapter(type):
			return L10n.string("Unsupported debug adapter: \(type)")
		case let .unsupportedRequest(request):
			return L10n.string("Unsupported debug request: \(request)")
		}
	}
}
