import AppKit
import ItsyLSP

final class CodeActionPopoverController: NSViewController {
	private let entries: [LSPCodeActionEntry]
	private let select: (LSPCodeActionEntry) -> Void

	init(entries: [LSPCodeActionEntry], select: @escaping (LSPCodeActionEntry) -> Void) {
		self.entries = entries
		self.select = select
		super.init(nibName: nil, bundle: nil)
	}

	required init?(coder: NSCoder) {
		nil
	}

	override func loadView() {
		let stack = NSStackView()
		stack.orientation = .vertical
		stack.alignment = .width
		stack.spacing = 2
		stack.edgeInsets = NSEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
		stack.translatesAutoresizingMaskIntoConstraints = false

		for (index, entry) in entries.enumerated() {
			let button = NSButton(title: entry.title, target: self, action: #selector(selectAction(_:)))
			button.tag = index
			button.isBordered = false
			button.alignment = .left
			button.font = .systemFont(ofSize: 12)
			button.lineBreakMode = .byTruncatingTail
			button.translatesAutoresizingMaskIntoConstraints = false
			button.heightAnchor.constraint(greaterThanOrEqualToConstant: 26).isActive = true
			stack.addArrangedSubview(button)
		}

		let scroll = NSScrollView()
		scroll.drawsBackground = false
		scroll.borderType = .noBorder
		scroll.hasVerticalScroller = entries.count > 8
		scroll.documentView = stack
		scroll.translatesAutoresizingMaskIntoConstraints = false

		let height = min(max(34, entries.count * 30 + 12), 252)
		let root = NSView(frame: NSRect(x: 0, y: 0, width: 320, height: height))
		root.addSubview(scroll)
		NSLayoutConstraint.activate([
			scroll.leadingAnchor.constraint(equalTo: root.leadingAnchor),
			scroll.trailingAnchor.constraint(equalTo: root.trailingAnchor),
			scroll.topAnchor.constraint(equalTo: root.topAnchor),
			scroll.bottomAnchor.constraint(equalTo: root.bottomAnchor),
			stack.widthAnchor.constraint(equalToConstant: 308),
		])
		view = root
	}

	@objc private func selectAction(_ sender: NSButton) {
		guard entries.indices.contains(sender.tag) else {
			return
		}
		select(entries[sender.tag])
	}
}
