import AppKit

final class RenamePopoverController: NSViewController, NSTextFieldDelegate {
	private let initialName: String
	private let submit: (String) -> Void
	private let cancel: () -> Void
	private let field = NSTextField()

	init(initialName: String, submit: @escaping (String) -> Void, cancel: @escaping () -> Void) {
		self.initialName = initialName
		self.submit = submit
		self.cancel = cancel
		super.init(nibName: nil, bundle: nil)
	}

	required init?(coder: NSCoder) {
		nil
	}

	override func loadView() {
		let stack = NSStackView()
		stack.orientation = .vertical
		stack.spacing = 8
		stack.edgeInsets = NSEdgeInsets(top: 10, left: 10, bottom: 10, right: 10)
		stack.translatesAutoresizingMaskIntoConstraints = false

		field.stringValue = initialName
		field.target = self
		field.action = #selector(commit)
		field.delegate = self
		field.translatesAutoresizingMaskIntoConstraints = false

		let buttons = NSStackView()
		buttons.orientation = .horizontal
		buttons.alignment = .centerY
		buttons.distribution = .fillEqually
		buttons.spacing = 6
		let cancelButton = NSButton(title: L10n.string("Cancel"), target: self, action: #selector(cancelRename))
		let renameButton = NSButton(title: L10n.string("Rename"), target: self, action: #selector(commit))
		renameButton.keyEquivalent = "\r"
		buttons.addArrangedSubview(cancelButton)
		buttons.addArrangedSubview(renameButton)

		stack.addArrangedSubview(field)
		stack.addArrangedSubview(buttons)

		let root = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: 76))
		root.addSubview(stack)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: root.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: root.trailingAnchor),
			stack.topAnchor.constraint(equalTo: root.topAnchor),
			stack.bottomAnchor.constraint(equalTo: root.bottomAnchor),
			field.widthAnchor.constraint(equalToConstant: 240),
		])
		view = root
	}

	override func viewDidAppear() {
		super.viewDidAppear()
		view.window?.makeFirstResponder(field)
		field.selectText(nil)
	}

	@objc private func commit() {
		let name = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			return
		}
		submit(name)
	}

	@objc private func cancelRename() {
		cancel()
	}
}
