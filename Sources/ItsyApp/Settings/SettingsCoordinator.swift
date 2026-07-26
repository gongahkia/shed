import AppKit
import Foundation
import ItsyConfig
import ItsyEditor
import ItsySyntax
import ItsyWorkbenchDSL
import ItsyWorkbenchLayout

@MainActor final class SettingsCoordinator: NSObject {
	private let documentController: ItsyDocumentController
	private let onSettingsChange: (ItsySettings) -> Void
	private let onTerminalSettingsChange: (ItsySettings.TerminalSettings) -> Void
	private var settingsWindowController: NSWindowController?
	private var settingsThemePopup: NSPopUpButton?
	private var settingsFontPopup: NSPopUpButton?
	private var settingsFontRenderingPopup: NSPopUpButton?
	private var settingsKeymapPopup: NSPopUpButton?
	private var settingsTabGroupsPopup: NSPopUpButton?
	private var settingsLineNumberModePopup: NSPopUpButton?
	private var settingsWrapPopup: NSPopUpButton?
	private var settingsWrapColumnField: NSTextField?
	private var settingsWrapColumnStepper: NSStepper?
	private var settingsTabWidthField: NSTextField?
	private var settingsTabWidthStepper: NSStepper?
	private var settingsUseSpacesButton: NSButton?
	private var settingsAutoPairsButton: NSButton?
	private var settingsSmartIndentButton: NSButton?
	private var settingsMultipleSelectionsButton: NSButton?
	private var settingsFindRegexButton: NSButton?
	private var settingsFindCaseButton: NSButton?
	private var settingsFindWholeWordButton: NSButton?
	private var settingsRecoveryJournalButton: NSButton?
	private var settingsAutomaticallyCheckForUpdatesButton: NSButton?
	private var settingsWorkbenchProfilePopup: NSPopUpButton?
	private var settingsWorkbenchFileTreePopup: NSPopUpButton?
	private var settingsWorkbenchTerminalPopup: NSPopUpButton?
	private var settingsWorkbenchGitPopup: NSPopUpButton?
	private var settingsTerminalPresentationPopup: NSPopUpButton?
	private var settingsGitPresentationPopup: NSPopUpButton?
	private var settingsDebuggerPresentationPopup: NSPopUpButton?
	private var settingsSidebarVisibleButton: NSButton?
	private var settingsSidebarPositionPopup: NSPopUpButton?
	private var settingsSidebarWidthField: NSTextField?
	private var settingsSidebarWidthStepper: NSStepper?
	private var settingsTabBarVisibleButton: NSButton?
	private var settingsStatusBarVisibleButton: NSButton?
	private var settingsInterfaceScaleField: NSTextField?
	private var settingsInterfaceScaleStepper: NSStepper?
	private var settingsFontSizeField: NSTextField?
	private var settingsFontSizeStepper: NSStepper?
	private var settingsTerminalFontSizeField: NSTextField?
	private var settingsTerminalFontSizeStepper: NSStepper?
	private var settingsTerminalScrollbackField: NSTextField?
	private var settingsStatusLabel: NSTextField?
	private var lspConfigurationPanel: LSPServerConfigurationPanel?
	private var managedSupportPanel: ManagedSupportPanel?
	private var settingsInspectorPanel: SettingsInspectorPanel?
	private let settingsStore: ItsySettingsStore
	private var appSettings: ItsySettings
	private var settingsResolution: ItsySettingsResolution
	private var settingsWarnings: [ItsySettingsWarning]
	private var settingsWatcher: ItsySettingsWatcher?
	private var settingsFontChoicesLoaded = false
	private var settingsFontChoicesLoading = false

	var currentSettings: ItsySettings {
		appSettings.normalized()
	}

	var workbenchDiagnostic: String? {
		settingsWarnings.first(where: { $0.key == "workbench" })?.description
	}

	init(
		documentController: ItsyDocumentController,
		onSettingsChange: @escaping (ItsySettings) -> Void,
		onTerminalSettingsChange: @escaping (ItsySettings.TerminalSettings) -> Void
	) {
		let store = ItsySettingsStore()
		let fallback = Self.legacySettingsFromDefaults()
		let loadedSettings = store.load(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: fallback)
		let resolution = store.resolve(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: fallback)
		self.documentController = documentController
		self.onSettingsChange = onSettingsChange
		self.onTerminalSettingsChange = onTerminalSettingsChange
		settingsStore = store
		appSettings = resolution.settings
		settingsResolution = resolution
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
			settingsResolution = settingsStore.resolve(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: Self.legacySettingsFromDefaults())
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
		refreshSettingsEditorControls()
		refreshSettingsTerminalControls()
		controller.showWindow(sender)
		controller.window?.center()
		controller.window?.makeKeyAndOrderFront(sender)
		NSApp.activate(ignoringOtherApps: true)
		loadSettingsFontChoicesIfNeeded()
	}

	func settingsContentViewForTesting() -> NSView {
		makeSettingsWindowControllerIfNeeded().window?.contentView ?? NSView()
	}

