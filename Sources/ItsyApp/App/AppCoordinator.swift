import AppKit
import Foundation
import ItsyConfig
import ItsyEditor
import ItsyKeymap

final class AppCoordinator: NSObject {
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
	private lazy var terminalCoordinator = TerminalCoordinator(
		settingsProvider: { [weak self] in self?.currentTerminalSettings() ?? ItsySettings.TerminalSettings() },
		activeDocumentProvider: { [weak self] in self?.activeDocument() }
	)
	private lazy var problemsCoordinator = ProblemsCoordinator(documentController: documentController)
	private lazy var outlineCoordinator = OutlineCoordinator(
		documentController: documentController,
		activeDocumentProvider: { [weak self] in self?.activeDocument() }
	)

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
		recordBenchStage("delegate_init")
		do {
			let profile = try KeymapProfile.selected(from: CommandLine.arguments)
			let bindings = try KeymapConfiguration.load(profile: profile)
			ItsyAppKeymap.configure(profile: profile, bindings: bindings)
		} catch {
			NSLog("failed to load keymap profile: \(error)")
			ItsyAppKeymap.configure(profile: .plain, bindings: [])
		}
		super.init()
		_ = settingsCoordinator.currentSettings
		commandPaletteCoordinator.installBridge()
	}


	func applicationDidFinishLaunching(_ notification: Notification) {
		recordBenchStage("app_did_finish_launching")
		installServicesProvider()
		menuCoordinator.installMainMenu()
		recordBenchStage("main_menu_installed")
		openInitialDocument()
		recordBenchStage("initial_document_opened")
		if CommandLine.arguments.contains("--bench-exit-after-initial-document") {
			exitForBenchReady()
		}
		NSApp.activate(ignoringOtherApps: true)
		recordBenchStage("app_activated")
	}

	func applicationWillTerminate(_ notification: Notification) {
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

	@objc func toggleCommandPalette(_ sender: Any?) {
		commandPaletteCoordinator.toggleCommandPalette(sender)
	}

	@objc func showWorkspaceSymbolPalette(_ sender: Any?) {
		commandPaletteCoordinator.showWorkspaceSymbolPalette(sender)
	}

	@objc func showFileSymbolPalette(_ sender: Any?) {
		commandPaletteCoordinator.showFileSymbolPalette(sender)
	}

	private func makeCommandRegistry() -> CommandRegistry {
		var registry = CommandRegistry()
		do {
			try registry.register([
				Command(id: "file.new", title: L10n.string("New File"), defaultKey: "Cmd-N") { [weak self] in
					self?.documentController.newDocument(nil)
				},
				Command(id: "file.open", title: L10n.string("Open File"), defaultKey: "Cmd-O") { [weak self] in
					self?.documentController.openDocument(nil)
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
				Command(id: "view.commandPalette", title: L10n.string("Command Palette"), defaultKey: "Cmd-Shift-P") { [weak self] in
					self?.toggleCommandPalette(nil)
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
				Command(id: "view.outline", title: L10n.string("Outline"), defaultKey: "Cmd-Opt-7") { [weak self] in
					self?.showOutline(nil)
				},
				Command(id: "lsp.references", title: L10n.string("Find All References"), defaultKey: "Shift-F12") { [weak self] in
					_ = self?.activeEditorWindowController()?.findAllReferences(nil)
				},
				Command(id: "app.settings", title: L10n.string("Settings"), defaultKey: "Cmd-,") { [weak self] in
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
					Command(id: "git.stashes", title: L10n.string("Stashes"), defaultKey: nil) { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.showGitStashes(_:)))
					},
					Command(id: "git.stashCurrent", title: L10n.string("Stash Current Changes"), defaultKey: "Cmd-Shift-S") { [weak self] in
						self?.sendGitAction(#selector(GitCoordinator.stashCurrentGitChanges(_:)))
					},
					Command(id: "task.run", title: L10n.string("Run Task"), defaultKey: nil) { [weak self] in
						self?.showTasks(nil)
					},
					Command(id: "task.refresh", title: L10n.string("Refresh Tasks"), defaultKey: nil) { [weak self] in
						self?.refreshTasks(nil)
					},
					Command(id: "terminal.toggle", title: L10n.string("Terminal"), defaultKey: "Cmd-Shift-`") { [weak self] in
						self?.showTerminal(nil)
					},
					Command(id: "view.problems", title: L10n.string("Problems"), defaultKey: "Cmd-Shift-M") { [weak self] in
						self?.showProblems(nil)
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
			return registry
		} catch {
			preconditionFailure("failed to register commands: \(error)")
		}
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
			_ = try documentController.openUntitledDocumentAndDisplay(true)
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
