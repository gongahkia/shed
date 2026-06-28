import AppKit
import Dispatch
import Foundation
import PicoEditor

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: PicoDocumentController
	private let commandRegistry = CommandRegistry()
	private weak var openRecentMenu: NSMenu?
	private lazy var commandPalette = CommandPaletteController(registry: commandRegistry)
	private lazy var projectFind = ProjectFindController(
		workspaceURL: { PicoWorkspaceController.shared.currentRootURL },
		openFile: { [weak self] url in _ = self?.documentController.openDocument(at: url) }
	)

	init(documentController: PicoDocumentController) {
		self.documentController = documentController
		super.init()
		registerInitialCommands()
	}

	func applicationDidFinishLaunching(_ notification: Notification) {
		if CommandLine.arguments.contains("--bench-exit-on-ready") {
			let ns = DispatchTime.now().uptimeNanoseconds
			FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
			NSApp.terminate(nil)
			return
		}
		installMainMenu()
		openInitialDocument()
		NSApp.activate(ignoringOtherApps: true)
	}

	func application(_ sender: NSApplication, openFile filename: String) -> Bool {
		openPath(URL(fileURLWithPath: filename))
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
				Command(id: "file.new", title: "New File", defaultKey: "Cmd-N") { [weak self] in
					self?.documentController.newDocument(nil)
				},
				Command(id: "file.open", title: "Open File", defaultKey: "Cmd-O") { [weak self] in
					self?.documentController.openDocument(nil)
				},
				Command(id: "file.openFolder", title: "Open Folder", defaultKey: "Cmd-Shift-O") { [weak self] in
					self?.openFolder(nil)
				},
				Command(id: "file.save", title: "Save File", defaultKey: "Cmd-S") { [weak self] in
					self?.activeDocument()?.save(nil)
				},
				Command(id: "file.close", title: "Close File", defaultKey: "Cmd-W") { [weak self] in
					self?.closeCurrentDocument(nil)
				},
				Command(id: "view.commandPalette", title: "Command Palette", defaultKey: "Cmd-Shift-P") { [weak self] in
					self?.toggleCommandPalette(nil)
				},
				Command(id: "view.focusEditor", title: "Focus Editor", defaultKey: nil) { [weak self] in
					self?.activeEditorWindowController()?.focusEditor()
				},
					Command(id: "edit.find", title: "Find", defaultKey: "Cmd-F") { [weak self] in
						self?.toggleFindBar(nil)
					},
					Command(id: "edit.findNext", title: "Find Next", defaultKey: "Cmd-G") { [weak self] in
						self?.findNext(nil)
					},
					Command(id: "edit.findPrevious", title: "Find Previous", defaultKey: "Cmd-Shift-G") { [weak self] in
						self?.findPrevious(nil)
					},
					Command(id: "edit.selectAllFindMatches", title: "Select All Find Matches", defaultKey: "Cmd-Ctrl-G") { [weak self] in
						self?.selectAllFindMatches(nil)
					},
					Command(id: "edit.findInProject", title: "Find in Project", defaultKey: "Cmd-Shift-F") { [weak self] in
						self?.showProjectFind(nil)
					},
					Command(id: "editor.moveLeft", title: "Move Left", defaultKey: "Left") { [weak self] in
						self?.performEditorMotion(.charBackward)
					},
					Command(id: "editor.moveRight", title: "Move Right", defaultKey: "Right") { [weak self] in
						self?.performEditorMotion(.charForward)
					},
				Command(id: "editor.moveLineStart", title: "Move Line Start", defaultKey: "Cmd-Left") { [weak self] in
					self?.performEditorMotion(.lineStart)
				},
				Command(id: "editor.moveLineEnd", title: "Move Line End", defaultKey: "Cmd-Right") { [weak self] in
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
		PicoWorkspaceController.shared.openWorkspace(at: url)
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
		appMenu.addItem(withTitle: "Quit Pico", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
		appItem.submenu = appMenu

		let fileMenu = NSMenu(title: "File")
		let newItem = fileMenu.addItem(withTitle: "New", action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "n")
		newItem.target = documentController
		let newTabItem = fileMenu.addItem(withTitle: "New Tab", action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "t")
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: "Open...", action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: "Open Folder...", action: #selector(openFolder(_:)), keyEquivalent: "O")
		openFolderItem.target = self
		let openRecentItem = fileMenu.addItem(withTitle: "Open Recent", action: nil, keyEquivalent: "")
		let openRecentMenu = NSMenu(title: "Open Recent")
		openRecentMenu.delegate = self
		self.openRecentMenu = openRecentMenu
		fileMenu.setSubmenu(openRecentMenu, for: openRecentItem)
		let closeItem = fileMenu.addItem(withTitle: "Close", action: #selector(closeCurrentDocument(_:)), keyEquivalent: "w")
		closeItem.target = self
		fileMenu.addItem(.separator())
		fileMenu.addItem(withTitle: "Save", action: #selector(NSDocument.save(_:)), keyEquivalent: "s")
		fileMenu.addItem(withTitle: "Save As...", action: #selector(NSDocument.saveAs(_:)), keyEquivalent: "S")
		fileItem.submenu = fileMenu

		let editMenu = NSMenu(title: "Edit")
		let findItem = editMenu.addItem(withTitle: "Find", action: #selector(toggleFindBar(_:)), keyEquivalent: "f")
		findItem.target = self
		let findNextItem = editMenu.addItem(withTitle: "Find Next", action: #selector(findNext(_:)), keyEquivalent: "g")
		findNextItem.target = self
			let findPreviousItem = editMenu.addItem(withTitle: "Find Previous", action: #selector(findPrevious(_:)), keyEquivalent: "G")
			findPreviousItem.keyEquivalentModifierMask = [.command, .shift]
			findPreviousItem.target = self
			let selectAllFindMatchesItem = editMenu.addItem(withTitle: "Select All Find Matches", action: #selector(selectAllFindMatches(_:)), keyEquivalent: "g")
			selectAllFindMatchesItem.keyEquivalentModifierMask = [.command, .control]
			selectAllFindMatchesItem.target = self
			let findInProjectItem = editMenu.addItem(withTitle: "Find in Project", action: #selector(showProjectFind(_:)), keyEquivalent: "F")
			findInProjectItem.keyEquivalentModifierMask = [.command, .shift]
			findInProjectItem.target = self
			editItem.submenu = editMenu

		let commandMenu = NSMenu(title: "Command")
		let paletteItem = commandMenu.addItem(withTitle: "Command Palette", action: #selector(toggleCommandPalette(_:)), keyEquivalent: "P")
		paletteItem.keyEquivalentModifierMask = [.command, .shift]
		paletteItem.target = self
		commandItem.submenu = commandMenu
		NSApp.mainMenu = mainMenu
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
		let clearItem = menu.addItem(withTitle: "Clear Menu", action: #selector(NSDocumentController.clearRecentDocuments(_:)), keyEquivalent: "")
		clearItem.target = documentController
		clearItem.isEnabled = !urls.isEmpty
	}
}

let app = NSApplication.shared
private let documentController = PicoDocumentController()
private let appDelegate = AppDelegate(documentController: documentController)

_ = documentController
app.setActivationPolicy(.regular)
app.delegate = appDelegate
app.run()