	private func makeSettingsWindowControllerIfNeeded() -> NSWindowController {
		if let controller = settingsWindowController {
			return controller
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 680, height: 1_300))
		let scrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 680, height: 720))
		scrollView.hasVerticalScroller = true
		scrollView.autohidesScrollers = true
		scrollView.documentView = contentView
		let window = NSWindow(
			contentRect: scrollView.frame,
			styleMask: [.titled, .closable],
			backing: .buffered,
			defer: false
		)
		window.title = L10n.string("Settings")
		window.contentView = scrollView
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

		let keymapLabel = settingsLabel("Keymap")
		contentView.addSubview(keymapLabel)

		let keymapPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		keymapPopup.addItem(withTitle: L10n.string("Plain"))
		keymapPopup.lastItem?.representedObject = ItsySettings.KeymapMode.plain.rawValue
		keymapPopup.addItem(withTitle: L10n.string("Vim"))
		keymapPopup.lastItem?.representedObject = ItsySettings.KeymapMode.vim.rawValue
		keymapPopup.addItem(withTitle: L10n.string("Emacs"))
		keymapPopup.lastItem?.representedObject = ItsySettings.KeymapMode.emacs.rawValue
		keymapPopup.target = self
		keymapPopup.action = #selector(settingsKeymapDidChange(_:))
		keymapPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(keymapPopup)

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

		let fontRenderingLabel = settingsLabel("Font Rendering")
		contentView.addSubview(fontRenderingLabel)
		let fontRenderingPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		fontRenderingPopup.addItem(withTitle: L10n.string("Grayscale"))
		fontRenderingPopup.lastItem?.representedObject = ItsySettings.FontRenderingMode.grayscale.rawValue
		fontRenderingPopup.addItem(withTitle: L10n.string("Subpixel"))
		fontRenderingPopup.lastItem?.representedObject = ItsySettings.FontRenderingMode.subpixel.rawValue
		fontRenderingPopup.target = self
		fontRenderingPopup.action = #selector(settingsFontRenderingDidChange(_:))
		fontRenderingPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(fontRenderingPopup)

		let lineNumberModeLabel = settingsLabel("Line Numbers")
		contentView.addSubview(lineNumberModeLabel)

		let lineNumberModePopup = NSPopUpButton(frame: .zero, pullsDown: false)
		lineNumberModePopup.addItem(withTitle: L10n.string("Off"))
		lineNumberModePopup.lastItem?.representedObject = ItsySettings.LineNumberMode.off.rawValue
		lineNumberModePopup.addItem(withTitle: L10n.string("Absolute"))
		lineNumberModePopup.lastItem?.representedObject = ItsySettings.LineNumberMode.absolute.rawValue
		lineNumberModePopup.addItem(withTitle: L10n.string("Relative"))
		lineNumberModePopup.lastItem?.representedObject = ItsySettings.LineNumberMode.relative.rawValue
		lineNumberModePopup.target = self
		lineNumberModePopup.action = #selector(settingsLineNumberModeDidChange(_:))
		lineNumberModePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(lineNumberModePopup)

		let tabGroupsLabel = settingsLabel("Tab Groups")
		contentView.addSubview(tabGroupsLabel)

		let tabGroupsPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		tabGroupsPopup.addItem(withTitle: L10n.string("Window"))
		tabGroupsPopup.lastItem?.representedObject = ItsySettings.TabGroupScope.window.rawValue
		tabGroupsPopup.addItem(withTitle: L10n.string("Pane"))
		tabGroupsPopup.lastItem?.representedObject = ItsySettings.TabGroupScope.pane.rawValue
		tabGroupsPopup.target = self
		tabGroupsPopup.action = #selector(settingsTabGroupsDidChange(_:))
		tabGroupsPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(tabGroupsPopup)

		let wrapLabel = settingsLabel("Wrap")
		contentView.addSubview(wrapLabel)

		let wrapPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		wrapPopup.addItem(withTitle: L10n.string("Off"))
		wrapPopup.lastItem?.representedObject = ItsySettings.WrapMode.none.rawValue
		wrapPopup.addItem(withTitle: L10n.string("Soft"))
		wrapPopup.lastItem?.representedObject = ItsySettings.WrapMode.soft.rawValue
		wrapPopup.addItem(withTitle: L10n.string("Hard"))
		wrapPopup.lastItem?.representedObject = ItsySettings.WrapMode.hard.rawValue
		wrapPopup.target = self
		wrapPopup.action = #selector(settingsWrapDidChange(_:))
		wrapPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(wrapPopup)

		let wrapColumnField = NSTextField(frame: .zero)
		wrapColumnField.alignment = .right
		let wrapColumnFormatter = NumberFormatter()
		wrapColumnFormatter.minimum = NSNumber(value: ItsySettings.EditorSettings.minWrapColumn)
		wrapColumnFormatter.maximum = NSNumber(value: ItsySettings.EditorSettings.maxWrapColumn)
		wrapColumnFormatter.allowsFloats = false
		wrapColumnField.formatter = wrapColumnFormatter
		wrapColumnField.target = self
		wrapColumnField.action = #selector(settingsWrapColumnDidChange(_:))
		wrapColumnField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(wrapColumnField)

		let wrapColumnStepper = NSStepper()
		wrapColumnStepper.minValue = Double(ItsySettings.EditorSettings.minWrapColumn)
		wrapColumnStepper.maxValue = Double(ItsySettings.EditorSettings.maxWrapColumn)
		wrapColumnStepper.increment = 1
		wrapColumnStepper.target = self
		wrapColumnStepper.action = #selector(settingsWrapColumnDidChange(_:))
		wrapColumnStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(wrapColumnStepper)

		let tabWidthLabel = settingsLabel("Tab Width")
		contentView.addSubview(tabWidthLabel)

		let tabWidthField = NSTextField(frame: .zero)
		tabWidthField.alignment = .right
		let tabWidthFormatter = NumberFormatter()
		tabWidthFormatter.minimum = NSNumber(value: ItsySettings.EditorSettings.minTabWidth)
		tabWidthFormatter.maximum = NSNumber(value: ItsySettings.EditorSettings.maxTabWidth)
		tabWidthFormatter.allowsFloats = false
		tabWidthField.formatter = tabWidthFormatter
		tabWidthField.target = self
		tabWidthField.action = #selector(settingsTabWidthDidChange(_:))
		tabWidthField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(tabWidthField)

		let tabWidthStepper = NSStepper()
		tabWidthStepper.minValue = Double(ItsySettings.EditorSettings.minTabWidth)
		tabWidthStepper.maxValue = Double(ItsySettings.EditorSettings.maxTabWidth)
		tabWidthStepper.increment = 1
		tabWidthStepper.target = self
		tabWidthStepper.action = #selector(settingsTabWidthDidChange(_:))
		tabWidthStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(tabWidthStepper)

		let useSpacesButton = settingsCheckbox("Indent Using Spaces", action: #selector(settingsUseSpacesDidChange(_:)))
		let autoPairsButton = settingsCheckbox("Auto Pairs", action: #selector(settingsAutoPairsDidChange(_:)))
		let smartIndentButton = settingsCheckbox("Smart Indent", action: #selector(settingsSmartIndentDidChange(_:)))
		let multipleSelectionsButton = settingsCheckbox("Multiple Cursors", action: #selector(settingsMultipleSelectionsDidChange(_:)))
		for button in [useSpacesButton, autoPairsButton, smartIndentButton, multipleSelectionsButton] {
			contentView.addSubview(button)
		}

		let findDefaultsLabel = settingsLabel("Find Defaults")
		contentView.addSubview(findDefaultsLabel)
		let findRegexButton = settingsCheckbox("Regex", action: #selector(settingsFindDidChange(_:)))
		let findCaseButton = settingsCheckbox("Case", action: #selector(settingsFindDidChange(_:)))
		let findWholeWordButton = settingsCheckbox("Word", action: #selector(settingsFindDidChange(_:)))
		let findStack = NSStackView(views: [findRegexButton, findCaseButton, findWholeWordButton])
		findStack.orientation = .horizontal
		findStack.alignment = .centerY
		findStack.spacing = 12
		findStack.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(findStack)

		let recoveryJournalButton = settingsCheckbox("Keep Recovery Journal", action: #selector(settingsRecoveryJournalDidChange(_:)))
		contentView.addSubview(recoveryJournalButton)
		let automaticallyCheckForUpdatesButton = settingsCheckbox("Automatically Check for Updates", action: #selector(settingsAutomaticallyCheckForUpdatesDidChange(_:)))
		contentView.addSubview(automaticallyCheckForUpdatesButton)
		let workbenchProfileLabel = settingsLabel("Workbench Profile")
		contentView.addSubview(workbenchProfileLabel)
		let workbenchProfilePopup = NSPopUpButton(frame: .zero, pullsDown: false)
		for profile in WorkbenchProfile.allCases {
			workbenchProfilePopup.addItem(withTitle: profile.rawValue.capitalized)
			workbenchProfilePopup.lastItem?.representedObject = profile.rawValue
		}
		workbenchProfilePopup.target = self
		workbenchProfilePopup.action = #selector(settingsWorkbenchProfileDidChange(_:))
		workbenchProfilePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(workbenchProfilePopup)
		let workbenchFileTreeLabel = settingsLabel("File Tree")
		contentView.addSubview(workbenchFileTreeLabel)
		let workbenchFileTreePopup = workbenchVisibilityPopup(identifier: "workbench.file_tree", label: "Workbench file tree visibility")
		workbenchFileTreePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(workbenchFileTreePopup)
		let workbenchTerminalLabel = settingsLabel("Terminal")
		contentView.addSubview(workbenchTerminalLabel)
		let workbenchTerminalPopup = workbenchVisibilityPopup(identifier: "workbench.terminal", label: "Workbench terminal visibility")
		workbenchTerminalPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(workbenchTerminalPopup)
		let workbenchGitLabel = settingsLabel("Git")
		contentView.addSubview(workbenchGitLabel)
		let workbenchGitPopup = workbenchVisibilityPopup(identifier: "workbench.git", label: "Workbench Git visibility")
		workbenchGitPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(workbenchGitPopup)
		let terminalPresentationLabel = settingsLabel("Terminal Presentation")
		contentView.addSubview(terminalPresentationLabel)
		let terminalPresentationPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		for presentation in [ItsySettings.TerminalSettings.Presentation.bottom, .window] {
			terminalPresentationPopup.addItem(withTitle: presentation.rawValue.capitalized)
			terminalPresentationPopup.lastItem?.representedObject = presentation.rawValue
		}
		terminalPresentationPopup.identifier = NSUserInterfaceItemIdentifier("terminal.presentation")
		terminalPresentationPopup.setAccessibilityLabel("Terminal presentation")
		terminalPresentationPopup.target = self
		terminalPresentationPopup.action = #selector(settingsTerminalPresentationDidChange(_:))
		terminalPresentationPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(terminalPresentationPopup)
		let gitPresentationLabel = settingsLabel("Git Presentation")
		contentView.addSubview(gitPresentationLabel)
		let gitPresentationPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		for presentation in [ItsySettings.GitSettings.Presentation.sidebar, .window] {
			gitPresentationPopup.addItem(withTitle: presentation.rawValue.capitalized)
			gitPresentationPopup.lastItem?.representedObject = presentation.rawValue
		}
		gitPresentationPopup.identifier = NSUserInterfaceItemIdentifier("git.presentation")
		gitPresentationPopup.setAccessibilityLabel("Git presentation")
		gitPresentationPopup.target = self
		gitPresentationPopup.action = #selector(settingsGitPresentationDidChange(_:))
		gitPresentationPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(gitPresentationPopup)
		let debuggerPresentationLabel = settingsLabel("Debugger Presentation")
		contentView.addSubview(debuggerPresentationLabel)
		let debuggerPresentationPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		for presentation in [ItsySettings.DebuggerSettings.Presentation.sidebar, .window] {
			debuggerPresentationPopup.addItem(withTitle: presentation.rawValue.capitalized)
			debuggerPresentationPopup.lastItem?.representedObject = presentation.rawValue
		}
		debuggerPresentationPopup.identifier = NSUserInterfaceItemIdentifier("debugger.presentation")
		debuggerPresentationPopup.setAccessibilityLabel("Debugger presentation")
		debuggerPresentationPopup.target = self
		debuggerPresentationPopup.action = #selector(settingsDebuggerPresentationDidChange(_:))
		debuggerPresentationPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(debuggerPresentationPopup)

		let sidebarVisibleButton = settingsCheckbox("Show Sidebar", action: #selector(settingsSidebarVisibleDidChange(_:)))
		contentView.addSubview(sidebarVisibleButton)
		let sidebarPositionLabel = settingsLabel("Sidebar Position")
		contentView.addSubview(sidebarPositionLabel)
		let sidebarPositionPopup = NSPopUpButton(frame: .zero, pullsDown: false)
		sidebarPositionPopup.addItem(withTitle: L10n.string("Leading"))
		sidebarPositionPopup.lastItem?.representedObject = ItsySettings.SidebarPosition.leading.rawValue
		sidebarPositionPopup.addItem(withTitle: L10n.string("Trailing"))
		sidebarPositionPopup.lastItem?.representedObject = ItsySettings.SidebarPosition.trailing.rawValue
		sidebarPositionPopup.target = self
		sidebarPositionPopup.action = #selector(settingsSidebarPositionDidChange(_:))
		sidebarPositionPopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sidebarPositionPopup)

		let sidebarWidthLabel = settingsLabel("Sidebar Width")
		contentView.addSubview(sidebarWidthLabel)
		let sidebarWidthField = NSTextField(frame: .zero)
		sidebarWidthField.alignment = .right
		let sidebarWidthFormatter = NumberFormatter()
		sidebarWidthFormatter.minimum = NSNumber(value: ItsySettings.LayoutSettings.minSidebarWidth)
		sidebarWidthFormatter.maximum = NSNumber(value: ItsySettings.LayoutSettings.maxSidebarWidth)
		sidebarWidthFormatter.allowsFloats = false
		sidebarWidthField.formatter = sidebarWidthFormatter
		sidebarWidthField.target = self
		sidebarWidthField.action = #selector(settingsSidebarWidthDidChange(_:))
		sidebarWidthField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sidebarWidthField)
		let sidebarWidthStepper = NSStepper()
		sidebarWidthStepper.minValue = Double(ItsySettings.LayoutSettings.minSidebarWidth)
		sidebarWidthStepper.maxValue = Double(ItsySettings.LayoutSettings.maxSidebarWidth)
		sidebarWidthStepper.increment = 10
		sidebarWidthStepper.target = self
		sidebarWidthStepper.action = #selector(settingsSidebarWidthDidChange(_:))
		sidebarWidthStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sidebarWidthStepper)

		let tabBarVisibleButton = settingsCheckbox("Show Tab Bar", action: #selector(settingsTabBarVisibleDidChange(_:)))
		let statusBarVisibleButton = settingsCheckbox("Show Status Bar", action: #selector(settingsStatusBarVisibleDidChange(_:)))
		contentView.addSubview(tabBarVisibleButton)
		contentView.addSubview(statusBarVisibleButton)

		let interfaceScaleLabel = settingsLabel("Interface Scale")
		contentView.addSubview(interfaceScaleLabel)
		let interfaceScaleField = NSTextField(frame: .zero)
		interfaceScaleField.alignment = .right
		let interfaceScaleFormatter = NumberFormatter()
		interfaceScaleFormatter.minimum = NSNumber(value: ItsySettings.LayoutSettings.minInterfaceScale)
		interfaceScaleFormatter.maximum = NSNumber(value: ItsySettings.LayoutSettings.maxInterfaceScale)
		interfaceScaleFormatter.minimumFractionDigits = 1
		interfaceScaleFormatter.maximumFractionDigits = 2
		interfaceScaleField.formatter = interfaceScaleFormatter
		interfaceScaleField.target = self
		interfaceScaleField.action = #selector(settingsInterfaceScaleDidChange(_:))
		interfaceScaleField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(interfaceScaleField)
		let interfaceScaleStepper = NSStepper()
		interfaceScaleStepper.minValue = ItsySettings.LayoutSettings.minInterfaceScale
		interfaceScaleStepper.maxValue = ItsySettings.LayoutSettings.maxInterfaceScale
		interfaceScaleStepper.increment = 0.1
		interfaceScaleStepper.target = self
		interfaceScaleStepper.action = #selector(settingsInterfaceScaleDidChange(_:))
		interfaceScaleStepper.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(interfaceScaleStepper)

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

		let languageServersButton = NSButton(title: L10n.string("Language Servers…"), target: self, action: #selector(showLSPConfiguration(_:)))
		languageServersButton.bezelStyle = .rounded
		languageServersButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(languageServersButton)

		let supportButton = NSButton(title: L10n.string("Language & Debugger Support…"), target: self, action: #selector(showManagedSupport(_:)))
		supportButton.bezelStyle = .rounded
		supportButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(supportButton)

		let catalogButton = NSButton(title: L10n.string("Settings Catalog…"), target: self, action: #selector(showSettingsCatalog(_:)))
		catalogButton.bezelStyle = .rounded
		catalogButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(catalogButton)

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
			keymapLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			keymapLabel.topAnchor.constraint(equalTo: themeLabel.bottomAnchor, constant: 22),
			keymapLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			keymapPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			keymapPopup.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			keymapPopup.centerYAnchor.constraint(equalTo: keymapLabel.centerYAnchor),
			fontLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			fontLabel.topAnchor.constraint(equalTo: keymapLabel.bottomAnchor, constant: 22),
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
			fontRenderingLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			fontRenderingLabel.topAnchor.constraint(equalTo: sizeField.bottomAnchor, constant: 18),
			fontRenderingLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			fontRenderingPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			fontRenderingPopup.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			fontRenderingPopup.centerYAnchor.constraint(equalTo: fontRenderingLabel.centerYAnchor),
			lineNumberModeLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			lineNumberModeLabel.topAnchor.constraint(equalTo: fontRenderingPopup.bottomAnchor, constant: 18),
			lineNumberModeLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			lineNumberModePopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			lineNumberModePopup.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			lineNumberModePopup.centerYAnchor.constraint(equalTo: lineNumberModeLabel.centerYAnchor),
			tabGroupsLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			tabGroupsLabel.topAnchor.constraint(equalTo: lineNumberModePopup.bottomAnchor, constant: 18),
			tabGroupsLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			tabGroupsPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			tabGroupsPopup.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			tabGroupsPopup.centerYAnchor.constraint(equalTo: tabGroupsLabel.centerYAnchor),
			wrapLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			wrapLabel.topAnchor.constraint(equalTo: tabGroupsPopup.bottomAnchor, constant: 18),
			wrapLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			wrapPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			wrapPopup.centerYAnchor.constraint(equalTo: wrapLabel.centerYAnchor),
			wrapColumnField.leadingAnchor.constraint(equalTo: wrapPopup.trailingAnchor, constant: 8),
			wrapColumnField.widthAnchor.constraint(equalToConstant: 72),
			wrapColumnField.centerYAnchor.constraint(equalTo: wrapPopup.centerYAnchor),
			wrapColumnStepper.leadingAnchor.constraint(equalTo: wrapColumnField.trailingAnchor, constant: 8),
			wrapColumnStepper.centerYAnchor.constraint(equalTo: wrapColumnField.centerYAnchor),
			wrapColumnStepper.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			tabWidthLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			tabWidthLabel.topAnchor.constraint(equalTo: wrapPopup.bottomAnchor, constant: 18),
			tabWidthLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			tabWidthField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			tabWidthField.widthAnchor.constraint(equalToConstant: 72),
			tabWidthField.centerYAnchor.constraint(equalTo: tabWidthLabel.centerYAnchor),
			tabWidthStepper.leadingAnchor.constraint(equalTo: tabWidthField.trailingAnchor, constant: 8),
			tabWidthStepper.centerYAnchor.constraint(equalTo: tabWidthField.centerYAnchor),
			useSpacesButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			useSpacesButton.topAnchor.constraint(equalTo: tabWidthField.bottomAnchor, constant: 12),
			autoPairsButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			autoPairsButton.topAnchor.constraint(equalTo: useSpacesButton.bottomAnchor, constant: 8),
			smartIndentButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			smartIndentButton.topAnchor.constraint(equalTo: autoPairsButton.bottomAnchor, constant: 8),
			multipleSelectionsButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			multipleSelectionsButton.topAnchor.constraint(equalTo: smartIndentButton.bottomAnchor, constant: 8),
			findDefaultsLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			findDefaultsLabel.topAnchor.constraint(equalTo: multipleSelectionsButton.bottomAnchor, constant: 16),
			findDefaultsLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			findStack.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			findStack.centerYAnchor.constraint(equalTo: findDefaultsLabel.centerYAnchor),
			recoveryJournalButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			recoveryJournalButton.topAnchor.constraint(equalTo: findStack.bottomAnchor, constant: 12),
			automaticallyCheckForUpdatesButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			automaticallyCheckForUpdatesButton.topAnchor.constraint(equalTo: recoveryJournalButton.bottomAnchor, constant: 8),
			workbenchProfileLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			workbenchProfileLabel.topAnchor.constraint(equalTo: automaticallyCheckForUpdatesButton.bottomAnchor, constant: 16),
			workbenchProfileLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			workbenchProfilePopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			workbenchProfilePopup.centerYAnchor.constraint(equalTo: workbenchProfileLabel.centerYAnchor),
			workbenchFileTreeLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			workbenchFileTreeLabel.topAnchor.constraint(equalTo: workbenchProfilePopup.bottomAnchor, constant: 12),
			workbenchFileTreeLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			workbenchFileTreePopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			workbenchFileTreePopup.centerYAnchor.constraint(equalTo: workbenchFileTreeLabel.centerYAnchor),
			workbenchTerminalLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			workbenchTerminalLabel.topAnchor.constraint(equalTo: workbenchFileTreePopup.bottomAnchor, constant: 12),
			workbenchTerminalLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			workbenchTerminalPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			workbenchTerminalPopup.centerYAnchor.constraint(equalTo: workbenchTerminalLabel.centerYAnchor),
			workbenchGitLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			workbenchGitLabel.topAnchor.constraint(equalTo: workbenchTerminalPopup.bottomAnchor, constant: 12),
			workbenchGitLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			workbenchGitPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			workbenchGitPopup.centerYAnchor.constraint(equalTo: workbenchGitLabel.centerYAnchor),
			terminalPresentationLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			terminalPresentationLabel.topAnchor.constraint(equalTo: workbenchGitPopup.bottomAnchor, constant: 12),
			terminalPresentationLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			terminalPresentationPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			terminalPresentationPopup.centerYAnchor.constraint(equalTo: terminalPresentationLabel.centerYAnchor),
			gitPresentationLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			gitPresentationLabel.topAnchor.constraint(equalTo: terminalPresentationPopup.bottomAnchor, constant: 12),
			gitPresentationLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			gitPresentationPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			gitPresentationPopup.centerYAnchor.constraint(equalTo: gitPresentationLabel.centerYAnchor),
			debuggerPresentationLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			debuggerPresentationLabel.topAnchor.constraint(equalTo: gitPresentationPopup.bottomAnchor, constant: 12),
			debuggerPresentationLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			debuggerPresentationPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			debuggerPresentationPopup.centerYAnchor.constraint(equalTo: debuggerPresentationLabel.centerYAnchor),
			sidebarVisibleButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			sidebarVisibleButton.topAnchor.constraint(equalTo: debuggerPresentationPopup.bottomAnchor, constant: 16),
			sidebarPositionLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			sidebarPositionLabel.topAnchor.constraint(equalTo: sidebarVisibleButton.bottomAnchor, constant: 12),
			sidebarPositionLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			sidebarPositionPopup.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			sidebarPositionPopup.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			sidebarPositionPopup.centerYAnchor.constraint(equalTo: sidebarPositionLabel.centerYAnchor),
			sidebarWidthLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			sidebarWidthLabel.topAnchor.constraint(equalTo: sidebarPositionPopup.bottomAnchor, constant: 18),
			sidebarWidthLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			sidebarWidthField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			sidebarWidthField.widthAnchor.constraint(equalToConstant: 72),
			sidebarWidthField.centerYAnchor.constraint(equalTo: sidebarWidthLabel.centerYAnchor),
			sidebarWidthStepper.leadingAnchor.constraint(equalTo: sidebarWidthField.trailingAnchor, constant: 8),
			sidebarWidthStepper.centerYAnchor.constraint(equalTo: sidebarWidthField.centerYAnchor),
			tabBarVisibleButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			tabBarVisibleButton.topAnchor.constraint(equalTo: sidebarWidthField.bottomAnchor, constant: 12),
			statusBarVisibleButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			statusBarVisibleButton.topAnchor.constraint(equalTo: tabBarVisibleButton.bottomAnchor, constant: 8),
			interfaceScaleLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			interfaceScaleLabel.topAnchor.constraint(equalTo: statusBarVisibleButton.bottomAnchor, constant: 16),
			interfaceScaleLabel.widthAnchor.constraint(equalTo: themeLabel.widthAnchor),
			interfaceScaleField.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			interfaceScaleField.widthAnchor.constraint(equalToConstant: 72),
			interfaceScaleField.centerYAnchor.constraint(equalTo: interfaceScaleLabel.centerYAnchor),
			interfaceScaleStepper.leadingAnchor.constraint(equalTo: interfaceScaleField.trailingAnchor, constant: 8),
			interfaceScaleStepper.centerYAnchor.constraint(equalTo: interfaceScaleField.centerYAnchor),
			terminalFontSizeLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			terminalFontSizeLabel.topAnchor.constraint(equalTo: interfaceScaleField.bottomAnchor, constant: 18),
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
			languageServersButton.leadingAnchor.constraint(equalTo: reloadButton.trailingAnchor, constant: 8),
			languageServersButton.centerYAnchor.constraint(equalTo: reloadButton.centerYAnchor),
			supportButton.leadingAnchor.constraint(equalTo: languageServersButton.trailingAnchor, constant: 8),
			supportButton.centerYAnchor.constraint(equalTo: reloadButton.centerYAnchor),
			supportButton.trailingAnchor.constraint(lessThanOrEqualTo: themePopup.trailingAnchor),
			catalogButton.leadingAnchor.constraint(equalTo: themePopup.leadingAnchor),
			catalogButton.topAnchor.constraint(equalTo: reloadButton.bottomAnchor, constant: 12),
			statusLabel.leadingAnchor.constraint(equalTo: themeLabel.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			statusLabel.topAnchor.constraint(equalTo: catalogButton.bottomAnchor, constant: 16),
			statusLabel.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -20),
		])
		settingsThemePopup = themePopup
		settingsFontPopup = fontPopup
		settingsFontRenderingPopup = fontRenderingPopup
		settingsKeymapPopup = keymapPopup
		settingsTabGroupsPopup = tabGroupsPopup
		settingsLineNumberModePopup = lineNumberModePopup
		settingsWrapPopup = wrapPopup
		settingsWrapColumnField = wrapColumnField
		settingsWrapColumnStepper = wrapColumnStepper
		settingsTabWidthField = tabWidthField
		settingsTabWidthStepper = tabWidthStepper
		settingsUseSpacesButton = useSpacesButton
		settingsAutoPairsButton = autoPairsButton
		settingsSmartIndentButton = smartIndentButton
		settingsMultipleSelectionsButton = multipleSelectionsButton
		settingsFindRegexButton = findRegexButton
		settingsFindCaseButton = findCaseButton
		settingsFindWholeWordButton = findWholeWordButton
		settingsRecoveryJournalButton = recoveryJournalButton
		settingsAutomaticallyCheckForUpdatesButton = automaticallyCheckForUpdatesButton
		settingsWorkbenchProfilePopup = workbenchProfilePopup
		settingsWorkbenchFileTreePopup = workbenchFileTreePopup
		settingsWorkbenchTerminalPopup = workbenchTerminalPopup
		settingsWorkbenchGitPopup = workbenchGitPopup
		settingsTerminalPresentationPopup = terminalPresentationPopup
		settingsGitPresentationPopup = gitPresentationPopup
		settingsDebuggerPresentationPopup = debuggerPresentationPopup
		settingsSidebarVisibleButton = sidebarVisibleButton
		settingsSidebarPositionPopup = sidebarPositionPopup
		settingsSidebarWidthField = sidebarWidthField
		settingsSidebarWidthStepper = sidebarWidthStepper
		settingsTabBarVisibleButton = tabBarVisibleButton
		settingsStatusBarVisibleButton = statusBarVisibleButton
		settingsInterfaceScaleField = interfaceScaleField
		settingsInterfaceScaleStepper = interfaceScaleStepper
		settingsFontSizeField = sizeField
		settingsFontSizeStepper = sizeStepper
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

	private func settingsCheckbox(_ title: String.LocalizationValue, action: Selector) -> NSButton {
		let button = NSButton(checkboxWithTitle: L10n.string(title), target: self, action: action)
		button.translatesAutoresizingMaskIntoConstraints = false
		return button
	}

	private func refreshSettingsThemes() {
		guard let themePopup = settingsThemePopup else {
			return
		}
		let choices = ItsyTheme.availableChoices()
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
		setDefaultSettingsStatus()
	}

	private func refreshSettingsEditorControls() {
		let preferences = EditorPreferences(settings: appSettings.editor)
		if settingsFontPopup?.numberOfItems == 0 {
			applySettingsFontChoices([EditorPreferences.FontChoice(name: preferences.fontName, displayName: EditorPreferences.fontDisplayName(for: preferences.fontName))])
		}
		syncSettingsEditorControls(preferences)
	}

	private func loadSettingsFontChoicesIfNeeded() {
		guard !settingsFontChoicesLoaded, !settingsFontChoicesLoading else {
			return
		}
		settingsFontChoicesLoading = true
		DispatchQueue.global(qos: .userInitiated).async {
			let choices = EditorPreferences.availableFontChoices()
			DispatchQueue.main.async { [weak self] in
				guard let self else {
					return
				}
				settingsFontChoicesLoaded = true
				settingsFontChoicesLoading = false
				let preferences = EditorPreferences(settings: appSettings.editor)
				applySettingsFontChoices(fontChoices(choices, including: preferences.fontName))
				syncSettingsEditorControls(preferences)
			}
		}
	}

	private func applySettingsFontChoices(_ choices: [EditorPreferences.FontChoice]) {
		settingsFontPopup?.removeAllItems()
		for choice in choices {
			settingsFontPopup?.addItem(withTitle: choice.displayName)
			settingsFontPopup?.lastItem?.representedObject = choice.name
		}
	}

	private func fontChoices(_ choices: [EditorPreferences.FontChoice], including fontName: String) -> [EditorPreferences.FontChoice] {
		guard !choices.contains(where: { $0.name == fontName }) else {
			return choices
		}
		return [EditorPreferences.FontChoice(name: fontName, displayName: EditorPreferences.fontDisplayName(for: fontName))] + choices
	}

	private func syncSettingsEditorControls(_ preferences: EditorPreferences) {
		if let item = settingsFontPopup?.itemArray.first(where: { $0.representedObject as? String == preferences.fontName }) {
			settingsFontPopup?.select(item)
		}
		settingsFontSizeField?.doubleValue = Double(preferences.fontSize)
		settingsFontSizeStepper?.doubleValue = Double(preferences.fontSize)
		if let item = settingsFontRenderingPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.editor.fontRendering.rawValue }) {
			settingsFontRenderingPopup?.select(item)
		}
		if let item = settingsKeymapPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.editor.keymap.rawValue }) {
			settingsKeymapPopup?.select(item)
		}
		if let item = settingsLineNumberModePopup?.itemArray.first(where: { $0.representedObject as? String == preferences.lineNumberMode.rawValue }) {
			settingsLineNumberModePopup?.select(item)
		}
		if let item = settingsTabGroupsPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.editor.tabGroups.rawValue }) {
			settingsTabGroupsPopup?.select(item)
		}
		if let item = settingsWrapPopup?.itemArray.first(where: { $0.representedObject as? String == preferences.wrap.rawValue }) {
			settingsWrapPopup?.select(item)
		}
		settingsWrapColumnField?.integerValue = preferences.wrapColumn
		settingsWrapColumnStepper?.integerValue = preferences.wrapColumn
		settingsTabWidthField?.integerValue = appSettings.editor.tabWidth
		settingsTabWidthStepper?.integerValue = appSettings.editor.tabWidth
		settingsUseSpacesButton?.state = appSettings.editor.useSpaces ? .on : .off
		settingsAutoPairsButton?.state = appSettings.editor.autoPairs ? .on : .off
		settingsSmartIndentButton?.state = appSettings.editor.smartIndent ? .on : .off
		settingsMultipleSelectionsButton?.state = appSettings.editor.multipleSelections ? .on : .off
		settingsFindRegexButton?.state = appSettings.find.usesRegex ? .on : .off
		settingsFindCaseButton?.state = appSettings.find.isCaseSensitive ? .on : .off
		settingsFindWholeWordButton?.state = appSettings.find.matchesWholeWord ? .on : .off
		settingsRecoveryJournalButton?.state = appSettings.recovery.journalEnabled ? .on : .off
		settingsAutomaticallyCheckForUpdatesButton?.state = appSettings.updates.automaticallyCheck ? .on : .off
		if let item = settingsWorkbenchProfilePopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.workbench.profile.rawValue }) {
			settingsWorkbenchProfilePopup?.select(item)
		}
		syncWorkbenchVisibilityPopup(settingsWorkbenchFileTreePopup, visibility: appSettings.workbench.fileTree)
		syncWorkbenchVisibilityPopup(settingsWorkbenchTerminalPopup, visibility: appSettings.workbench.terminal)
		syncWorkbenchVisibilityPopup(settingsWorkbenchGitPopup, visibility: appSettings.workbench.git)
		if let item = settingsTerminalPresentationPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.terminal.presentation.rawValue }) {
			settingsTerminalPresentationPopup?.select(item)
		}
		if let item = settingsGitPresentationPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.git.presentation.rawValue }) {
			settingsGitPresentationPopup?.select(item)
		}
		if let item = settingsDebuggerPresentationPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.debugger.presentation.rawValue }) {
			settingsDebuggerPresentationPopup?.select(item)
		}
		settingsSidebarVisibleButton?.state = appSettings.layout.sidebarVisible ? .on : .off
		if let item = settingsSidebarPositionPopup?.itemArray.first(where: { $0.representedObject as? String == appSettings.layout.sidebarPosition.rawValue }) {
			settingsSidebarPositionPopup?.select(item)
		}
		settingsSidebarWidthField?.integerValue = appSettings.layout.sidebarWidth
		settingsSidebarWidthStepper?.integerValue = appSettings.layout.sidebarWidth
		settingsTabBarVisibleButton?.state = appSettings.layout.tabBarVisible ? .on : .off
		settingsStatusBarVisibleButton?.state = appSettings.layout.statusBarVisible ? .on : .off
		settingsInterfaceScaleField?.doubleValue = appSettings.layout.interfaceScale
		settingsInterfaceScaleStepper?.doubleValue = appSettings.layout.interfaceScale
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
				? L10n.string("Config: ~/.config/itsy/settings.toml · LSP: \(LSPServerRegistryLoader.defaultConfigURL.path)")
				: L10n.string("Config: global + workspace · LSP: \(LSPServerRegistryLoader.defaultConfigURL.path)")
		}
	}

	@objc private func reloadSettings(_ sender: Any?) {
		reloadSettingsFromDisk()
	}

	@objc func showLSPConfiguration(_ sender: Any?) {
		let panel = lspConfigurationPanel ?? LSPServerConfigurationPanel()
		lspConfigurationPanel = panel
		panel.show(relativeTo: settingsWindowController?.window)
	}

	@objc func showManagedSupport(_ sender: Any?) {
		let panel = managedSupportPanel ?? ManagedSupportPanel(workspaceRootProvider: { ItsyWorkspaceController.currentRootURL })
		managedSupportPanel = panel
		panel.show(relativeTo: settingsWindowController?.window, selecting: (sender as? ManagedSupportRequest)?.componentID)
	}

	@objc func showSettingsCatalog(_: Any?) {
		let panel = settingsInspectorPanel ?? SettingsInspectorPanel(
			resetEntry: { [weak self] key in
				self?.resetCatalogEntry(key) ?? []
			},
			updateEntry: { [weak self] key, value in
				self?.updateCatalogEntry(key, value: value) ?? .init(items: [], validationError: "Settings are unavailable.")
			}
		)
		settingsInspectorPanel = panel
		panel.update(items: settingsCatalogItems())
		panel.show(relativeTo: settingsWindowController?.window)
	}

	func openSettingsFile(workspace: Bool) {
		let url: URL
		if workspace {
			guard let root = ItsyWorkspaceController.currentRootURL else { return }
			url = ItsySettingsStore.workspaceFileURL(workspaceRoot: root)
		} else {
			url = settingsStore.fileURL
		}
		if !FileManager.default.fileExists(atPath: url.path) {
			do {
				try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
				try "# Itsy settings. Changes reload while Itsy is running.\n\n".write(to: url, atomically: true, encoding: .utf8)
			} catch {
				settingsStatusLabel?.textColor = .systemRed
				settingsStatusLabel?.stringValue = L10n.string("Failed to create settings file: \(String(describing: error))")
				return
			}
		}
		_ = documentController.openDocument(at: url)
	}

	func restoreWorkbenchDefaults() {
		appSettings.workbench = WorkbenchProfileBuilder.workbench()
		saveAppSettings()
		reloadSettingsFromDisk()
	}

	func generateWorkbenchDoctorFile() -> URL? {
		let url = settingsStore.fileURL.deletingLastPathComponent().appendingPathComponent("settings.workbench.doctor.toml")
		let contents = """
		# Copy this section into settings.toml, then reload settings.
		[workbench]
		profile = "workbench"
		file_tree = "automatic"
		terminal = "automatic"
		git = "automatic"
		"""
		do {
			try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
			try contents.write(to: url, atomically: true, encoding: .utf8)
			return url
		} catch {
			settingsStatusLabel?.textColor = .systemRed
			settingsStatusLabel?.stringValue = L10n.string("Failed to generate workbench doctor file: \(String(describing: error))")
			return nil
		}
	}

	private func reloadSettingsFromDisk() {
		let result = settingsStore.load(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: Self.legacySettingsFromDefaults())
		settingsResolution = settingsStore.resolve(workspaceRoot: ItsyWorkspaceController.currentRootURL, fallback: Self.legacySettingsFromDefaults())
		appSettings = settingsResolution.settings
		settingsWarnings = result.warnings
		Self.mirrorSettingsToDefaults(appSettings)
		refreshSettingsThemes()
		refreshSettingsEditorControls()
		refreshSettingsTerminalControls()
		setDefaultSettingsStatus()
		onSettingsChange(appSettings.normalized())
		onTerminalSettingsChange(appSettings.terminal)
		reloadSyntaxThemes()
		publishSettingsChanged()
		settingsInspectorPanel?.update(items: settingsCatalogItems())
	}

	private func settingsCatalogItems() -> [SettingsInspectorPanel.Item] {
		let workspaceRoot = ItsyWorkspaceController.currentRootURL
		return ItsySettingsCatalog.entries.map { entry in
			let scope = settingsResolution.sources[entry.key] ?? .default
			var source: (String, URL?)
			switch scope {
			case .global:
				source = (L10n.string("Global"), settingsStore.fileURL)
			case .workspace:
				source = (L10n.string("Workspace"), workspaceRoot.map { ItsySettingsStore.workspaceFileURL(workspaceRoot: $0) })
			case .language:
				source = (L10n.string("Language"), nil)
			case .session:
				source = (L10n.string("Session"), nil)
			case .default:
				source = (L10n.string("Built-in Default"), nil)
			}
			if entry.isLanguageTemplate {
				source = (L10n.string("Language Template"), nil)
			}
			return SettingsInspectorPanel.Item(
				entry: entry,
				effectiveValue: ItsySettingsCatalog.effectiveValue(for: entry.key, in: settingsResolution.settings),
				sourceLabel: source.0,
				sourceURL: source.1
			)
		}
	}

	private func resetCatalogEntry(_ key: String) -> [SettingsInspectorPanel.Item] {
		guard ItsySettingsCatalog.reset(key, in: &appSettings) else {
			return settingsCatalogItems()
		}
		commitCatalogSettingsChange()
		return settingsCatalogItems()
	}

	private func updateCatalogEntry(_ key: String, value: String) -> SettingsInspectorPanel.UpdateResult {
		var updated = appSettings
		if let error = ItsySettingsCatalog.update(value: value, for: key, in: &updated) {
			return .init(items: settingsCatalogItems(), validationError: error)
		}
		if key == "theme.id" {
			do {
				_ = try ItsyTheme.loadChoice(id: updated.theme.id)
			} catch {
				return .init(items: settingsCatalogItems(), validationError: L10n.string("Theme could not be loaded."))
			}
		}
		appSettings = updated
		commitCatalogSettingsChange()
		return .init(items: settingsCatalogItems(), validationError: nil)
	}

	private func commitCatalogSettingsChange() {
		appSettings = appSettings.normalized()
		saveAppSettings()
		refreshSettingsThemes()
		refreshSettingsEditorControls()
		refreshSettingsTerminalControls()
		onSettingsChange(appSettings)
		onTerminalSettingsChange(appSettings.terminal)
		reloadSyntaxThemes()
	}

	private func restartSettingsWatcher() {
		settingsWatcher?.stop()
		var urls = [settingsStore.fileURL]
		urls.append(LSPServerRegistryLoader.defaultConfigURL)
		if let root = ItsyWorkspaceController.currentRootURL {
			urls.append(ItsySettingsStore.workspaceFileURL(workspaceRoot: root))
			urls.append(root.appendingPathComponent(".itsy/lsp.toml"))
		}
		let watcher = ItsySettingsWatcher(urls: urls) { [weak self] in
			DispatchQueue.main.async {
				self?.reloadSettingsFromDisk()
				EditorWindowController.reloadLSPConfiguration()
			}
		}
		_ = watcher.start()
		settingsWatcher = watcher
	}

	private func publishSettingsChanged() {
		let isError = !settingsWarnings.isEmpty
		NotificationCenter.default.post(
			name: .itsySettingsChanged,
			object: self,
			userInfo: [
				ItsySettingsNotificationUserInfoKey.settings: appSettings.normalized(),
				ItsySettingsNotificationUserInfoKey.statusMessage: settingsApplicationStatusMessage(),
				ItsySettingsNotificationUserInfoKey.statusIsError: isError,
			]
		)
	}

	private func settingsApplicationStatusMessage() -> String {
		if let warning = settingsWarnings.first {
			let fallback = warning.retainedFallback ? L10n.string(" A fallback value remains active.") : ""
			return L10n.string("Settings error: \(warning.description).\(fallback)")
		}
		return L10n.string(
			"Settings applied · Terminal \(appSettings.terminal.presentation.rawValue) · Git \(appSettings.git.presentation.rawValue) · Debugger \(appSettings.debugger.presentation.rawValue)"
		)
	}

	@objc private func settingsThemeDidChange(_ sender: Any?) {
		guard let id = settingsThemePopup?.selectedItem?.representedObject as? String else {
			return
		}
		do {
			_ = try ItsyTheme.loadChoice(id: id)
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

	@objc private func settingsFontRenderingDidChange(_: Any?) {
		guard
			let rawValue = settingsFontRenderingPopup?.selectedItem?.representedObject as? String,
			let fontRendering = ItsySettings.FontRenderingMode(rawValue: rawValue)
		else {
			return
		}
		appSettings.editor.fontRendering = fontRendering
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsKeymapDidChange(_ sender: Any?) {
		guard
			let rawValue = settingsKeymapPopup?.selectedItem?.representedObject as? String,
			let keymap = ItsySettings.KeymapMode(rawValue: rawValue)
		else {
			return
		}
		appSettings.editor.keymap = keymap
		saveAppSettings()
		refreshSettingsEditorControls()
		onSettingsChange(appSettings.normalized())
	}

	@objc private func settingsLineNumberModeDidChange(_ sender: Any?) {
		guard
			let rawValue = settingsLineNumberModePopup?.selectedItem?.representedObject as? String,
			let lineNumberMode = ItsySettings.LineNumberMode(rawValue: rawValue)
		else {
			return
		}
		var preferences = EditorPreferences(settings: appSettings.editor)
		preferences.lineNumberMode = lineNumberMode
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsTabGroupsDidChange(_ sender: Any?) {
		guard
			let rawValue = settingsTabGroupsPopup?.selectedItem?.representedObject as? String,
			let tabGroups = ItsySettings.TabGroupScope(rawValue: rawValue)
		else {
			return
		}
		appSettings.editor.tabGroups = tabGroups
		saveAppSettings()
		refreshSettingsEditorControls()
		onSettingsChange(appSettings.normalized())
	}

	@objc private func settingsWrapDidChange(_ sender: Any?) {
		guard
			let rawValue = settingsWrapPopup?.selectedItem?.representedObject as? String,
			let wrap = ItsySettings.WrapMode(rawValue: rawValue)
		else {
			return
		}
		var preferences = EditorPreferences(settings: appSettings.editor)
		preferences.wrap = wrap
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsWrapColumnDidChange(_ sender: Any?) {
		var preferences = EditorPreferences(settings: appSettings.editor)
		if sender as? NSStepper === settingsWrapColumnStepper {
			preferences.wrapColumn = settingsWrapColumnStepper?.integerValue ?? preferences.wrapColumn
		} else {
			preferences.wrapColumn = settingsWrapColumnField?.integerValue ?? preferences.wrapColumn
		}
		preferences.wrapColumn = min(
			max(preferences.wrapColumn, ItsySettings.EditorSettings.minWrapColumn),
			ItsySettings.EditorSettings.maxWrapColumn
		)
		saveAndApplyEditorPreferences(preferences)
	}

	@objc private func settingsTabWidthDidChange(_ sender: Any?) {
		let value = if sender as? NSStepper === settingsTabWidthStepper {
			settingsTabWidthStepper?.integerValue ?? appSettings.editor.tabWidth
		} else {
			settingsTabWidthField?.integerValue ?? appSettings.editor.tabWidth
		}
		appSettings.editor.tabWidth = min(
			max(value, ItsySettings.EditorSettings.minTabWidth),
			ItsySettings.EditorSettings.maxTabWidth
		)
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsUseSpacesDidChange(_: Any?) {
		appSettings.editor.useSpaces = settingsUseSpacesButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsAutoPairsDidChange(_: Any?) {
		appSettings.editor.autoPairs = settingsAutoPairsButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsSmartIndentDidChange(_: Any?) {
		appSettings.editor.smartIndent = settingsSmartIndentButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsMultipleSelectionsDidChange(_: Any?) {
		appSettings.editor.multipleSelections = settingsMultipleSelectionsButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsFindDidChange(_: Any?) {
		appSettings.find = ItsySettings.FindSettings(
			usesRegex: settingsFindRegexButton?.state == .on,
			isCaseSensitive: settingsFindCaseButton?.state == .on,
			matchesWholeWord: settingsFindWholeWordButton?.state == .on
		)
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsRecoveryJournalDidChange(_: Any?) {
		appSettings.recovery.journalEnabled = settingsRecoveryJournalButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsAutomaticallyCheckForUpdatesDidChange(_: Any?) {
		appSettings.updates.automaticallyCheck = settingsAutomaticallyCheckForUpdatesButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsWorkbenchProfileDidChange(_: Any?) {
		guard let rawValue = settingsWorkbenchProfilePopup?.selectedItem?.representedObject as? String,
		      let profile = WorkbenchProfile(rawValue: rawValue)
		else {
			return
		}
		appSettings.workbench.profile = profile
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsWorkbenchVisibilityDidChange(_ sender: NSPopUpButton) {
		guard let rawValue = sender.selectedItem?.representedObject as? String,
		      let visibility = WorkbenchVisibility(rawValue: rawValue)
		else {
			return
		}
		switch sender.identifier?.rawValue {
		case "workbench.file_tree":
			appSettings.workbench.fileTree = visibility
		case "workbench.terminal":
			appSettings.workbench.terminal = visibility
		case "workbench.git":
			appSettings.workbench.git = visibility
		default:
			return
		}
		saveAndApplyBehaviorSettings()
	}

	private func workbenchVisibilityPopup(identifier: String, label: String) -> NSPopUpButton {
		let popup = NSPopUpButton(frame: .zero, pullsDown: false)
		for visibility in WorkbenchVisibility.allCases {
			popup.addItem(withTitle: visibility.rawValue.capitalized)
			popup.lastItem?.representedObject = visibility.rawValue
		}
		popup.identifier = NSUserInterfaceItemIdentifier(identifier)
		popup.setAccessibilityLabel(label)
		popup.target = self
		popup.action = #selector(settingsWorkbenchVisibilityDidChange(_:))
		return popup
	}

	private func syncWorkbenchVisibilityPopup(_ popup: NSPopUpButton?, visibility: WorkbenchVisibility) {
		if let item = popup?.itemArray.first(where: { $0.representedObject as? String == visibility.rawValue }) {
			popup?.select(item)
		}
	}

	@objc private func settingsTerminalPresentationDidChange(_: Any?) {
		guard
			let rawValue = settingsTerminalPresentationPopup?.selectedItem?.representedObject as? String,
			let presentation = ItsySettings.TerminalSettings.Presentation(rawValue: rawValue)
		else {
			return
		}
		appSettings.terminal.presentation = presentation
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsGitPresentationDidChange(_: Any?) {
		guard
			let rawValue = settingsGitPresentationPopup?.selectedItem?.representedObject as? String,
			let presentation = ItsySettings.GitSettings.Presentation(rawValue: rawValue)
		else {
			return
		}
		appSettings.git.presentation = presentation
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsDebuggerPresentationDidChange(_: Any?) {
		guard
			let rawValue = settingsDebuggerPresentationPopup?.selectedItem?.representedObject as? String,
			let presentation = ItsySettings.DebuggerSettings.Presentation(rawValue: rawValue)
		else {
			return
		}
		appSettings.debugger.presentation = presentation
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsSidebarVisibleDidChange(_: Any?) {
		appSettings.layout.sidebarVisible = settingsSidebarVisibleButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsSidebarPositionDidChange(_: Any?) {
		guard
			let rawValue = settingsSidebarPositionPopup?.selectedItem?.representedObject as? String,
			let sidebarPosition = ItsySettings.SidebarPosition(rawValue: rawValue)
		else {
			return
		}
		appSettings.layout.sidebarPosition = sidebarPosition
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsSidebarWidthDidChange(_ sender: Any?) {
		let value = if sender as? NSStepper === settingsSidebarWidthStepper {
			settingsSidebarWidthStepper?.integerValue ?? appSettings.layout.sidebarWidth
		} else {
			settingsSidebarWidthField?.integerValue ?? appSettings.layout.sidebarWidth
		}
		appSettings.layout.sidebarWidth = min(
			max(value, ItsySettings.LayoutSettings.minSidebarWidth),
			ItsySettings.LayoutSettings.maxSidebarWidth
		)
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsTabBarVisibleDidChange(_: Any?) {
		appSettings.layout.tabBarVisible = settingsTabBarVisibleButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsStatusBarVisibleDidChange(_: Any?) {
		appSettings.layout.statusBarVisible = settingsStatusBarVisibleButton?.state == .on
		saveAndApplyBehaviorSettings()
	}

	@objc private func settingsInterfaceScaleDidChange(_ sender: Any?) {
		let value = if sender as? NSStepper === settingsInterfaceScaleStepper {
			settingsInterfaceScaleStepper?.doubleValue ?? appSettings.layout.interfaceScale
		} else {
			settingsInterfaceScaleField?.doubleValue ?? appSettings.layout.interfaceScale
		}
		appSettings.layout.interfaceScale = min(
			max(value, ItsySettings.LayoutSettings.minInterfaceScale),
			ItsySettings.LayoutSettings.maxInterfaceScale
		)
		saveAndApplyBehaviorSettings()
	}

	private func saveAndApplyBehaviorSettings() {
		appSettings = appSettings.normalized()
		saveAppSettings()
		refreshSettingsEditorControls()
		onSettingsChange(appSettings)
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
