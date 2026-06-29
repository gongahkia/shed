import AppKit
import ItsySyntax

final class ThemeSettingsWindowController: NSWindowController {
	private let onThemeChange: () -> Void
	private let themePopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let statusLabel = NSTextField(labelWithString: "")

	init(onThemeChange: @escaping () -> Void) {
		self.onThemeChange = onThemeChange
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 420, height: 148))
		let window = NSWindow(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable],
			backing: .buffered,
			defer: false
		)
		window.title = L10n.string("Settings")
		window.contentView = contentView
		super.init(window: window)
		configure(contentView)
	}

	required init?(coder: NSCoder) {
		nil
	}

	override func showWindow(_ sender: Any?) {
		refreshThemes()
		super.showWindow(sender)
		window?.center()
		window?.makeKeyAndOrderFront(sender)
		NSApp.activate(ignoringOtherApps: true)
	}

	private func configure(_ contentView: NSView) {
		let label = NSTextField(labelWithString: L10n.string("Theme"))
		label.font = .systemFont(ofSize: 13, weight: .semibold)
		label.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(label)

		themePopup.target = self
		themePopup.action = #selector(themeDidChange(_:))
		themePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(themePopup)

		let reloadButton = NSButton(title: L10n.string("Reload Themes"), target: self, action: #selector(reloadThemes(_:)))
		reloadButton.bezelStyle = .rounded
		reloadButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(reloadButton)

		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)

		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
			label.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24),
			label.widthAnchor.constraint(equalToConstant: 80),
			themePopup.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 12),
			themePopup.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
			themePopup.centerYAnchor.constraint(equalTo: label.centerYAnchor),
			reloadButton.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			reloadButton.topAnchor.constraint(equalTo: themePopup.bottomAnchor, constant: 18),
			statusLabel.leadingAnchor.constraint(equalTo: label.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: reloadButton.leadingAnchor, constant: -12),
			statusLabel.centerYAnchor.constraint(equalTo: reloadButton.centerYAnchor),
		])
	}

	private func refreshThemes() {
		let choices = SyntaxTheme.availableChoices()
		let selectedID = UserDefaults.standard.string(forKey: SyntaxTheme.selectedThemeDefaultsKey) ?? SyntaxTheme.defaultChoiceID
		themePopup.removeAllItems()
		for choice in choices {
			themePopup.addItem(withTitle: choice.displayName)
			themePopup.lastItem?.representedObject = choice.id
		}
		if let item = themePopup.itemArray.first(where: { $0.representedObject as? String == selectedID }) {
			themePopup.select(item)
		} else if let item = themePopup.itemArray.first {
			themePopup.select(item)
		}
		setDefaultStatus()
	}

	private func setDefaultStatus() {
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.stringValue = L10n.string("Custom themes: ~/.config/itsy/themes/*.toml")
	}

	@objc private func reloadThemes(_ sender: Any?) {
		refreshThemes()
	}

	@objc private func themeDidChange(_ sender: Any?) {
		guard let id = themePopup.selectedItem?.representedObject as? String else {
			return
		}
		do {
			_ = try SyntaxTheme.loadChoice(id: id)
			UserDefaults.standard.set(id, forKey: SyntaxTheme.selectedThemeDefaultsKey)
			setDefaultStatus()
			onThemeChange()
		} catch {
			statusLabel.textColor = .systemRed
			statusLabel.stringValue = L10n.string("Failed to load selected theme")
		}
	}
}
