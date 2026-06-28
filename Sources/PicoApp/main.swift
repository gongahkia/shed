import AppKit
import Dispatch
import Foundation

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: PicoDocumentController
	private weak var openRecentMenu: NSMenu?

	init(documentController: PicoDocumentController) {
		self.documentController = documentController
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
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)

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
