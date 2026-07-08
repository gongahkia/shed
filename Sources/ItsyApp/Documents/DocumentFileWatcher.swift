import AppKit
import Darwin
import Dispatch
import Foundation

@MainActor final class DocumentFileWatcher {
	private let queue = DispatchQueue(label: "dev.itsy.editor.file-watcher")
	private var source: DispatchSourceFileSystemObject?
	private var pendingExternalChangePrompt = false
	var fileURL: () -> URL? = { nil }
	var currentText: () -> String = { "" }
	var displayName: (URL) -> String = { $0.lastPathComponent }
	var promptWindow: () -> NSWindow? = { nil }
	var reloadFromDisk: (URL) -> Void = { _ in }

	deinit {
		source?.cancel()
	}

	func restart() {
		stop()
		guard let url = fileURL(), url.isFileURL else {
			return
		}
		let descriptor = open(url.path, O_EVTONLY)
		guard descriptor >= 0 else {
			return
		}
		let nextSource = DispatchSource.makeFileSystemObjectSource(
			fileDescriptor: descriptor,
			eventMask: [.write, .delete, .rename, .extend],
			queue: queue
		)
		nextSource.setEventHandler { [weak self] in
			DispatchQueue.main.async {
				self?.externalFileDidChange()
			}
		}
		nextSource.setCancelHandler {
			Darwin.close(descriptor)
		}
		source = nextSource
		nextSource.resume()
	}

	func stop() {
		source?.cancel()
		source = nil
	}

	private func externalFileDidChange() {
		guard !pendingExternalChangePrompt, let url = fileURL() else {
			return
		}
		pendingExternalChangePrompt = true
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
			self?.promptForExternalFileChange(at: url)
		}
	}

	private func promptForExternalFileChange(at url: URL) {
		if (try? String(contentsOf: url, encoding: .utf8)) == currentText() {
			pendingExternalChangePrompt = false
			restart()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("\(displayName(url)) changed on disk")
		alert.informativeText = L10n.string("Reload the file from disk?")
		alert.addButton(withTitle: L10n.string("Reload"))
		alert.addButton(withTitle: L10n.string("Keep Editing"))
		if let window = promptWindow() {
			alert.beginSheetModal(for: window) { [weak self] response in
				self?.handleExternalFilePrompt(response, url: url)
			}
		} else {
			handleExternalFilePrompt(alert.runModal(), url: url)
		}
	}

	private func handleExternalFilePrompt(_ response: NSApplication.ModalResponse, url: URL) {
		if response == .alertFirstButtonReturn {
			reloadFromDisk(url)
		}
		pendingExternalChangePrompt = false
		restart()
	}
}
