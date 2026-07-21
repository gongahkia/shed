import AppKit
import Darwin
import Dispatch
import Foundation
import ItsyEditor

@MainActor final class DocumentFileWatcher {
	private let queue = DispatchQueue(label: "dev.itsy.editor.file-watcher")
	private var source: DispatchSourceFileSystemObject?
	private var pendingExternalChangePrompt = false
	var fileURL: () -> URL? = { nil }
	var currentText: () -> String = { "" }
	var isDocumentEdited: () -> Bool = { false }
	var displayName: (URL) -> String = { $0.lastPathComponent }
	var promptWindow: () -> NSWindow? = { nil }
	var reloadFromDisk: (URL) -> Void = { _ in }
	var compareExternalText: (URL, String, String) -> Void = { _, _, _ in }
	var mergeExternalText: (URL, String, String) -> Void = { _, _, _ in }
	var discardDeletedBuffer: () -> Void = {}

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
		let exists = FileManager.default.fileExists(atPath: url.path)
		let diskText = exists ? (try? TextFileCodec.decode(Data(contentsOf: url)).text) : nil
		let localText = currentText()
		switch ExternalFileChangeResolver.state(localText: localText, diskText: diskText, isDirty: isDocumentEdited(), fileExists: exists) {
		case .unchanged:
			pendingExternalChangePrompt = false
			restart()
		case .cleanReload:
			reloadFromDisk(url)
			pendingExternalChangePrompt = false
			restart()
		case .dirtyConflict:
			presentDirtyConflict(url: url, localText: localText, diskText: diskText ?? "")
		case .deletedClean, .deletedDirty:
			presentDeletion(url: url)
		case .unreadable:
			presentUnreadableChange(url: url)
		}
	}

	private func presentDirtyConflict(url: URL, localText: String, diskText: String) {
		let alert = NSAlert()
		alert.messageText = L10n.string("\(displayName(url)) changed on disk")
		alert.informativeText = L10n.string("Your unsaved edits conflict with the on-disk version.")
		alert.addButton(withTitle: L10n.string("Reload"))
		alert.addButton(withTitle: L10n.string("Keep Editing"))
		alert.addButton(withTitle: L10n.string("Compare"))
		alert.addButton(withTitle: L10n.string("Merge Safely"))
		if let window = promptWindow() {
			alert.beginSheetModal(for: window) { [weak self] response in
				self?.handleDirtyConflictPrompt(response, url: url, localText: localText, diskText: diskText)
			}
		} else {
			handleDirtyConflictPrompt(alert.runModal(), url: url, localText: localText, diskText: diskText)
		}
	}

	private func handleDirtyConflictPrompt(_ response: NSApplication.ModalResponse, url: URL, localText: String, diskText: String) {
		if response == .alertFirstButtonReturn {
			reloadFromDisk(url)
		} else if response == .alertThirdButtonReturn {
			compareExternalText(url, localText, diskText)
		} else if response.rawValue == NSApplication.ModalResponse.alertThirdButtonReturn.rawValue + 1 {
			mergeExternalText(url, localText, diskText)
		}
		pendingExternalChangePrompt = false
		restart()
	}

	private func presentDeletion(url: URL) {
		let alert = NSAlert()
		alert.messageText = L10n.string("\(displayName(url)) was removed or renamed")
		alert.informativeText = L10n.string("Keep the current buffer or explicitly discard it.")
		alert.addButton(withTitle: L10n.string("Keep Editing"))
		alert.addButton(withTitle: L10n.string("Discard Buffer"))
		let complete: (NSApplication.ModalResponse) -> Void = { [weak self] response in
			if response == .alertSecondButtonReturn {
				self?.discardDeletedBuffer()
			}
			self?.pendingExternalChangePrompt = false
			self?.restart()
		}
		if let window = promptWindow() {
			alert.beginSheetModal(for: window, completionHandler: complete)
		} else {
			complete(alert.runModal())
		}
	}

	private func presentUnreadableChange(url: URL) {
		let alert = NSAlert()
		alert.messageText = L10n.string("\(displayName(url)) changed to an unsupported format")
		alert.informativeText = L10n.string("The current buffer was kept unchanged.")
		alert.addButton(withTitle: L10n.string("Keep Editing"))
		let complete: (NSApplication.ModalResponse) -> Void = { [weak self] _ in
			self?.pendingExternalChangePrompt = false
			self?.restart()
		}
		if let window = promptWindow() {
			alert.beginSheetModal(for: window, completionHandler: complete)
		} else {
			complete(alert.runModal())
		}
	}
}
