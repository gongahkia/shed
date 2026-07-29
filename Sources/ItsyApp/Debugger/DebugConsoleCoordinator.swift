import AppKit
import Foundation
import ItsyDAP
import ItsyDebugger
import ItsyEditor

@MainActor final class DebugConsoleCoordinator: NSObject, NSTextFieldDelegate {
	private let activeSessionProvider: () -> DebugAppSession?
	private var panel: NSPanel?
	private var contentView: NSView?
	private var statusLabel: NSTextField?
	private var textView: NSTextView?
	private var inputField: NSTextField?
	private var outputTask: Task<Void, Never>?
	private var seenOutputSequences = Set<Int>()

	init(activeSessionProvider: @escaping () -> DebugAppSession?) {
		self.activeSessionProvider = activeSessionProvider
		super.init()
	}

	@objc func showConsole(_ sender: Any?) {
		let panel = makePanelIfNeeded()
		center(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.makeFirstResponder(inputField)
		refreshStatus()
	}

	func sessionDidStart(_ session: DebugAppSession) {
		outputTask?.cancel()
		seenOutputSequences.removeAll()
		textView?.textStorage?.setAttributedString(NSAttributedString())
		outputTask = Task { @MainActor [weak self] in
			let stream = await session.client.on(event: DAPEvent.output)
			let recovered = await session.outputRecoveryBuffer.snapshot()
			for entry in recovered {
				self?.appendOutput(entry.body, sequence: entry.sequence, session: session)
			}
			for await event in stream {
				guard case let .output(body) = try? event.typed() else {
					continue
				}
				self?.appendOutput(body, sequence: event.seq, session: session)
			}
		}
		refreshStatus()
	}

	func clear() {
		outputTask?.cancel()
		outputTask = nil
		seenOutputSequences.removeAll()
		textView?.textStorage?.setAttributedString(NSAttributedString())
		setStatus(L10n.string("No active debug session"), isError: true)
	}

	func debuggerContentView() -> NSView {
		let contentView = makeContentViewIfNeeded()
		panel?.orderOut(nil)
		return contentView
	}

	func prepareForDebuggerPresentation() {
		panel?.orderOut(nil)
		refreshStatus()
	}

	private func makePanelIfNeeded() -> NSPanel {
		let panel: NSPanel
		if let existing = self.panel {
			panel = existing
		} else {
			panel = NSPanel(
				contentRect: NSRect(x: 0, y: 0, width: 760, height: 520),
				styleMask: [.titled, .closable, .resizable, .utilityWindow],
				backing: .buffered,
				defer: false
			)
			panel.title = L10n.string("Debug Console")
			panel.isReleasedWhenClosed = false
			self.panel = panel
		}
		let contentView = makeContentViewIfNeeded()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		return panel
	}

	private func makeContentViewIfNeeded() -> NSView {
		if let contentView {
			return contentView
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 760, height: 520))
		configureView(contentView)
		self.contentView = contentView
		return contentView
	}

