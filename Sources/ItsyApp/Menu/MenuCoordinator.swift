import AppKit
import ItsyRender

@MainActor final class MenuCoordinator: NSObject, NSMenuDelegate {
	private let documentController: ItsyDocumentController
	private weak var actionTarget: AppCoordinator?
	private weak var gitTarget: GitCoordinator?
	private weak var updateTarget: SparkleUpdateCoordinator?
	private weak var openRecentMenu: NSMenu?
	private weak var gitGutterIndexMenuItem: NSMenuItem?
	private weak var gitGutterHeadMenuItem: NSMenuItem?

	init(documentController: ItsyDocumentController, actionTarget: AppCoordinator, gitTarget: GitCoordinator, updateTarget: SparkleUpdateCoordinator) {
		self.documentController = documentController
		self.actionTarget = actionTarget
		self.gitTarget = gitTarget
		self.updateTarget = updateTarget
		super.init()
	}

	@objc private func useGitGutterIndex(_: Any?) {
		ItsyGitHunkGutterCoordinator.setMode(.index)
		refreshGitGutterMenuItems()
	}

	@objc private func useGitGutterHead(_: Any?) {
		ItsyGitHunkGutterCoordinator.setMode(.head)
		refreshGitGutterMenuItems()
	}

	private func refreshGitGutterMenuItems() {
		let mode = ItsyGitHunkGutterCoordinator.currentMode
		gitGutterIndexMenuItem?.state = mode == .index ? .on : .off
		gitGutterHeadMenuItem?.state = mode == .head ? .on : .off
	}

