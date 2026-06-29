import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyKeymap

private func recordBenchStage(_ name: String) {
	guard let path = ProcessInfo.processInfo.environment["ITSY_BENCH_STAGES_PATH"] else {
		return
	}
	let line = "\(name) \(DispatchTime.now().uptimeNanoseconds)\n"
	let url = URL(fileURLWithPath: path)
	if !FileManager.default.fileExists(atPath: path) {
		FileManager.default.createFile(atPath: path, contents: nil)
	}
	guard let handle = try? FileHandle(forWritingTo: url) else {
		return
	}
	defer {
		try? handle.close()
	}
	_ = try? handle.seekToEnd()
	_ = try? handle.write(contentsOf: Data(line.utf8))
}

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: ItsyDocumentController
	private var commandRegistry = CommandRegistry()
	private weak var openRecentMenu: NSMenu?
	private lazy var servicesProvider = ItsyServicesProvider { [weak self] url in
		self?.documentController.openDocument(at: url) ?? false
	}
	private lazy var commandPalette = CommandPaletteController(registry: commandRegistry)
	private lazy var settingsWindow = ThemeSettingsWindowController { [weak self] in
		self?.reloadSyntaxThemes()
	}
	private lazy var projectFind = ProjectFindController(
		workspaceURL: { ItsyWorkspaceController.shared.currentRootURL },
		openFile: { [weak self] url in _ = self?.documentController.openDocument(at: url) }
	)

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
		recordBenchStage("delegate_init")
		do {
			let profile = try KeymapProfile.selected(from: CommandLine.arguments)
			let bindings = try KeymapConfiguration.load(profile: profile)
			ItsyAppKeymap.shared.configure(profile: profile, bindings: bindings)
		} catch {
			NSLog("failed to load keymap profile: \(error)")
			ItsyAppKeymap.shared.configure(profile: .plain, bindings: [])
		}
		super.init()
		ItsyCommandPaletteBridge.shared.showExCommand = { [weak self] window, completion in
			guard let self else {
				return false
			}
			self.commandPalette.showExCommand(relativeTo: window, completion: completion)
			return true
		}
		registerInitialCommands()
	}

	func applicationDidFinishLaunching(_ notification: Notification) {
		recordBenchStage("app_did_finish_launching")
		installServicesProvider()
		installMainMenu()
		recordBenchStage("main_menu_installed")
		openInitialDocument()
		recordBenchStage("initial_document_opened")
		NSApp.activate(ignoringOtherApps: true)
		recordBenchStage("app_activated")
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

	@objc private func closeCurrentDocument(_ sender: Any?) {
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

	@objc private func toggleCommandPalette(_ sender: Any?) {
		commandPalette.toggle(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func registerInitialCommands() {
		do {
			try commandRegistry.register([
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

	@objc private func toggleFindBar(_ sender: Any?) {
		activeEditorWindowController()?.toggleFindBar()
	}

	@objc private func findNext(_ sender: Any?) {
		activeEditorWindowController()?.findNext()
	}

	@objc private func findPrevious(_ sender: Any?) {
		activeEditorWindowController()?.findPrevious()
	}

	@objc private func selectAllFindMatches(_ sender: Any?) {
		activeEditorWindowController()?.selectAllFindMatches()
	}

	@objc private func showProjectFind(_ sender: Any?) {
		projectFind.toggle(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc private func showSettings(_ sender: Any?) {
		settingsWindow.showWindow(sender)
	}

	private func reloadSyntaxThemes() {
		for document in documentController.documents {
			(document as? ItsyDocument)?.reloadSyntaxTheme()
		}
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

	@objc private func openFolder(_ sender: Any?) {
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

	@objc private func openRecentDocument(_ sender: NSMenuItem) {
		guard let url = sender.representedObject as? URL else {
			return
		}
		_ = documentController.openDocument(at: url)
	}

	private func openWorkspace(at url: URL) -> Bool {
		ItsyWorkspaceController.shared.openWorkspace(at: url)
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
		NSApp.servicesProvider = servicesProvider
		NSRegisterServicesProvider(servicesProvider, "Itsy")
		NSUpdateDynamicServices()
	}

	private func installMainMenu() {
		let mainMenu = NSMenu()
		let appItem = NSMenuItem()
		let fileItem = NSMenuItem()
		let editItem = NSMenuItem()
		let commandItem = NSMenuItem()
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)
		mainMenu.addItem(editItem)
		mainMenu.addItem(commandItem)

		let appMenu = NSMenu()
		let settingsItem = appMenu.addItem(withTitle: L10n.string("Settings..."), action: #selector(showSettings(_:)), keyEquivalent: ",")
		settingsItem.target = self
		appMenu.addItem(.separator())
		appMenu.addItem(withTitle: L10n.string("Quit Itsy"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
		appItem.submenu = appMenu

		let fileMenu = NSMenu(title: L10n.string("File"))
		let newItem = fileMenu.addItem(withTitle: L10n.string("New"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "n")
		newItem.target = documentController
		let newTabItem = fileMenu.addItem(withTitle: L10n.string("New Tab"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "t")
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: L10n.string("Open..."), action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: L10n.string("Open Folder..."), action: #selector(openFolder(_:)), keyEquivalent: "O")
		openFolderItem.target = self
		let openRecentItem = fileMenu.addItem(withTitle: L10n.string("Open Recent"), action: nil, keyEquivalent: "")
		let openRecentMenu = NSMenu(title: L10n.string("Open Recent"))
		openRecentMenu.delegate = self
		self.openRecentMenu = openRecentMenu
		fileMenu.setSubmenu(openRecentMenu, for: openRecentItem)
		let closeItem = fileMenu.addItem(withTitle: L10n.string("Close"), action: #selector(closeCurrentDocument(_:)), keyEquivalent: "w")
		closeItem.target = self
		fileMenu.addItem(.separator())
		fileMenu.addItem(withTitle: L10n.string("Save"), action: #selector(NSDocument.save(_:)), keyEquivalent: "s")
		fileMenu.addItem(withTitle: L10n.string("Save As..."), action: #selector(NSDocument.saveAs(_:)), keyEquivalent: "S")
		fileItem.submenu = fileMenu

		let editMenu = NSMenu(title: L10n.string("Edit"))
		let findItem = editMenu.addItem(withTitle: L10n.string("Find"), action: #selector(toggleFindBar(_:)), keyEquivalent: "f")
		findItem.target = self
		let findNextItem = editMenu.addItem(withTitle: L10n.string("Find Next"), action: #selector(findNext(_:)), keyEquivalent: "g")
		findNextItem.target = self
			let findPreviousItem = editMenu.addItem(withTitle: L10n.string("Find Previous"), action: #selector(findPrevious(_:)), keyEquivalent: "G")
			findPreviousItem.keyEquivalentModifierMask = [.command, .shift]
			findPreviousItem.target = self
			let selectAllFindMatchesItem = editMenu.addItem(withTitle: L10n.string("Select All Find Matches"), action: #selector(selectAllFindMatches(_:)), keyEquivalent: "g")
			selectAllFindMatchesItem.keyEquivalentModifierMask = [.command, .control]
			selectAllFindMatchesItem.target = self
			let findInProjectItem = editMenu.addItem(withTitle: L10n.string("Find in Project"), action: #selector(showProjectFind(_:)), keyEquivalent: "F")
			findInProjectItem.keyEquivalentModifierMask = [.command, .shift]
			findInProjectItem.target = self
			editItem.submenu = editMenu

		let commandMenu = NSMenu(title: L10n.string("Command"))
		let paletteItem = commandMenu.addItem(withTitle: L10n.string("Command Palette"), action: #selector(toggleCommandPalette(_:)), keyEquivalent: "P")
		paletteItem.keyEquivalentModifierMask = [.command, .shift]
		paletteItem.target = self
		commandItem.submenu = commandMenu
		NSApp.mainMenu = mainMenu
	}
}

private final class ItsyServicesProvider: NSObject {
	private let openURL: (URL) -> Bool

	init(openURL: @escaping (URL) -> Bool) {
		self.openURL = openURL
	}

	@objc func openSelection(_ pasteboard: NSPasteboard, userData: String, error serviceError: AutoreleasingUnsafeMutablePointer<NSString?>) {
		guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
			serviceError.pointee = L10n.string("No text selection was provided") as NSString
			return
		}
		do {
			let url = FileManager.default.temporaryDirectory.appendingPathComponent("Itsy-Service-\(UUID().uuidString).txt")
			try text.write(to: url, atomically: true, encoding: .utf8)
			if !openURL(url) {
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
		for url in urls where !openURL(url) {
			serviceError.pointee = L10n.string("Itsy could not open \(url.lastPathComponent)") as NSString
			return
		}
	}

	private func error(_ pointer: AutoreleasingUnsafeMutablePointer<NSString?>, _ message: String.LocalizationValue) {
		pointer.pointee = L10n.string(message) as NSString
	}
}

extension AppDelegate: NSMenuDelegate {
	func menuNeedsUpdate(_ menu: NSMenu) {
		guard menu === openRecentMenu else {
			return
		}
		menu.removeAllItems()
		let urls = documentController.recentDocumentURLs
		for url in urls {
			let item = menu.addItem(withTitle: url.lastPathComponent, action: #selector(openRecentDocument(_:)), keyEquivalent: "")
			item.target = self
			item.representedObject = url
		}
		if !urls.isEmpty {
			menu.addItem(.separator())
		}
		let clearItem = menu.addItem(withTitle: L10n.string("Clear Menu"), action: #selector(NSDocumentController.clearRecentDocuments(_:)), keyEquivalent: "")
		clearItem.target = documentController
		clearItem.isEnabled = !urls.isEmpty
	}
}

if CommandLine.arguments.contains("--bench-exit-on-ready") {
	let ns = DispatchTime.now().uptimeNanoseconds
	FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
	exit(0)
}

recordBenchStage("process_start")

let app = NSApplication.shared
private let documentController = ItsyDocumentController()
private let appDelegate = AppDelegate(documentController: documentController)

_ = documentController
app.setActivationPolicy(.regular)
app.delegate = appDelegate
app.run()
