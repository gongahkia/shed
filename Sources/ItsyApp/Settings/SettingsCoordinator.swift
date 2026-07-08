import AppKit
import Foundation
import ItsyConfig
import ItsySyntax

@MainActor final class SettingsCoordinator: NSObject {
	private let documentController: ItsyDocumentController
	private let onSettingsChange: (ItsySettings) -> Void
	private let onTerminalSettingsChange: (ItsySettings.TerminalSettings) -> Void
	private var settingsWindowController: NSWindowController?
	private var settingsThemePopup: NSPopUpButton?
	private var settingsFontPopup: NSPopUpButton?
	private var settingsFontSizeField: NSTextField?
	private var settingsFontSizeStepper: NSStepper?
	private var settingsLineNumbersButton: NSButton?
	private var settingsTerminalFontSizeField: NSTextField?
	private var settingsTerminalFontSizeStepper: NSStepper?
	private var settingsTerminalScrollbackField: NSTextField?
	private var settingsStatusLabel: NSTextField?
	private let settingsStore: ItsySettingsStore
	private var appSettings: ItsySettings
	private var settingsWarnings: [ItsySettingsWarning]
	private var settingsWatcher: ItsySettingsWatcher?

	var currentSettings: ItsySettings {
		appSettings.normalized()
	}

	init(
		documentController: ItsyDocumentController,
		onSettingsChange: @escaping (ItsySettings) -> Void,
		onTerminalSettingsChange: @escaping (ItsySettings.TerminalSettings) -> Void
	) {
		let store = ItsySettingsStore()
		let loadedSettings = store.load(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: Self.legacySettingsFromDefaults())
		self.documentController = documentController
		self.onSettingsChange = onSettingsChange
		self.onTerminalSettingsChange = onTerminalSettingsChange
		settingsStore = store
		appSettings = loadedSettings.settings
		settingsWarnings = loadedSettings.warnings
		Self.mirrorSettingsToDefaults(appSettings)
		super.init()
		restartSettingsWatcher()
	}

	deinit {
		settingsWatcher?.stop()
	}

	private static func legacySettingsFromDefaults(_ defaults: UserDefaults = .standard) -> ItsySettings {
		var settings = EditorPreferences.legacySettings(defaults: defaults)
		if let themeID = defaults.string(forKey: SyntaxTheme.selectedThemeDefaultsKey), !themeID.isEmpty {
			settings.theme.id = themeID
		}
		return settings
	}

	private static func mirrorSettingsToDefaults(_ settings: ItsySettings, defaults: UserDefaults = .standard) {
		let settings = settings.normalized()
		defaults.set(settings.editor.font, forKey: EditorPreferences.fontNameKey)
		defaults.set(settings.editor.fontSize, forKey: EditorPreferences.fontSizeKey)
		defaults.set(settings.editor.lineNumbers, forKey: EditorPreferences.showLineNumbersKey)
		defaults.set(settings.theme.id, forKey: SyntaxTheme.selectedThemeDefaultsKey)
	}
	@objc func zoomIn(_ sender: Any?) {
		saveAndApplyEditorPreferences(EditorPreferences(settings: appSettings.editor).zoomed(by: 1))
	}

	@objc func zoomOut(_ sender: Any?) {
		saveAndApplyEditorPreferences(EditorPreferences(settings: appSettings.editor).zoomed(by: -1))
	}

	@objc func resetZoom(_ sender: Any?) {
		saveAndApplyEditorPreferences(EditorPreferences(settings: appSettings.editor).resetZoom())
	}

	private func saveAndApplyEditorPreferences(_ preferences: EditorPreferences) {
		preferences.save()
		preferences.apply(to: &appSettings)
		saveAppSettings()
		syncSettingsEditorControls(preferences)
		onSettingsChange(appSettings.normalized())
	}

	private func saveAppSettings() {
		do {
			try settingsStore.save(appSettings)
			settingsWarnings.removeAll()
			Self.mirrorSettingsToDefaults(appSettings)
			setDefaultSettingsStatus()
			publishSettingsChanged()
		} catch {
			settingsStatusLabel?.textColor = .systemRed
			settingsStatusLabel?.stringValue = L10n.string("Failed to save settings: \(String(describing: error))")
		}
	}

