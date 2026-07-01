import AppKit

final class MenuCoordinator: NSObject, NSMenuDelegate {
	private let documentController: ItsyDocumentController
	private weak var actionTarget: AppCoordinator?
	private weak var openRecentMenu: NSMenu?
	private weak var gitGutterIndexMenuItem: NSMenuItem?
	private weak var gitGutterHeadMenuItem: NSMenuItem?

	init(documentController: ItsyDocumentController, actionTarget: AppCoordinator) {
		self.documentController = documentController
		self.actionTarget = actionTarget
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

	func installMainMenu() {
		let mainMenu = NSMenu()
		let appItem = NSMenuItem()
		let fileItem = NSMenuItem()
		let editItem = NSMenuItem()
		let navigateItem = NSMenuItem()
		let viewItem = NSMenuItem()
		let gitItem = NSMenuItem()
		let taskItem = NSMenuItem()
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
		mainMenu.addItem(terminalItem)
		mainMenu.addItem(problemItem)
		mainMenu.addItem(commandItem)

		let appMenu = NSMenu()
		let settingsItem = appMenu.addItem(withTitle: L10n.string("Settings..."), action: #selector(AppCoordinator.showSettings(_:)), keyEquivalent: ",")
		settingsItem.target = actionTarget
		appMenu.addItem(.separator())
		appMenu.addItem(withTitle: L10n.string("Quit Itsy"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
		appItem.submenu = appMenu

		let fileMenu = NSMenu(title: L10n.string("File"))
		let newItem = fileMenu.addItem(withTitle: L10n.string("New"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "n")
		newItem.target = documentController
		let newTabItem = fileMenu.addItem(withTitle: L10n.string("New Tab"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "t")
		newTabItem.keyEquivalentModifierMask = [.command, .option]
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: L10n.string("Open..."), action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: L10n.string("Open Folder..."), action: #selector(AppCoordinator.openFolder(_:)), keyEquivalent: "o")
		openFolderItem.keyEquivalentModifierMask = [.command, .option]
		openFolderItem.target = actionTarget
		let openRecentItem = fileMenu.addItem(withTitle: L10n.string("Open Recent"), action: nil, keyEquivalent: "")
		let openRecentMenu = NSMenu(title: L10n.string("Open Recent"))
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
		let gotoWorkspaceSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in Workspace"), action: #selector(AppCoordinator.showWorkspaceSymbolPalette(_:)), keyEquivalent: "t")
		gotoWorkspaceSymbolItem.target = actionTarget
		let gotoFileSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in File"), action: #selector(AppCoordinator.showFileSymbolPalette(_:)), keyEquivalent: "O")
		gotoFileSymbolItem.target = actionTarget
		let outlineItem = navigateMenu.addItem(withTitle: L10n.string("Outline"), action: #selector(AppCoordinator.showOutline(_:)), keyEquivalent: "7")
		outlineItem.keyEquivalentModifierMask = [.command, .option]
		outlineItem.target = actionTarget
		navigateItem.submenu = navigateMenu

		let viewMenu = NSMenu(title: L10n.string("View"))
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
		let gitChangesItem = gitMenu.addItem(withTitle: L10n.string("Git Changes"), action: #selector(AppCoordinator.showGitChanges(_:)), keyEquivalent: "")
		gitChangesItem.target = actionTarget
		let gitRefreshItem = gitMenu.addItem(withTitle: L10n.string("Refresh Git Status"), action: #selector(AppCoordinator.refreshGitChanges(_:)), keyEquivalent: "")
		gitRefreshItem.target = actionTarget
		gitMenu.addItem(.separator())
		let gitStashesItem = gitMenu.addItem(withTitle: L10n.string("Stashes"), action: #selector(AppCoordinator.showGitStashes(_:)), keyEquivalent: "")
		gitStashesItem.target = actionTarget
		let gitStashCurrentItem = gitMenu.addItem(withTitle: L10n.string("Stash Current Changes..."), action: #selector(AppCoordinator.stashCurrentGitChanges(_:)), keyEquivalent: "S")
		gitStashCurrentItem.keyEquivalentModifierMask = [.command, .shift]
		gitStashCurrentItem.target = actionTarget
		gitMenu.addItem(.separator())
		let gitFetchItem = gitMenu.addItem(withTitle: L10n.string("Fetch"), action: #selector(AppCoordinator.fetchGitRemote(_:)), keyEquivalent: "")
		gitFetchItem.target = actionTarget
		let gitPullItem = gitMenu.addItem(withTitle: L10n.string("Pull"), action: #selector(AppCoordinator.pullGitRemote(_:)), keyEquivalent: "")
		gitPullItem.target = actionTarget
		let gitPullRebaseItem = gitMenu.addItem(withTitle: L10n.string("Pull Rebase"), action: #selector(AppCoordinator.pullGitRemoteRebase(_:)), keyEquivalent: "")
		gitPullRebaseItem.target = actionTarget
		let gitPushItem = gitMenu.addItem(withTitle: L10n.string("Push"), action: #selector(AppCoordinator.pushGitRemote(_:)), keyEquivalent: "")
		gitPushItem.target = actionTarget
		gitItem.submenu = gitMenu

		let taskMenu = NSMenu(title: L10n.string("Tasks"))
		let taskRunItem = taskMenu.addItem(withTitle: L10n.string("Run Task"), action: #selector(AppCoordinator.showTasks(_:)), keyEquivalent: "")
		taskRunItem.target = actionTarget
		let taskRefreshItem = taskMenu.addItem(withTitle: L10n.string("Refresh Tasks"), action: #selector(AppCoordinator.refreshTasks(_:)), keyEquivalent: "")
		taskRefreshItem.target = actionTarget
		taskItem.submenu = taskMenu

		let terminalMenu = NSMenu(title: L10n.string("Terminal"))
		let terminalShowItem = terminalMenu.addItem(withTitle: L10n.string("Terminal"), action: #selector(AppCoordinator.showTerminal(_:)), keyEquivalent: "`")
		terminalShowItem.keyEquivalentModifierMask = [.command, .shift]
		terminalShowItem.target = actionTarget
		terminalItem.submenu = terminalMenu

		let problemMenu = NSMenu(title: L10n.string("Problems"))
		let problemShowItem = problemMenu.addItem(withTitle: L10n.string("Problems"), action: #selector(AppCoordinator.showProblems(_:)), keyEquivalent: "M")
		problemShowItem.keyEquivalentModifierMask = [.command, .shift]
		problemShowItem.target = actionTarget
		problemItem.submenu = problemMenu

		let commandMenu = NSMenu(title: L10n.string("Command"))
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
