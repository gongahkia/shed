import AppKit
import Foundation
import ItsyConfig
import ItsyEditor
import ItsyKeymap

@MainActor final class AppCoordinator: NSObject {
	private let documentController: ItsyDocumentController
	private lazy var menuCoordinator = MenuCoordinator(documentController: documentController, actionTarget: self, gitTarget: gitCoordinator)
	private lazy var commandRegistry = makeCommandRegistry()
	private lazy var commandPaletteCoordinator = CommandPaletteCoordinator(
		documentController: documentController,
		commandRegistryProvider: { [weak self] in self?.commandRegistry ?? CommandRegistry() },
		activeDocumentProvider: { [weak self] in self?.activeDocument() },
		workspaceSymbolProvider: { [weak self] query in
			try await self?.activeEditorWindowController()?.workspaceSymbols(matching: query) ?? []
		},
		fileSymbolProvider: { [weak self] in
			try await self?.activeEditorWindowController()?.fileSymbolsFromLSP()
		}
	)
	private lazy var settingsCoordinator = SettingsCoordinator(
		documentController: documentController,
		onEditorPreferencesChange: { [weak self] preferences in
			self?.applyEditorPreferencesToOpenWindows(preferences)
		},
		onTerminalSettingsChange: { [weak self] settings in
			self?.applyTerminalSettings(settings)
		}
	)
	private lazy var projectFindCoordinator = ProjectFindCoordinator(documentController: documentController)
	private lazy var gitCoordinator = GitCoordinator(
		documentController: documentController,
		activeDocumentProvider: { [weak self] in self?.activeDocument() }
	)
	private lazy var taskCoordinator = TaskCoordinator(problemsCoordinator: problemsCoordinator)
	private lazy var debuggerCoordinator = DebuggerCoordinator(documentController: documentController)
	private lazy var terminalCoordinator = TerminalCoordinator(
		settingsProvider: { [weak self] in self?.currentTerminalSettings() ?? ItsySettings.TerminalSettings() },
		activeDocumentProvider: { [weak self] in self?.activeDocument() }
	)
	private lazy var problemsCoordinator = ProblemsCoordinator(documentController: documentController)
	private lazy var outlineCoordinator = OutlineCoordinator(
		documentController: documentController,
		activeDocumentProvider: { [weak self] in self?.activeDocument() }
	)
	private lazy var extensionsCoordinator = ExtensionsCoordinator()

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
		recordBenchStage("delegate_init")
		recordBenchStage("delegate_keymap_begin")
		do {
			let profile = try KeymapProfile.selected(from: CommandLine.arguments)
			let bindings = try KeymapConfiguration.load(profile: profile)
			ItsyAppKeymap.configure(profile: profile, bindings: bindings)
		} catch {
			NSLog("failed to load keymap profile: \(error)")
			ItsyAppKeymap.configure(profile: .plain, bindings: [])
		}
		recordBenchStage("delegate_keymap_end")
		super.init()
		recordBenchStage("delegate_settings_begin")
		_ = settingsCoordinator.currentSettings
		recordBenchStage("delegate_settings_end")
		recordBenchStage("delegate_palette_bridge_begin")
		commandPaletteCoordinator.installBridge()
		recordBenchStage("delegate_palette_bridge_end")
		recordBenchStage("delegate_command_bridge_begin")
		installCommandBridge()
		recordBenchStage("delegate_command_bridge_end")
		installProblemsBridge()
	}


	func applicationDidFinishLaunching(_ notification: Notification) {
		recordBenchStage("app_did_finish_launching")
		installServicesProvider()
		menuCoordinator.installMainMenu()
		recordBenchStage("main_menu_installed")
		recordBenchStage("initial_document_open_begin")
		openInitialDocument()
		recordBenchStage("initial_document_opened")
		if CommandLine.arguments.contains("--bench-exit-after-initial-document") {
			exitForBenchReady()
		}
		NSApp.activate(ignoringOtherApps: true)
		recordBenchStage("app_activated")
	}

	func applicationWillTerminate(_ notification: Notification) {
		debuggerCoordinator.terminate()
		terminalCoordinator.terminate()
	}

	func application(_ sender: NSApplication, openFile filename: String) -> Bool {
		openPath(URL(fileURLWithPath: filename))
	}

	func application(_ application: NSApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([NSUserActivityRestoring]) -> Void) -> Bool {
		guard userActivity.activityType == ItsyDocument.handoffActivityType,
		      let value = userActivity.userInfo?[ItsyDocument.handoffURLKey] as? String,
		      let url = URL(string: value)
		else {
			return false
		}
		guard documentController.openDocument(at: url),
		      let document = documentController.document(for: url) as? ItsyDocument
		else {
			return false
		}
		if let offset = userActivity.userInfo?[ItsyDocument.handoffCursorOffsetKey] as? Int {
			document.restoreHandoffCursorOffset(offset)
		}
		return true
	}

	@objc func closeCurrentDocument(_ sender: Any?) {
		if let document = NSApp.keyWindow?.windowController?.document as? NSDocument {
			document.close()
			return
		}
		if let document = documentController.currentDocument {
			document.close()
			return
		}
		if let document = documentController.documents.last {
			document.close()
			return
		}
		NSApp.keyWindow?.performClose(sender)
	}

	@objc func newWindow(_ sender: Any?) {
		do {
			let document = try documentController.makeUntitledDocument(ofType: documentController.defaultType ?? "public.data")
			documentController.addDocument(document)
			document.makeWindowControllers()
			document.showWindows()
			ItsyTabCoordinator.refresh()
		} catch {
			NSLog("failed to create new window: \(error)")
		}
	}

	@objc func toggleCommandPalette(_ sender: Any?) {
		commandPaletteCoordinator.toggleCommandPalette(sender)
	}

	@objc func showWorkspaceSymbolPalette(_ sender: Any?) {
		commandPaletteCoordinator.showWorkspaceSymbolPalette(sender)
	}

	@objc func showFileSymbolPalette(_ sender: Any?) {
		commandPaletteCoordinator.showFileSymbolPalette(sender)
	}

	@objc func showFilePalette(_ sender: Any?) {
		commandPaletteCoordinator.showFilePalette(sender)
	}

	@objc func showLinePalette(_ sender: Any?) {
		commandPaletteCoordinator.showLinePalette(sender)
	}

	private func makeCommandRegistry(workspaceRoot: URL? = nil) -> CommandRegistry {
		let workspaceRoot = workspaceRoot ?? ItsyWorkspaceController.currentRootURL
		var registry = CommandRegistry()
		do {
			try registry.register([
				Command(id: "file.new", title: L10n.string("New File"), defaultKey: "Cmd-N") { [weak self] in
					self?.documentController.newDocument(nil)
				},
				Command(id: "file.open", title: L10n.string("Open File"), defaultKey: "Cmd-O") { [weak self] in
					self?.documentController.openDocument(nil)
				},
				Command(id: "file.newWindow", title: L10n.string("New Window"), defaultKey: "Cmd-Shift-N") { [weak self] in
					self?.newWindow(nil)
				},
				Command(id: "file.openFolder", title: L10n.string("Open Folder"), defaultKey: "Cmd-Shift-O") { [weak self] in
					self?.openFolder(nil)
				},
				Command(id: "file.save", title: L10n.string("Save File"), defaultKey: "Cmd-S") { [weak self] in
					self?.activeDocument()?.save(nil)
				},
				Command(id: "file.close", title: L10n.string("Close File"), defaultKey: "Cmd-W") { [weak self] in
					self?.closeCurrentDocument(nil)
				},
				Command(id: "file.nextBuffer", title: L10n.string("Next Tab"), defaultKey: "Ctrl-Tab") {
					ItsyTabCoordinator.selectAdjacentDocument(delta: 1)
				},
				Command(id: "file.previousBuffer", title: L10n.string("Previous Tab"), defaultKey: "Ctrl-Shift-Tab") {
					ItsyTabCoordinator.selectAdjacentDocument(delta: -1)
				},
				Command(id: "file.selectTab.1", title: L10n.string("Select Tab 1"), defaultKey: "Cmd-1") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 0)
				},
				Command(id: "file.selectTab.2", title: L10n.string("Select Tab 2"), defaultKey: "Cmd-2") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 1)
				},
				Command(id: "file.selectTab.3", title: L10n.string("Select Tab 3"), defaultKey: "Cmd-3") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 2)
				},
				Command(id: "file.selectTab.4", title: L10n.string("Select Tab 4"), defaultKey: "Cmd-4") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 3)
				},
				Command(id: "file.selectTab.5", title: L10n.string("Select Tab 5"), defaultKey: "Cmd-5") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 4)
				},
				Command(id: "file.selectTab.6", title: L10n.string("Select Tab 6"), defaultKey: "Cmd-6") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 5)
				},
				Command(id: "file.selectTab.7", title: L10n.string("Select Tab 7"), defaultKey: "Cmd-7") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 6)
				},
				Command(id: "file.selectTab.8", title: L10n.string("Select Tab 8"), defaultKey: "Cmd-8") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 7)
				},
				Command(id: "file.selectTab.9", title: L10n.string("Select Tab 9"), defaultKey: "Cmd-9") {
					ItsyTabCoordinator.selectDocument(atDisplayIndex: 8)
				},
				Command(id: "view.commandPalette", title: L10n.string("Command Palette"), defaultKey: "Cmd-Shift-P") { [weak self] in
					self?.toggleCommandPalette(nil)
				},
				Command(id: "view.sidebar.toggle", title: L10n.string("Toggle Sidebar"), defaultKey: "Cmd-B") { [weak self] in
					self?.activeEditorWindowController()?.toggleSidebar()
				},
				Command(id: "view.hiddenFiles.toggle", title: L10n.string("Toggle Hidden Files"), defaultKey: "Cmd-Shift-.") { [weak self] in
					self?.activeEditorWindowController()?.toggleHiddenFiles()
				},
				Command(id: "view.focusEditor", title: L10n.string("Focus Editor"), defaultKey: nil) { [weak self] in
					self?.activeEditorWindowController()?.focusEditor()
				},
				Command(id: "view.zoomIn", title: L10n.string("Zoom In"), defaultKey: "Cmd-+") { [weak self] in
					self?.zoomIn(nil)
				},
				Command(id: "view.zoomOut", title: L10n.string("Zoom Out"), defaultKey: "Cmd--") { [weak self] in
					self?.zoomOut(nil)
				},
				Command(id: "view.resetZoom", title: L10n.string("Reset Zoom"), defaultKey: "Cmd-0") { [weak self] in
					self?.resetZoom(nil)
				},
				Command(id: "nav.gotoSymbolWorkspace", title: L10n.string("Go to Symbol in Workspace"), defaultKey: "Cmd-T") { [weak self] in
					self?.showWorkspaceSymbolPalette(nil)
				},
				Command(id: "nav.gotoSymbolFile", title: L10n.string("Go to Symbol in File"), defaultKey: "Cmd-Shift-O") { [weak self] in
					self?.showFileSymbolPalette(nil)
				},
				Command(id: "nav.gotoFile", title: L10n.string("Go to File"), defaultKey: "Cmd-P") { [weak self] in
					self?.showFilePalette(nil)
				},
				Command(id: "nav.gotoLine", title: L10n.string("Go to Line"), defaultKey: "Ctrl-G") { [weak self] in
					self?.showLinePalette(nil)
				},
				Command(id: "view.outline", title: L10n.string("Outline"), defaultKey: "Cmd-Opt-7") { [weak self] in
					self?.showOutline(nil)
				},
				Command(id: "lsp.references", title: L10n.string("Find All References"), defaultKey: "Shift-F12") { [weak self] in
					_ = self?.activeEditorWindowController()?.findAllReferences(nil)
				},
				Command(id: "lsp.callHierarchy", title: L10n.string("Find Callers of Symbol"), defaultKey: nil) { [weak self] in
					_ = self?.activeEditorWindowController()?.findCallHierarchy(nil)
				},
				Command(id: "lsp.rename", title: L10n.string("Rename Symbol"), defaultKey: "F2") { [weak self] in
					_ = self?.activeEditorWindowController()?.renameSymbol(nil)
				},
				Command(id: "lsp.formatDocument", title: L10n.string("Format Document"), defaultKey: "Shift-Opt-F") { [weak self] in
					_ = self?.activeEditorWindowController()?.formatDocument(nil)
				},
				Command(id: "lsp.formatSelection", title: L10n.string("Format Selection"), defaultKey: nil) { [weak self] in
					_ = self?.activeEditorWindowController()?.formatSelection(nil)
				},
				Command(id: "lsp.codeAction", title: L10n.string("Code Action"), defaultKey: "Cmd-.") { [weak self] in
					_ = self?.activeEditorWindowController()?.showCodeActions(nil)
				},
				Command(id: "app.settings", title: L10n.string("Settings"), defaultKey: "Cmd-,") { [weak self] in
					self?.showSettings(nil)
				},
				Command(id: "app.keyboardShortcuts", title: L10n.string("Keyboard Shortcuts"), defaultKey: "Cmd-K Cmd-S") { [weak self] in
					self?.showSettings(nil)
				},
					Command(id: "edit.find", title: L10n.string("Find"), defaultKey: "Cmd-F") { [weak self] in
						self?.toggleFindBar(nil)
					},
					Command(id: "edit.findNext", title: L10n.string("Find Next"), defaultKey: "Cmd-G") { [weak self] in
						self?.findNext(nil)
					},
					Command(id: "edit.findPrevious", title: L10n.string("Find Previous"), defaultKey: "Cmd-Shift-G") { [weak self] in
						self?.findPrevious(nil)
					},
					Command(id: "edit.selectAllFindMatches", title: L10n.string("Select All Find Matches"), defaultKey: "Cmd-Ctrl-G") { [weak self] in
						self?.selectAllFindMatches(nil)
					},
					Command(id: "edit.findInProject", title: L10n.string("Find in Project"), defaultKey: "Cmd-Shift-F") { [weak self] in
						self?.showProjectFind(nil)
					},
					Command(id: "git.changes", title: L10n.string("Git Changes"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitChanges(_:)))
					},
					Command(id: "git.refresh", title: L10n.string("Refresh Git Status"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.refreshGitChanges(_:)))
					},
					Command(id: "git.blame", title: L10n.string("Blame Current File"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitBlame(_:)))
					},
					Command(id: "git.fileHistory", title: L10n.string("File History"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitFileHistory(_:)))
					},
					Command(id: "git.lineHistory", title: L10n.string("Line History"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitLineHistory(_:)))
					},
					Command(id: "git.stashes", title: L10n.string("Stashes"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitStashes(_:)))
					},
					Command(id: "git.stashCurrent", title: L10n.string("Stash Current Changes"), defaultKey: "Cmd-Shift-S") { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.stashCurrentGitChanges(_:)))
					},
					Command(id: "git.cancelRemote", title: L10n.string("Cancel Remote Operation"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.cancelGitRemote(_:)))
					},
					Command(id: "task.run", title: L10n.string("Run Task"), defaultKey: nil) { [weak self] in
						self?.showTasks(nil)
					},
					Command(id: "task.refresh", title: L10n.string("Refresh Tasks"), defaultKey: nil) { [weak self] in
						self?.refreshTasks(nil)
					},
					Command(id: "extensions.manage", title: L10n.string("Extensions"), defaultKey: nil) { [weak self] in
						self?.showExtensions(nil)
					},
					Command(id: "extensions.reload", title: L10n.string("Reload Extension Contributions"), defaultKey: nil) { [weak self] in
						self?.reloadExtensionContributions(nil)
					},
					Command(id: "debug.start", title: L10n.string("Start Debugging"), defaultKey: "Cmd-F5") { [weak self] in
						self?.showDebugLaunchConfigPicker(nil)
					},
					Command(id: "debug.callStack", title: L10n.string("Call Stack"), defaultKey: nil) { [weak self] in
						self?.showDebugCallStack(nil)
					},
					Command(id: "debug.variables", title: L10n.string("Variables"), defaultKey: nil) { [weak self] in
						self?.showDebugVariables(nil)
					},
					Command(id: "debug.watches", title: L10n.string("Watches"), defaultKey: nil) { [weak self] in
						self?.showDebugWatches(nil)
					},
					Command(id: "debug.console", title: L10n.string("Debug Console"), defaultKey: nil) { [weak self] in
						self?.showDebugConsole(nil)
					},
					Command(id: "debug.continue", title: L10n.string("Continue"), defaultKey: nil) { [weak self] in
						self?.continueDebug(nil)
					},
					Command(id: "debug.stepOver", title: L10n.string("Step Over"), defaultKey: nil) { [weak self] in
						self?.stepOverDebug(nil)
					},
					Command(id: "debug.stepIn", title: L10n.string("Step In"), defaultKey: nil) { [weak self] in
						self?.stepInDebug(nil)
					},
					Command(id: "debug.stepOut", title: L10n.string("Step Out"), defaultKey: nil) { [weak self] in
						self?.stepOutDebug(nil)
					},
					Command(id: "debug.pause", title: L10n.string("Pause"), defaultKey: nil) { [weak self] in
						self?.pauseDebug(nil)
					},
					Command(id: "debug.restart", title: L10n.string("Restart"), defaultKey: nil) { [weak self] in
						self?.restartDebug(nil)
					},
					Command(id: "debug.stop", title: L10n.string("Stop"), defaultKey: nil) { [weak self] in
						self?.stopDebug(nil)
					},
					Command(id: "terminal.toggle", title: L10n.string("Terminal"), defaultKey: "Cmd-Shift-`") { [weak self] in
						self?.showTerminal(nil)
					},
					Command(id: "view.problems", title: L10n.string("Problems"), defaultKey: "Cmd-Shift-M") { [weak self] in
						self?.showProblems(nil)
					},
					Command(id: "problems.next", title: L10n.string("Next Problem"), defaultKey: "Ctrl-Alt-N") { [weak self] in
						self?.showNextProblem(nil)
					},
					Command(id: "problems.previous", title: L10n.string("Previous Problem"), defaultKey: "Ctrl-Alt-P") { [weak self] in
						self?.showPreviousProblem(nil)
					},
					Command(id: "editor.moveLeft", title: L10n.string("Move Left"), defaultKey: "Left") { [weak self] in
						self?.performEditorMotion(.charBackward)
					},
					Command(id: "editor.moveRight", title: L10n.string("Move Right"), defaultKey: "Right") { [weak self] in
						self?.performEditorMotion(.charForward)
					},
				Command(id: "editor.moveLineStart", title: L10n.string("Move Line Start"), defaultKey: "Cmd-Left") { [weak self] in
					self?.performEditorMotion(.lineStart)
				},
				Command(id: "editor.moveLineEnd", title: L10n.string("Move Line End"), defaultKey: "Cmd-Right") { [weak self] in
					self?.performEditorMotion(.lineEnd)
				},
			])
			try registry.register(KeymapCommandCatalog.hiddenCommands)
			registerExtensionCommands(from: workspaceRoot, into: &registry)
			return registry
		} catch {
			preconditionFailure("failed to register commands: \(error)")
		}
	}

	private func registerExtensionCommands(from workspaceRoot: URL?, into registry: inout CommandRegistry) {
		guard let workspaceRoot else {
			return
		}
		let commands = ExtensionCommandDiscovery.discover(root: workspaceRoot) { manifest, contribution in
			NSLog("extension command requested: \(manifest.identifier).\(contribution.id)")
		}
		for command in commands {
			do {
				try registry.register(command)
			} catch {
				NSLog("failed to register extension command \(command.id): \(error)")
			}
		}
	}

	private func extensionKeybindings(from workspaceRoot: URL, commandRegistry: CommandRegistry) -> [KeyBinding] {
		ExtensionKeybindingMapper.discover(
			root: workspaceRoot,
			mode: ItsyAppKeymap.currentInitialMode,
			validCommandIDs: Set(commandRegistry.allCommands.map(\.id))
		)
	}

	private func activeDocument() -> NSDocument? {
		NSApp.keyWindow?.windowController?.document as? NSDocument ?? documentController.currentDocument
	}

	private func activeEditorWindowController() -> EditorWindowController? {
		NSApp.keyWindow?.windowController as? EditorWindowController
			?? documentController.currentDocument?.windowControllers.first as? EditorWindowController
	}

	private func performEditorMotion(_ motion: Motion) {
		activeEditorWindowController()?.performEditorMotion(motion)
	}

	@objc func zoomIn(_ sender: Any?) {
		settingsCoordinator.zoomIn(sender)
	}

	@objc func zoomOut(_ sender: Any?) {
		settingsCoordinator.zoomOut(sender)
	}

	@objc func resetZoom(_ sender: Any?) {
		settingsCoordinator.resetZoom(sender)
	}

	private func applyEditorPreferencesToOpenWindows(_ preferences: EditorPreferences = EditorPreferences.load()) {
		for document in documentController.documents {
			for controller in document.windowControllers {
				(controller as? EditorWindowController)?.applyEditorPreferences(preferences)
			}
		}
		gitCoordinator.applyEditorPreferences(preferences)
	}

	@objc func toggleFindBar(_ sender: Any?) {
		activeEditorWindowController()?.toggleFindBar()
	}

	@objc func findNext(_ sender: Any?) {
		activeEditorWindowController()?.findNext()
	}

	@objc func findPrevious(_ sender: Any?) {
		activeEditorWindowController()?.findPrevious()
	}

	@objc func selectAllFindMatches(_ sender: Any?) {
		activeEditorWindowController()?.selectAllFindMatches()
	}

	@objc func showProjectFind(_ sender: Any?) {
		projectFindCoordinator.showProjectFind(sender)
	}

	private func sendGitAction(_ selector: Selector, sender: Any? = nil) {
		NSApp.sendAction(selector, to: gitCoordinator, from: sender)
	}

	@objc func showTasks(_ sender: Any?) {
		taskCoordinator.showTasks(sender)
	}

	@objc func refreshTasks(_ sender: Any?) {
		taskCoordinator.refreshTasks(sender)
	}

	@objc func showExtensions(_ sender: Any?) {
		extensionsCoordinator.showExtensions(sender)
	}

	@objc func reloadExtensionContributions(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			return
		}
		commandRegistry = makeCommandRegistry(workspaceRoot: root)
		ItsyAppKeymap.setExtensionBindings(extensionKeybindings(from: root, commandRegistry: commandRegistry))
	}

	@objc func showDebugLaunchConfigPicker(_ sender: Any?) {
		debuggerCoordinator.showLaunchConfigPicker(sender)
	}

	@objc func showDebugCallStack(_ sender: Any?) {
		debuggerCoordinator.showCallStack(sender)
	}

	@objc func showDebugVariables(_ sender: Any?) {
		debuggerCoordinator.showVariables(sender)
	}

	@objc func showDebugWatches(_ sender: Any?) {
		debuggerCoordinator.showWatches(sender)
	}

	@objc func showDebugConsole(_ sender: Any?) {
		debuggerCoordinator.showConsole(sender)
	}

	@objc func continueDebug(_ sender: Any?) {
		debuggerCoordinator.continueDebug(sender)
	}

	@objc func stepOverDebug(_ sender: Any?) {
		debuggerCoordinator.stepOverDebug(sender)
	}

	@objc func stepInDebug(_ sender: Any?) {
		debuggerCoordinator.stepInDebug(sender)
	}

	@objc func stepOutDebug(_ sender: Any?) {
		debuggerCoordinator.stepOutDebug(sender)
	}

	@objc func pauseDebug(_ sender: Any?) {
		debuggerCoordinator.pauseDebug(sender)
	}

	@objc func restartDebug(_ sender: Any?) {
		debuggerCoordinator.restartDebug(sender)
	}

	@objc func stopDebug(_ sender: Any?) {
		debuggerCoordinator.stopDebug(sender)
	}

	@objc func showTerminal(_ sender: Any?) {
		terminalCoordinator.showTerminal(sender)
	}

	private func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings) {
		terminalCoordinator.applyTerminalSettings(settings)
	}

	private func currentTerminalSettings() -> ItsySettings.TerminalSettings {
		settingsCoordinator.currentSettings.terminal
	}

	@objc func showProblems(_ sender: Any?) {
		problemsCoordinator.showProblems(sender)
	}

	@objc func showNextProblem(_ sender: Any?) {
		problemsCoordinator.showNextProblem(sender)
	}

	@objc func showPreviousProblem(_ sender: Any?) {
		problemsCoordinator.showPreviousProblem(sender)
	}

	private func setProblems(_ snapshot: WorkspaceProblemSnapshot) {
		problemsCoordinator.setProblems(snapshot)
	}

	@objc func showOutline(_ sender: Any?) {
		outlineCoordinator.showOutline(sender)
	}

	@objc func showSettings(_ sender: Any?) {
		settingsCoordinator.showSettings(sender)
	}

	private func openInitialDocument() {
		let files = CommandLine.arguments.dropFirst().filter { !$0.hasPrefix("--") }
		if let path = files.first {
			_ = openPath(URL(fileURLWithPath: path))
			return
		}
		do {
			recordBenchStage("initial_untitled_make_begin")
			let document = try documentController.makeUntitledDocument(ofType: documentController.defaultType ?? "public.data")
			recordBenchStage("initial_untitled_make_end")
			documentController.addDocument(document)
			recordBenchStage("initial_make_window_controllers_begin")
			document.makeWindowControllers()
			recordBenchStage("initial_make_window_controllers_end")
			recordBenchStage("initial_show_windows_begin")
			document.showWindows()
			recordBenchStage("initial_show_windows_end")
		} catch {
			NSLog("failed to open untitled document: \(error)")
		}
	}

	@objc func openFolder(_ sender: Any?) {
		let panel = NSOpenPanel()
		panel.canChooseDirectories = true
		panel.canChooseFiles = false
		panel.allowsMultipleSelection = false
		guard panel.runModal() == .OK, let url = panel.url else {
			return
		}
		_ = openWorkspace(at: url)
	}

	private func openPath(_ url: URL) -> Bool {
		var isDirectory: ObjCBool = false
		FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
		if isDirectory.boolValue {
			return openWorkspace(at: url)
		}
		return documentController.openDocument(at: url)
	}

	private func openWorkspace(at url: URL) -> Bool {
		ItsyWorkspaceController.openWorkspace(at: url)
		commandRegistry = makeCommandRegistry(workspaceRoot: url)
		ItsyAppKeymap.setExtensionBindings(extensionKeybindings(from: url, commandRegistry: commandRegistry))
		if documentController.documents.isEmpty {
			do {
				_ = try documentController.openUntitledDocumentAndDisplay(true)
			} catch {
				NSLog("failed to open untitled document for workspace \(url.path): \(error)")
				return false
			}
		}
		return true
	}

	private func installServicesProvider() {
		NSApp.servicesProvider = self
		NSRegisterServicesProvider(self, "Itsy")
		NSUpdateDynamicServices()
	}

	private func installCommandBridge() {
		ItsyAppCommandBridge.runCommand = { [weak self] commandID in
			guard let self else {
				return false
			}
			do {
				try self.commandRegistry.run(id: commandID)
				return true
			} catch {
				NSLog("failed to run command \(commandID): \(error)")
				return false
			}
		}
	}

	private func installProblemsBridge() {
		ItsyProblemsBridge.publishDiagnostics = { [weak self] snapshot, sourceID in
			self?.problemsCoordinator.setProblems(snapshot, sourceID: sourceID)
		}
	}

	@objc func openSelection(_ pasteboard: NSPasteboard, userData: String, error serviceError: AutoreleasingUnsafeMutablePointer<NSString?>) {
		guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
			serviceError.pointee = L10n.string("No text selection was provided") as NSString
			return
		}
		do {
			let url = FileManager.default.temporaryDirectory.appendingPathComponent("Itsy-Service-\(UUID().uuidString).txt")
			try text.write(to: url, atomically: true, encoding: .utf8)
			if !documentController.openDocument(at: url) {
				serviceError.pointee = L10n.string("Itsy could not open the service text") as NSString
			}
		} catch {
			self.error(serviceError, "Itsy could not create the service file")
		}
	}

	@objc func openFile(_ pasteboard: NSPasteboard, userData: String, error serviceError: AutoreleasingUnsafeMutablePointer<NSString?>) {
		let urls = (pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL]) ?? []
		guard !urls.isEmpty else {
			serviceError.pointee = L10n.string("No file was provided") as NSString
			return
		}
		for url in urls where !documentController.openDocument(at: url) {
			serviceError.pointee = L10n.string("Itsy could not open \(url.lastPathComponent)") as NSString
			return
		}
	}

	private func error(_ pointer: AutoreleasingUnsafeMutablePointer<NSString?>, _ message: String.LocalizationValue) {
		pointer.pointee = L10n.string(message) as NSString
	}
}