	private func addGitItem(to menu: NSMenu, title: String, selector: Selector, keyEquivalent: String = "", modifiers: NSEvent.ModifierFlags = []) {
		let item = menu.addItem(withTitle: title, action: #selector(sendGitAction(_:)), keyEquivalent: keyEquivalent)
		item.keyEquivalentModifierMask = modifiers
		item.target = self
		item.representedObject = NSStringFromSelector(selector)
	}

	@objc private func sendGitAction(_ sender: NSMenuItem) {
		guard let selectorName = sender.representedObject as? String else {
			return
		}
		NSApp.sendAction(NSSelectorFromString(selectorName), to: gitTarget, from: sender)
	}

	func installMainMenu() {
		let mainMenu = NSMenu()
		mainMenu.disableAutomaticWritingToolsItems()
		let appItem = NSMenuItem()
		let fileItem = NSMenuItem()
		let editItem = NSMenuItem()
		let navigateItem = NSMenuItem()
		let viewItem = NSMenuItem()
		let gitItem = NSMenuItem()
		let taskItem = NSMenuItem()
		let debugItem = NSMenuItem()
		let terminalItem = NSMenuItem()
		let problemItem = NSMenuItem()
		let commandItem = NSMenuItem()
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)
		mainMenu.addItem(editItem)
		mainMenu.addItem(navigateItem)
		mainMenu.addItem(viewItem)
		mainMenu.addItem(gitItem)
		mainMenu.addItem(taskItem)
		mainMenu.addItem(debugItem)
		mainMenu.addItem(terminalItem)
		mainMenu.addItem(problemItem)
		mainMenu.addItem(commandItem)

		let appMenu = NSMenu()
		appMenu.disableAutomaticWritingToolsItems()
		let settingsItem = appMenu.addItem(withTitle: L10n.string("Settings..."), action: #selector(AppCoordinator.showSettings(_:)), keyEquivalent: ",")
		settingsItem.target = actionTarget
		appMenu.addItem(.separator())
		let updateItem = appMenu.addItem(withTitle: L10n.string("Check for Updates..."), action: #selector(SparkleUpdateCoordinator.checkForUpdates(_:)), keyEquivalent: "")
		updateItem.target = updateTarget
		updateItem.isEnabled = updateTarget?.isConfigured ?? false
		appMenu.addItem(.separator())
		appMenu.addItem(withTitle: L10n.string("Quit Itsy"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
		appItem.submenu = appMenu

		let fileMenu = NSMenu(title: L10n.string("File"))
		fileMenu.disableAutomaticWritingToolsItems()
		let newItem = fileMenu.addItem(withTitle: L10n.string("New"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "n")
		newItem.target = documentController
		let newWindowItem = fileMenu.addItem(withTitle: L10n.string("New Window"), action: #selector(AppCoordinator.newWindow(_:)), keyEquivalent: "N")
		newWindowItem.keyEquivalentModifierMask = [.command, .shift]
		newWindowItem.target = actionTarget
		let newTabItem = fileMenu.addItem(withTitle: L10n.string("New Tab"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "t")
		newTabItem.keyEquivalentModifierMask = [.command, .option]
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: L10n.string("Open..."), action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: L10n.string("Open Folder..."), action: #selector(AppCoordinator.openFolder(_:)), keyEquivalent: "o")
		openFolderItem.keyEquivalentModifierMask = [.command, .option]
		openFolderItem.target = actionTarget
		let addFolderItem = fileMenu.addItem(withTitle: L10n.string("Add Folder to Workspace..."), action: #selector(AppCoordinator.addFolderToWorkspace(_:)), keyEquivalent: "")
		addFolderItem.target = actionTarget
		let openRecentItem = fileMenu.addItem(withTitle: L10n.string("Open Recent"), action: nil, keyEquivalent: "")
		let openRecentMenu = NSMenu(title: L10n.string("Open Recent"))
		openRecentMenu.disableAutomaticWritingToolsItems()
		openRecentMenu.delegate = self
		self.openRecentMenu = openRecentMenu
		fileMenu.setSubmenu(openRecentMenu, for: openRecentItem)
		let closeItem = fileMenu.addItem(withTitle: L10n.string("Close"), action: #selector(AppCoordinator.closeCurrentDocument(_:)), keyEquivalent: "w")
		closeItem.target = actionTarget
		fileMenu.addItem(.separator())
		fileMenu.addItem(withTitle: L10n.string("Save"), action: #selector(NSDocument.save(_:)), keyEquivalent: "s")
		fileMenu.addItem(withTitle: L10n.string("Save As..."), action: #selector(NSDocument.saveAs(_:)), keyEquivalent: "")
		fileItem.submenu = fileMenu

		let editMenu = NSMenu(title: L10n.string("Edit"))
		editMenu.disableAutomaticWritingToolsItems()
		editMenu.addItem(withTitle: L10n.string("Undo"), action: #selector(MetalTextView.undo(_:)), keyEquivalent: "z")
		let redoItem = editMenu.addItem(withTitle: L10n.string("Redo"), action: #selector(MetalTextView.redo(_:)), keyEquivalent: "Z")
		redoItem.keyEquivalentModifierMask = [.command, .shift]
		editMenu.addItem(.separator())
		editMenu.addItem(withTitle: L10n.string("Cut"), action: #selector(MetalTextView.cut(_:)), keyEquivalent: "x")
		editMenu.addItem(withTitle: L10n.string("Copy"), action: #selector(MetalTextView.copy(_:)), keyEquivalent: "c")
		editMenu.addItem(withTitle: L10n.string("Paste"), action: #selector(MetalTextView.paste(_:)), keyEquivalent: "v")
		editMenu.addItem(withTitle: L10n.string("Select All"), action: #selector(MetalTextView.selectAll(_:)), keyEquivalent: "a")
		editMenu.addItem(.separator())
		let findItem = editMenu.addItem(withTitle: L10n.string("Find"), action: #selector(AppCoordinator.toggleFindBar(_:)), keyEquivalent: "f")
		findItem.target = actionTarget
		let findNextItem = editMenu.addItem(withTitle: L10n.string("Find Next"), action: #selector(AppCoordinator.findNext(_:)), keyEquivalent: "g")
		findNextItem.target = actionTarget
		let findPreviousItem = editMenu.addItem(withTitle: L10n.string("Find Previous"), action: #selector(AppCoordinator.findPrevious(_:)), keyEquivalent: "G")
		findPreviousItem.keyEquivalentModifierMask = [.command, .shift]
		findPreviousItem.target = actionTarget
		let selectAllFindMatchesItem = editMenu.addItem(withTitle: L10n.string("Select All Find Matches"), action: #selector(AppCoordinator.selectAllFindMatches(_:)), keyEquivalent: "g")
		selectAllFindMatchesItem.keyEquivalentModifierMask = [.command, .control]
		selectAllFindMatchesItem.target = actionTarget
		let findInProjectItem = editMenu.addItem(withTitle: L10n.string("Find in Project"), action: #selector(AppCoordinator.showProjectFind(_:)), keyEquivalent: "F")
		findInProjectItem.keyEquivalentModifierMask = [.command, .shift]
		findInProjectItem.target = actionTarget
		editItem.submenu = editMenu

		let navigateMenu = NSMenu(title: L10n.string("Navigate"))
		navigateMenu.disableAutomaticWritingToolsItems()
		let gotoFileItem = navigateMenu.addItem(withTitle: L10n.string("Go to File"), action: #selector(AppCoordinator.showFilePalette(_:)), keyEquivalent: "p")
		gotoFileItem.target = actionTarget
		let gotoLineItem = navigateMenu.addItem(withTitle: L10n.string("Go to Line"), action: #selector(AppCoordinator.showLinePalette(_:)), keyEquivalent: "g")
		gotoLineItem.keyEquivalentModifierMask = [.control]
		gotoLineItem.target = actionTarget
		navigateMenu.addItem(.separator())
		let gotoWorkspaceSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in Workspace"), action: #selector(AppCoordinator.showWorkspaceSymbolPalette(_:)), keyEquivalent: "t")
		gotoWorkspaceSymbolItem.target = actionTarget
		let gotoFileSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in File"), action: #selector(AppCoordinator.showFileSymbolPalette(_:)), keyEquivalent: "O")
		gotoFileSymbolItem.target = actionTarget
		let outlineItem = navigateMenu.addItem(withTitle: L10n.string("Outline"), action: #selector(AppCoordinator.showOutline(_:)), keyEquivalent: "7")
		outlineItem.keyEquivalentModifierMask = [.command, .option]
		outlineItem.target = actionTarget
		navigateItem.submenu = navigateMenu

		let viewMenu = NSMenu(title: L10n.string("View"))
		viewMenu.disableAutomaticWritingToolsItems()
		let zoomInItem = viewMenu.addItem(withTitle: L10n.string("Zoom In"), action: #selector(AppCoordinator.zoomIn(_:)), keyEquivalent: "+")
		zoomInItem.target = actionTarget
		let zoomOutItem = viewMenu.addItem(withTitle: L10n.string("Zoom Out"), action: #selector(AppCoordinator.zoomOut(_:)), keyEquivalent: "-")
		zoomOutItem.target = actionTarget
		let resetZoomItem = viewMenu.addItem(withTitle: L10n.string("Reset Zoom"), action: #selector(AppCoordinator.resetZoom(_:)), keyEquivalent: "0")
		resetZoomItem.target = actionTarget
		viewMenu.addItem(.separator())
		let gitGutterIndexItem = viewMenu.addItem(
			withTitle: L10n.string("Git Gutter: Compare to Index"),
			action: #selector(useGitGutterIndex(_:)),
			keyEquivalent: ""
		)
		gitGutterIndexItem.target = self
		let gitGutterHeadItem = viewMenu.addItem(
			withTitle: L10n.string("Git Gutter: Compare to HEAD"),
			action: #selector(useGitGutterHead(_:)),
			keyEquivalent: ""
		)
		gitGutterHeadItem.target = self
		gitGutterIndexMenuItem = gitGutterIndexItem
		gitGutterHeadMenuItem = gitGutterHeadItem
		refreshGitGutterMenuItems()
		viewItem.submenu = viewMenu

		let gitMenu = NSMenu(title: L10n.string("Git"))
		gitMenu.disableAutomaticWritingToolsItems()
		addGitItem(to: gitMenu, title: L10n.string("Git Changes"), selector: #selector(GitCoordinator.showGitChanges(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Refresh Git Status"), selector: #selector(GitCoordinator.refreshGitChanges(_:)))
		gitMenu.addItem(.separator())
		addGitItem(to: gitMenu, title: L10n.string("Blame Current File"), selector: #selector(GitCoordinator.showGitBlame(_:)))
		addGitItem(to: gitMenu, title: L10n.string("File History"), selector: #selector(GitCoordinator.showGitFileHistory(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Line History"), selector: #selector(GitCoordinator.showGitLineHistory(_:)))
		gitMenu.addItem(.separator())
		addGitItem(to: gitMenu, title: L10n.string("Stashes"), selector: #selector(GitCoordinator.showGitStashes(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Stash Current Changes..."), selector: #selector(GitCoordinator.stashCurrentGitChanges(_:)), keyEquivalent: "S", modifiers: [.command, .shift])
		gitMenu.addItem(.separator())
		addGitItem(to: gitMenu, title: L10n.string("Fetch"), selector: #selector(GitCoordinator.fetchGitRemote(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Pull"), selector: #selector(GitCoordinator.pullGitRemote(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Pull Rebase"), selector: #selector(GitCoordinator.pullGitRemoteRebase(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Push"), selector: #selector(GitCoordinator.pushGitRemote(_:)))
		addGitItem(to: gitMenu, title: L10n.string("Cancel Remote Operation"), selector: #selector(GitCoordinator.cancelGitRemote(_:)))
		gitItem.submenu = gitMenu

		let taskMenu = NSMenu(title: L10n.string("Tasks"))
		taskMenu.disableAutomaticWritingToolsItems()
		let taskRunItem = taskMenu.addItem(withTitle: L10n.string("Run Task"), action: #selector(AppCoordinator.showTasks(_:)), keyEquivalent: "")
		taskRunItem.target = actionTarget
		let taskRefreshItem = taskMenu.addItem(withTitle: L10n.string("Refresh Tasks"), action: #selector(AppCoordinator.refreshTasks(_:)), keyEquivalent: "")
		taskRefreshItem.target = actionTarget
		taskItem.submenu = taskMenu

		let debugMenu = NSMenu(title: L10n.string("Debug"))
		debugMenu.disableAutomaticWritingToolsItems()
		let startDebugItem = debugMenu.addItem(withTitle: L10n.string("Start Debugging"), action: #selector(AppCoordinator.showDebugLaunchConfigPicker(_:)), keyEquivalent: Self.functionKeyEquivalent(NSF5FunctionKey))
		startDebugItem.keyEquivalentModifierMask = [.command]
		startDebugItem.target = actionTarget
		let callStackItem = debugMenu.addItem(withTitle: L10n.string("Call Stack"), action: #selector(AppCoordinator.showDebugCallStack(_:)), keyEquivalent: "")
		callStackItem.target = actionTarget
		let variablesItem = debugMenu.addItem(withTitle: L10n.string("Variables"), action: #selector(AppCoordinator.showDebugVariables(_:)), keyEquivalent: "")
		variablesItem.target = actionTarget
		let watchesItem = debugMenu.addItem(withTitle: L10n.string("Watches"), action: #selector(AppCoordinator.showDebugWatches(_:)), keyEquivalent: "")
		watchesItem.target = actionTarget
		let consoleItem = debugMenu.addItem(withTitle: L10n.string("Debug Console"), action: #selector(AppCoordinator.showDebugConsole(_:)), keyEquivalent: "")
		consoleItem.target = actionTarget
		debugMenu.addItem(.separator())
		let continueItem = debugMenu.addItem(withTitle: L10n.string("Continue"), action: #selector(AppCoordinator.continueDebug(_:)), keyEquivalent: "")
		continueItem.target = actionTarget
		let stepOverItem = debugMenu.addItem(withTitle: L10n.string("Step Over"), action: #selector(AppCoordinator.stepOverDebug(_:)), keyEquivalent: "")
		stepOverItem.target = actionTarget
		let stepInItem = debugMenu.addItem(withTitle: L10n.string("Step In"), action: #selector(AppCoordinator.stepInDebug(_:)), keyEquivalent: "")
		stepInItem.target = actionTarget
		let stepOutItem = debugMenu.addItem(withTitle: L10n.string("Step Out"), action: #selector(AppCoordinator.stepOutDebug(_:)), keyEquivalent: "")
		stepOutItem.target = actionTarget
		let pauseItem = debugMenu.addItem(withTitle: L10n.string("Pause"), action: #selector(AppCoordinator.pauseDebug(_:)), keyEquivalent: "")
		pauseItem.target = actionTarget
		let restartItem = debugMenu.addItem(withTitle: L10n.string("Restart"), action: #selector(AppCoordinator.restartDebug(_:)), keyEquivalent: "")
		restartItem.target = actionTarget
		let stopItem = debugMenu.addItem(withTitle: L10n.string("Stop"), action: #selector(AppCoordinator.stopDebug(_:)), keyEquivalent: "")
		stopItem.target = actionTarget
		debugItem.submenu = debugMenu

		let terminalMenu = NSMenu(title: L10n.string("Terminal"))
		terminalMenu.disableAutomaticWritingToolsItems()
		let terminalShowItem = terminalMenu.addItem(withTitle: L10n.string("Terminal"), action: #selector(AppCoordinator.showTerminal(_:)), keyEquivalent: "`")
		terminalShowItem.keyEquivalentModifierMask = [.command, .shift]
		terminalShowItem.target = actionTarget
		terminalItem.submenu = terminalMenu

		let problemMenu = NSMenu(title: L10n.string("Problems"))
		problemMenu.disableAutomaticWritingToolsItems()
		let problemShowItem = problemMenu.addItem(withTitle: L10n.string("Problems"), action: #selector(AppCoordinator.showProblems(_:)), keyEquivalent: "M")
		problemShowItem.keyEquivalentModifierMask = [.command, .shift]
		problemShowItem.target = actionTarget
		problemMenu.addItem(.separator())
		let nextProblemItem = problemMenu.addItem(withTitle: L10n.string("Next Problem"), action: #selector(AppCoordinator.showNextProblem(_:)), keyEquivalent: "n")
		nextProblemItem.keyEquivalentModifierMask = [.control, .option]
		nextProblemItem.target = actionTarget
		let previousProblemItem = problemMenu.addItem(withTitle: L10n.string("Previous Problem"), action: #selector(AppCoordinator.showPreviousProblem(_:)), keyEquivalent: "p")
		previousProblemItem.keyEquivalentModifierMask = [.control, .option]
		previousProblemItem.target = actionTarget
		problemItem.submenu = problemMenu

		let commandMenu = NSMenu(title: L10n.string("Command"))
		commandMenu.disableAutomaticWritingToolsItems()
		let paletteItem = commandMenu.addItem(withTitle: L10n.string("Command Palette"), action: #selector(AppCoordinator.toggleCommandPalette(_:)), keyEquivalent: "P")
		paletteItem.keyEquivalentModifierMask = [.command, .shift]
		paletteItem.target = actionTarget
		commandItem.submenu = commandMenu
		NSApp.mainMenu = mainMenu
	}

	@objc private func openRecentDocument(_ sender: NSMenuItem) {
		guard let url = sender.representedObject as? URL else {
			return
		}
		_ = documentController.openDocument(at: url)
	}

	@objc func menuNeedsUpdate(_ menu: NSMenu) {
		guard menu === openRecentMenu else {
			return
		}
		menu.removeAllItems()
		let urls = documentController.recentDocumentURLs
		for url in urls {
			let item = menu.addItem(withTitle: url.lastPathComponent, action: #selector(openRecentDocument(_:)), keyEquivalent: "")
			item.target = self
			item.representedObject = url
		}
		if !urls.isEmpty {
			menu.addItem(.separator())
		}
		let clearItem = menu.addItem(withTitle: L10n.string("Clear Menu"), action: #selector(NSDocumentController.clearRecentDocuments(_:)), keyEquivalent: "")
		clearItem.target = documentController
		clearItem.isEnabled = !urls.isEmpty
	}
}

private extension MenuCoordinator {
	static func functionKeyEquivalent(_ key: Int) -> String {
		guard let scalar = UnicodeScalar(key) else {
			return ""
		}
		return String(scalar)
	}
}

private extension NSMenu {
	func disableAutomaticWritingToolsItems() {
		if #available(macOS 15.2, *) {
			automaticallyInsertsWritingToolsItems = false
		}
	}
}