	private func configureView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textView.textColor = .textColor
		textView.backgroundColor = .textBackgroundColor
		let scrollView = NSScrollView()
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = true
		let inputField = NSTextField(string: "")
		inputField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		inputField.target = self
		inputField.action = #selector(sendConsoleInput(_:))
		inputField.delegate = self
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		inputField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)
		contentView.addSubview(scrollView)
		contentView.addSubview(inputField)
		NSLayoutConstraint.activate([
			statusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			statusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			statusLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 10),
			inputField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 8),
			inputField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -8),
			inputField.topAnchor.constraint(equalTo: scrollView.bottomAnchor, constant: 8),
			inputField.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -8),
		])
		self.statusLabel = statusLabel
		self.textView = textView
		self.inputField = inputField
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(800, max(560, hostFrame.width - 120))
		let height = min(600, max(360, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	private func refreshStatus() {
		setStatus(activeSessionProvider() == nil ? L10n.string("No active debug session") : L10n.string("Ready"), isError: activeSessionProvider() == nil)
	}

	private func appendOutput(_ body: DAPOutputEventBody, sequence: Int, session: DebugAppSession) {
		guard activeSessionProvider() === session, seenOutputSequences.insert(sequence).inserted else {
			return
		}
		let identifier = "\(session.adapter.id):\(session.workspaceRoot.path)"
		Task {
			await IntegrationOutputConsole.shared.append(
				service: .dap,
				identifier: identifier,
				kind: body.category == DAPOutputCategory.stderr ? .standardError : .event,
				text: body.output,
				errorReference: body.category == DAPOutputCategory.stderr ? "dap://\(session.adapter.id)/\(session.workspaceRoot.path)" : nil
			)
		}
		append(body.output, category: body.category)
	}

	private func append(_ text: String, category: String? = nil) {
		guard let storage = textView?.textStorage else {
			return
		}
		let baseColor = category == DAPOutputCategory.stderr ? NSColor.systemRed : NSColor.textColor
		storage.append(DebugANSIAttributedString.make(text, baseColor: baseColor))
		textView?.scrollToEndOfDocument(nil)
	}

	@objc private func sendConsoleInput(_ sender: NSTextField) {
		let expression = sender.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !expression.isEmpty else {
			return
		}
		sender.stringValue = ""
		append("> \(expression)\n", category: DAPOutputCategory.console)
		guard let session = activeSessionProvider() else {
			setStatus(L10n.string("No active debug session"), isError: true)
			return
		}
		Task(priority: .userInitiated) { [weak self] in
			do {
				let frameID = await session.debugSession.focusedFrameID
				let value = try await session.debugSession.evaluate(expression: expression, frameID: frameID, context: "repl")
				Task { @MainActor in
					self?.append("\(value.result)\n", category: DAPOutputCategory.console)
					self?.setStatus(L10n.string("Ready"), isError: false)
				}
			} catch {
				Task { @MainActor in
					self?.append("\(String(describing: error))\n", category: DAPOutputCategory.stderr)
					self?.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	private func setStatus(_ status: String, isError: Bool) {
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
	}
}

private enum DebugANSIAttributedString {
	static func make(_ text: String, baseColor: NSColor) -> NSAttributedString {
		let output = NSMutableAttributedString()
		var color = baseColor
		var index = text.startIndex
		while index < text.endIndex {
			let nextIndex = text.index(after: index)
			if text[index] == "\u{1B}", nextIndex < text.endIndex, text[nextIndex] == "[" {
				if let end = text[index...].firstIndex(of: "m") {
					let start = text.index(index, offsetBy: 2)
					applySGR(String(text[start ..< end]), baseColor: baseColor, color: &color)
					index = text.index(after: end)
					continue
				}
			}
			output.append(NSAttributedString(string: String(text[index ..< nextIndex]), attributes: attributes(color: color)))
			index = nextIndex
		}
		return output
	}

	private static func applySGR(_ raw: String, baseColor: NSColor, color: inout NSColor) {
		let params = raw.split(separator: ";", omittingEmptySubsequences: false).map { Int($0) ?? 0 }
		let values = params.isEmpty ? [0] : params
		for value in values {
			switch value {
			case 0, 39:
				color = baseColor
			case 30:
				color = .black
			case 31:
				color = .systemRed
			case 32:
				color = .systemGreen
			case 33:
				color = .systemYellow
			case 34:
				color = .systemBlue
			case 35:
				color = .systemPurple
			case 36:
				color = .systemTeal
			case 37:
				color = .lightGray
			case 90:
				color = .darkGray
			case 91:
				color = .systemRed
			case 92:
				color = .systemGreen
			case 93:
				color = .systemYellow
			case 94:
				color = .systemBlue
			case 95:
				color = .systemPurple
			case 96:
				color = .systemTeal
			case 97:
				color = .white
			default:
				continue
			}
		}
	}

	private static func attributes(color: NSColor) -> [NSAttributedString.Key: Any] {
		[
			.font: NSFont.monospacedSystemFont(ofSize: 12, weight: .regular),
			.foregroundColor: color,
		]
	}
}
