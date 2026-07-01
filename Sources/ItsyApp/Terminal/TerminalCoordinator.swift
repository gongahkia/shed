import AppKit
import Foundation
import ItsyConfig

final class TerminalCoordinator: NSObject {
	private var terminalPanel: NSPanel?
	private var terminalStatusLabel: NSTextField?
	private var terminalView: ItsyTerminalView?
	private var terminalSession: ItsyTerminalSession?
	private let settingsProvider: () -> ItsySettings.TerminalSettings
	private let activeDocumentProvider: () -> NSDocument?

	init(settingsProvider: @escaping () -> ItsySettings.TerminalSettings, activeDocumentProvider: @escaping () -> NSDocument?) {
		self.settingsProvider = settingsProvider
		self.activeDocumentProvider = activeDocumentProvider
	}

	@objc func showTerminal(_ sender: Any?) {
		toggleTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	func terminate() {
		terminalSession?.terminate()
	}

	func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings) {
		terminalView?.applyTerminalSettings(settings)
	}

	private func toggleTerminal(relativeTo hostWindow: NSWindow?) {
		if terminalPanel?.isVisible == true {
			terminalPanel?.close()
			return
		}
		showTerminal(relativeTo: hostWindow)
	}

	private func showTerminal(relativeTo hostWindow: NSWindow?) {
		let panel = makeTerminalPanelIfNeeded()
		centerTerminalPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		startTerminalIfNeeded()
		terminalPanel?.makeFirstResponder(terminalView)
	}

	private func makeTerminalPanelIfNeeded() -> NSPanel {
		if let panel = terminalPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 760, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Terminal")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureTerminalView(contentView)
		terminalPanel = panel
		return panel
	}

	private func configureTerminalView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		let clearButton = NSButton(title: L10n.string("Clear"), target: self, action: #selector(clearTerminal(_:)))
		let restartButton = NSButton(title: L10n.string("Restart"), target: self, action: #selector(restartTerminal(_:)))
		let buttonStack = NSStackView(views: [clearButton, restartButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let terminalView = ItsyTerminalView()
		terminalView.onInput = { [weak self] data in
			self?.terminalSession?.send(data)
		}
		terminalView.onResize = { [weak self] columns, rows in
			self?.terminalSession?.resize(columns: columns, rows: rows)
		}
		terminalView.applyTerminalSettings(settingsProvider())
		header.translatesAutoresizingMaskIntoConstraints = false
		terminalView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(terminalView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
			terminalView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			terminalView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			terminalView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
			terminalView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		terminalStatusLabel = statusLabel
		self.terminalView = terminalView
	}

	private func centerTerminalPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(820, max(560, hostFrame.width - 120))
		let height = min(500, max(320, hostFrame.height - 160))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func startTerminalIfNeeded() {
		guard terminalSession?.isRunning != true else {
			updateTerminalStatus()
			return
		}
		let size = terminalView?.terminalSize ?? (columns: 80, rows: 24)
		let session = ItsyTerminalSession(currentDirectoryURL: terminalWorkingDirectory())
		session.onOutput = { [weak self] data in
			DispatchQueue.main.async {
				self?.terminalView?.ingest(data)
			}
		}
		session.onExit = { [weak self] status in
			DispatchQueue.main.async {
				self?.terminalStatusLabel?.textColor = .systemRed
				self?.terminalStatusLabel?.stringValue = L10n.string("Shell exited \(status)")
			}
		}
		do {
			try session.start(columns: size.columns, rows: size.rows)
			terminalSession = session
			updateTerminalStatus()
		} catch {
			terminalStatusLabel?.textColor = .systemRed
			terminalStatusLabel?.stringValue = String(describing: error)
			terminalView?.ingest(Data("failed to start shell: \(error)\r\n".utf8))
		}
	}

	@objc private func clearTerminal(_ sender: Any?) {
		terminalView?.clearScrollback()
	}

	@objc private func restartTerminal(_ sender: Any?) {
		terminalSession?.terminate()
		terminalSession = nil
		terminalView?.reset()
		startTerminalIfNeeded()
		terminalPanel?.makeFirstResponder(terminalView)
	}

	private func updateTerminalStatus() {
		terminalStatusLabel?.textColor = .secondaryLabelColor
		terminalStatusLabel?.stringValue = L10n.string("\(terminalShellName()) · \(terminalSession?.currentDirectoryURL.path ?? terminalWorkingDirectory().path)")
	}

	private func terminalWorkingDirectory() -> URL {
		if let root = ItsyWorkspaceController.currentRootURL {
			return root
		}
		if let url = activeDocumentProvider().flatMap(\.fileURL) {
			return url.deletingLastPathComponent()
		}
		return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
	}

	private func terminalShellName() -> String {
		let shellPath = ProcessInfo.processInfo.environment["SHELL"].flatMap { $0.isEmpty ? nil : $0 } ?? "/bin/zsh"
		return URL(fileURLWithPath: shellPath).lastPathComponent
	}
}