	func workspaceDidChange() {
		restartSettingsWatcher()
		reloadSettingsFromDisk()
	}

	@objc func showSettings(_ sender: Any?) {
		let controller = makeSettingsWindowControllerIfNeeded()
		refreshSettingsThemes()
		controller.showWindow(sender)
		controller.window?.center()
		controller.window?.makeKeyAndOrderFront(sender)
		NSApp.activate(ignoringOtherApps: true)
	}

	private func makeSettingsWindowControllerIfNeeded() -> NSWindowController {
		if let controller = settingsWindowController {
			return controller
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 680, height: 430))
		let window = NSWindow(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable],
			backing: .buffered,
			defer: false
		)
		window.title = L10n.string("Settings")
		window.contentView = contentView
		let controller = NSWindowController(window: window)
		configureSettings(contentView)
		settingsWindowController = controller
		return controller
	}

	private func configureSettings(_ contentView: NSView) {
		let themeLabel = settingsLabel("Theme")
		contentView.addSubview(themeLabel)

		let themePopup = NSPopUpButton(frame: .zero, pullsDown: false)
		themePopup.target = self
		themePopup.action = #selector(settingsThemeDidChange(_:))
		themePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(themePopup)

		let fontLabel = settingsLabel("Font")
		contentView.addSubview(fontLabel)

		let fontPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		fontPopup.target = self
		fontPopup.action = #selector(settingsFontDidChange(_:))
		fontPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(fontPopup)

		let sizeLabel = settingsLabel("Size")
		contentView.addSubview(sizeLabel)

		let sizeField = NSTextField(frame: .zero)
		sizeField.alignment = .right
		let sizeFormatter = NumberFormatter()
		sizeFormatter.minimum = NSNumber(value: Double(EditorPreferences.minFontSize))
		sizeFormatter.maximum = NSNumber(value: Double(EditorPreferences.maxFontSize))
		sizeFormatter.minimumFractionDigits = 0
		sizeFormatter.maximumFractionDigits = 1
		sizeField.formatter = sizeFormatter
		sizeField.target = self
		sizeField.action = #selector(settingsFontSizeDidChange(_:))
		sizeField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sizeField)

		let sizeStepper = NSStepper()
		sizeStepper.minValue = Double(EditorPreferences.minFontSize)
		sizeStepper.maxValue = Double(EditorPreferences.maxFontSize)
		sizeStepper.increment = 1
		sizeStepper.target = self
		sizeStepper.action = #selector(settingsFontSizeDidChange(_:))
		sizeStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sizeStepper)

		let lineNumbersButton = NSButton(checkboxWithTitle: L10n.string("Show Line Numbers"), target: self, action: #selector(settingsLineNumbersDidChange(_:)))
		lineNumbersButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(lineNumbersButton)

		let terminalFontSizeLabel = settingsLabel("Terminal Size")
		contentView.addSubview(terminalFontSizeLabel)

		let terminalFontSizeField = NSTextField(frame: .zero)
		terminalFontSizeField.alignment = .right
		let terminalSizeFormatter = NumberFormatter()
		terminalSizeFormatter.minimum = NSNumber(value: ItsySettings.TerminalSettings.minFontSize)
		terminalSizeFormatter.maximum = NSNumber(value: ItsySettings.TerminalSettings.maxFontSize)
		terminalSizeFormatter.minimumFractionDigits = 0
		terminalSizeFormatter.maximumFractionDigits = 1
		terminalFontSizeField.formatter = terminalSizeFormatter
		terminalFontSizeField.target = self
		terminalFontSizeField.action = #selector(settingsTerminalFontSizeDidChange(_:))
		terminalFontSizeField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(terminalFontSizeField)

		let terminalFontSizeStepper = NSStepper()
		terminalFontSizeStepper.minValue = ItsySettings.TerminalSettings.minFontSize
		terminalFontSizeStepper.maxValue = ItsySettings.TerminalSettings.maxFontSize
		terminalFontSizeStepper.increment = 1
		terminalFontSizeStepper.target = self
		terminalFontSizeStepper.action = #selector(settingsTerminalFontSizeDidChange(_:))
		terminalFontSizeStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(terminalFontSizeStepper)

		let scrollbackLabel = settingsLabel("Scrollback")
		contentView.addSubview(scrollbackLabel)

		let scrollbackField = NSTextField(frame: .zero)
		scrollbackField.alignment = .right
		let scrollbackFormatter = NumberFormatter()
		scrollbackFormatter.minimum = NSNumber(value: ItsySettings.TerminalSettings.minScrollbackLines)
		scrollbackFormatter.maximum = NSNumber(value: ItsySettings.TerminalSettings.maxScrollbackLines)
		scrollbackFormatter.allowsFloats = false
		scrollbackField.formatter = scrollbackFormatter
		scrollbackField.target = self
		scrollbackField.action = #selector(settingsTerminalScrollbackDidChange(_:))
		scrollbackField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollbackField)

		let zoomOutButton = NSButton(title: L10n.string("Zoom Out"), target: self, action: #selector(zoomOut(_:)))
		zoomOutButton.bezelStyle = .rounded
		zoomOutButton.translatesAutoresizingMaskIntoConstraints = false

		let resetZoomButton = NSButton(title: L10n.string("Reset Zoom"), target: self, action: #selector(resetZoom(_:)))
		resetZoomButton.bezelStyle = .rounded
		resetZoomButton.translatesAutoresizingMaskIntoConstraints = false

		let zoomInButton = NSButton(title: L10n.string("Zoom In"), target: self, action: #selector(zoomIn(_:)))
		zoomInButton.bezelStyle = .rounded
		zoomInButton.translatesAutoresizingMaskIntoConstraints = false

		let zoomStack = NSStackView(views: [zoomOutButton, resetZoomButton, zoomInButton])
		zoomStack.orientation = .horizontal
		zoomStack.alignment = .centerY
		zoomStack.spacing = 8
		zoomStack.distribution = .fillEqually
		zoomStack.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(zoomStack)

		let reloadButton = NSButton(title: L10n.string("Reload Settings"), target: self, action: #selector(reloadSettings(_:)))
		reloadButton.bezelStyle = .rounded
		reloadButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(reloadButton)

		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)

		NSLayoutConstraint.activate([
			themeLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
			themeLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24),
			themeLabel.widthAnchor.constraint(equalToConstant: 92),
			themePopup.leadingAnchor.constraint(equalTo: themeLabel.trailingAnchor, constant: 12),
			themePopup.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
			themePopup.centerYAnchor.constraint(equalTo: themeLabel.centerYAnchor),
			fontLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			fontLabel.topAnchor.constraint(equalTo: themeLabel.bottomAnchor, constant: 22),
			fontLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			fontPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			fontPopup.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			fontPopup.centerYAnchor.constraint(equalTo: fontLabel.centerYAnchor),
			sizeLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			sizeLabel.topAnchor.constraint(equalTo: fontLabel.bottomAnchor, constant: 22),
			sizeLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			sizeField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			sizeField.widthAnchor.constraint(equalToConstant: 72),
			sizeField.centerYAnchor.constraint(equalTo: sizeLabel.centerYAnchor),
			sizeStepper.leadingAnchor.constraint(equalTo: sizeField.trailingAnchor, constant: 8),
			sizeStepper.centerYAnchor.constraint(equalTo: sizeField.centerYAnchor),
			lineNumbersButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			lineNumbersButton.topAnchor.constraint(equalTo: sizeField.bottomAnchor, constant: 18),
			terminalFontSizeLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			terminalFontSizeLabel.topAnchor.constraint(equalTo: lineNumbersButton.bottomAnchor, constant: 18),
			terminalFontSizeLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			terminalFontSizeField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			terminalFontSizeField.widthAnchor.constraint(equalToConstant: 72),
			terminalFontSizeField.centerYAnchor.constraint(equalTo: terminalFontSizeLabel.centerYAnchor),
			terminalFontSizeStepper.leadingAnchor.constraint(equalTo: terminalFontSizeField.trailingAnchor, constant: 8),
			terminalFontSizeStepper.centerYAnchor.constraint(equalTo: terminalFontSizeField.centerYAnchor),
			scrollbackLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			scrollbackLabel.topAnchor.constraint(equalTo: terminalFontSizeField.bottomAnchor, constant: 22),
			scrollbackLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			scrollbackField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			scrollbackField.widthAnchor.constraint(equalToConstant: 112),
			scrollbackField.centerYAnchor.constraint(equalTo: scrollbackLabel.centerYAnchor),
			zoomStack.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			zoomStack.topAnchor.constraint(equalTo: scrollbackField.bottomAnchor, constant: 18),
			zoomStack.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			reloadButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			reloadButton.topAnchor.constraint(equalTo: zoomStack.bottomAnchor, constant: 16),
			reloadButton.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			statusLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			statusLabel.topAnchor.constraint(equalTo: reloadButton.bottomAnchor, constant: 16),
			statusLabel.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -20),
		])
		settingsThemePopup = themePopup
		settingsFontPopup = fontPopup
		settingsFontSizeField = sizeField
		settingsFontSizeStepper = sizeStepper
		settingsLineNumbersButton = lineNumbersButton
		settingsTerminalFontSizeField = terminalFontSizeField
		settingsTerminalFontSizeStepper = terminalFontSizeStepper
		settingsTerminalScrollbackField = scrollbackField
		settingsStatusLabel = statusLabel
	}

	private func settingsLabel(_ title: String.LocalizationValue) -> NSTextField {
		let label = NSTextField(labelWithString: L10n.string(title))
		label.font = .systemFont(ofSize: 13, weight: .semibold)
		label.translatesAutoresizingMaskIntoConstraints = false
		return label
	}

	private func refreshSettingsThemes() {
		guard let themePopup = settingsThemePopup else {
			return
		}
		let choices = SyntaxTheme.availableChoices()
		themePopup.removeAllItems()
		for choice in choices {
			themePopup.addItem(withTitle: choice.displayName)
			themePopup.lastItem?.representedObject = choice.id
		}
		if let item = themePopup.itemArray.first(where: { $0.representedObject as? String == appSettings.theme.id }) {
			themePopup.select(item)
		} else if let item = themePopup.itemArray.first {
			themePopup.select(item)
		}
		refreshSettingsEditorControls()
		refreshSettingsTerminalControls()
		setDefaultSettingsStatus()
	}

	private func refreshSettingsEditorControls() {
		let preferences = EditorPreferences(settings: appSettings.editor)
		settingsFontPopup?.removeAllItems()
		for fontName in EditorPreferences.availableFontNames() {
			settingsFontPopup?.addItem(withTitle: EditorPreferences.fontDisplayName(for: fontName))
			settingsFontPopup?.lastItem?.representedObject = fontName
		}
		syncSettingsEditorControls(preferences)
	}

	private func syncSettingsEditorControls(_ preferences: EditorPreferences) {
		if let item = settingsFontPopup?.itemArray.first(where: { $0.representedObject as? String == preferences.fontName }) {
			settingsFontPopup?.select(item)
		}
		settingsFontSizeField?.doubleValue = Double(preferences.fontSize)
		settingsFontSizeStepper?.doubleValue = Double(preferences.fontSize)
		settingsLineNumbersButton?.state = preferences.showLineNumbers ? .on : .off
	}

	private func refreshSettingsTerminalControls() {
		let terminal = appSettings.normalized().terminal
		settingsTerminalFontSizeField?.doubleValue = terminal.fontSize
		settingsTerminalFontSizeStepper?.doubleValue = terminal.fontSize
		settingsTerminalScrollbackField?.integerValue = terminal.scrollbackLines
	}

	private func setDefaultSettingsStatus() {
		if let warning = settingsWarnings.first {
			settingsStatusLabel?.textColor = .systemOrange
			settingsStatusLabel?.stringValue = L10n.string("Settings warning: \(warning.description)")
		} else {
			settingsStatusLabel?.textColor = .secondaryLabelColor
			settingsStatusLabel?.stringValue = ItsyWorkspaceController.currentRootURL == nil
				? L10n.string("Config: ~/.config/itsy/settings.toml")
				: L10n.string("Config: global + workspace")
		}
	}

	@objc private func reloadSettings(_ sender: Any?) {
		reloadSettingsFromDisk()
	}

	private func reloadSettingsFromDisk() {
		let result = settingsStore.load(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: Self.legacySettingsFromDefaults())
		appSettings = result.settings
		settingsWarnings = result.warnings
		Self.mirrorSettingsToDefaults(appSettings)
		refreshSettingsThemes()
		onSettingsChange(appSettings.normalized())
		onTerminalSettingsChange(appSettings.terminal)
		reloadSyntaxThemes()
		publishSettingsChanged()
	}

	private func restartSettingsWatcher() {
		settingsWatcher?.stop()
		var urls = [settingsStore.fileURL]
		if let root = ItsyWorkspaceController.currentRootURL {
			urls.append(ItsySettingsStore.workspaceFileURL(workspaceRoot: root))
		}
		let watcher = ItsySettingsWatcher(urls: urls) { [weak self] in
			DispatchQueue.main.async {
				self?.reloadSettingsFromDisk()
			}
		}
		_ = watcher.start()
		settingsWatcher = watcher
	}

	private func publishSettingsChanged() {
		NotificationCenter.default.post(
			name: .itsySettingsChanged,
			object: self,
			userInfo: [ItsySettingsNotificationUserInfoKey.settings: appSettings.normalized()]
		)
	}

	@objc private func settingsThemeDidChange(_ sender: Any?) {
		guard let id = settingsThemePopup?.selectedItem?.representedObject as? String else {
			return
		}
		do {
			_ = try SyntaxTheme.loadChoice(id: id)
			appSettings.theme.id = id
			saveAppSettings()
			reloadSyntaxThemes()
		} catch {
			settingsStatusLabel?.textColor = .systemRed
			settingsStatusLabel?.stringValue = L10n.string("Failed to load selected theme")
		}
	}

	@objc private func settingsFontDidChange(_ sender: Any?) {
		guard let fontName = settingsFontPopup?.selectedItem?.representedObject as? String else {
			return
		}
		var preferences = EditorPreferences(settings: appSettings.editor)
		preferences.fontName = fontName
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsFontSizeDidChange(_ sender: Any?) {
		var preferences = EditorPreferences(settings: appSettings.editor)
		if sender as? NSStepper === settingsFontSizeStepper {
			preferences.fontSize = CGFloat(settingsFontSizeStepper?.doubleValue ?? Double(preferences.fontSize))
		} else {
			preferences.fontSize = CGFloat(settingsFontSizeField?.doubleValue ?? Double(preferences.fontSize))
		}
		preferences.fontSize = EditorPreferences.clampedFontSize(preferences.fontSize)
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsLineNumbersDidChange(_ sender: Any?) {
		var preferences = EditorPreferences(settings: appSettings.editor)
		preferences.showLineNumbers = settingsLineNumbersButton?.state == .on
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsTerminalFontSizeDidChange(_ sender: Any?) {
		if sender as? NSStepper === settingsTerminalFontSizeStepper {
			appSettings.terminal.fontSize = settingsTerminalFontSizeStepper?.doubleValue ?? appSettings.terminal.fontSize
		} else {
			appSettings.terminal.fontSize = settingsTerminalFontSizeField?.doubleValue ?? appSettings.terminal.fontSize
		}
		appSettings = appSettings.normalized()
		saveAppSettings()
		refreshSettingsTerminalControls()
		onTerminalSettingsChange(appSettings.terminal)
	}

	@objc private func settingsTerminalScrollbackDidChange(_ sender: Any?) {
		appSettings.terminal.scrollbackLines = settingsTerminalScrollbackField?.integerValue ?? appSettings.terminal.scrollbackLines
		appSettings = appSettings.normalized()
		saveAppSettings()
		refreshSettingsTerminalControls()
		onTerminalSettingsChange(appSettings.terminal)
	}

	private func reloadSyntaxThemes() {
		for document in documentController.documents {
			(document as? ItsyDocument)?.reloadSyntaxTheme()
		}
	}

}
