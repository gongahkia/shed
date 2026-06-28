import AppKit
import Dispatch
import Foundation

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: PicoDocumentController

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
		openDocument(at: URL(fileURLWithPath: filename))
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
			_ = openDocument(at: URL(fileURLWithPath: path))
			return
		}
		do {
			_ = try documentController.openUntitledDocumentAndDisplay(true)
		} catch {
			NSLog("failed to open untitled document: \(error)")
		}
	}

	private func openDocument(at url: URL) -> Bool {
		let controller = documentController
		let typeName = controller.defaultType ?? "public.data"
		if let document = controller.document(for: url) {
			if document.windowControllers.isEmpty {
				document.makeWindowControllers()
			}
			document.showWindows()
			return true
		}
		do {
			let document = try controller.makeDocument(withContentsOf: url, ofType: typeName)
			controller.addDocument(document)
			document.makeWindowControllers()
			document.showWindows()
			return true
		} catch {
			NSLog("failed to open \(url.path): \(error)")
			return false
		}
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
		let closeItem = fileMenu.addItem(withTitle: "Close", action: #selector(closeCurrentDocument(_:)), keyEquivalent: "w")
		closeItem.target = self
		fileMenu.addItem(.separator())
		fileMenu.addItem(withTitle: "Save", action: #selector(NSDocument.save(_:)), keyEquivalent: "s")
		fileMenu.addItem(withTitle: "Save As...", action: #selector(NSDocument.saveAs(_:)), keyEquivalent: "S")
		fileItem.submenu = fileMenu
		NSApp.mainMenu = mainMenu
	}
}

let app = NSApplication.shared
private let documentController = PicoDocumentController()
private let appDelegate = AppDelegate(documentController: documentController)

_ = documentController
app.setActivationPolicy(.regular)
app.delegate = appDelegate
app.run()
