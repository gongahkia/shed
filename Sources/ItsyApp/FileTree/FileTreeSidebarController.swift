import AppKit
import Darwin
import Foundation
import ItsyEditor

@MainActor final class FileTreeSidebarController: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate, NSMenuDelegate {
	private static let quickLookPanelSelector = NSSelectorFromString("sharedPreviewPanel")
	private static let quickLookDataSourceSelector = NSSelectorFromString("setDataSource:")
	private static let quickLookDelegateSelector = NSSelectorFromString("setDelegate:")
	private static let quickLookReloadSelector = NSSelectorFromString("reloadData")
	private static let quickLookOrderFrontSelector = NSSelectorFromString("makeKeyAndOrderFront:")
	private static let quickLookFrameworkPaths = [
		"/System/Library/Frameworks/QuickLookUI.framework/QuickLookUI",
		"/System/Library/Frameworks/QuickLookUI.framework/Versions/A/QuickLookUI",
	]
	private static var quickLookHandle: UnsafeMutableRawPointer?
	let view = NSView(frame: NSRect(x: 0, y: 0, width: 240, height: 672))
	private let outlineView = NSOutlineView()
	private let scrollView = NSScrollView()
	private weak var window: NSWindow?
	private var rootURLs: [NSURL] = []
	private var childCache: [NSURL: [NSURL]] = [:]
	private var gitSnapshot: GitWorkspaceSnapshot?
	private var keyMonitor: Any?
	private var previewURL: URL?
	private var showsHiddenFiles = false
	var openFile: (URL) -> Bool = { _ in false }
	var createFileRequested: (String, URL) -> URL? = { _, _ in nil }
	var createFolderRequested: (String, URL) -> URL? = { _, _ in nil }
	var renameItemRequested: (URL, String) -> URL? = { _, _ in nil }
	var duplicateItemRequested: (URL) -> URL? = { _ in nil }
	var deleteItemRequested: (URL) -> Void = { _ in }
	var trashItemRequested: (URL) -> Void = { _ in }
	var moveItemRequested: (URL, URL) -> URL? = { _, _ in nil }

	override init() {
		super.init()
		configureView()
		installActions()
	}

	deinit {
		MainActor.assumeIsolated {
			if let keyMonitor {
				NSEvent.removeMonitor(keyMonitor)
			}
		}
	}

	func attach(to window: NSWindow) {
		self.window = window
	}

	func setWorkspaceRootURL(_ url: URL?) {
		setWorkspaceRootURLs(url.map { [$0] } ?? [])
	}

	func setWorkspaceRootURLs(_ urls: [URL]) {
		rootURLs = urls.map { $0 as NSURL }
		childCache.removeAll(keepingCapacity: true)
		outlineView.reloadData()
		for rootURL in rootURLs {
			outlineView.expandItem(rootURL)
		}
	}

	func setGitSnapshot(_ snapshot: GitWorkspaceSnapshot?) {
		gitSnapshot = snapshot
		outlineView.reloadData()
	}

	func toggleHiddenFiles() {
		showsHiddenFiles.toggle()
		childCache.removeAll(keepingCapacity: true)
		outlineView.reloadData()
		for rootURL in rootURLs {
			outlineView.expandItem(rootURL)
		}
	}

	private func configureView() {
		view.wantsLayer = true
		view.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
		column.title = L10n.string("Files")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.rowSizeStyle = .small
		outlineView.usesAlternatingRowBackgroundColors = false

		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		view.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: view.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
		])
	}

	private func installActions() {
		outlineView.dataSource = self
		outlineView.delegate = self
		outlineView.target = self
		outlineView.doubleAction = #selector(doubleClickFileTree(_:))
		outlineView.registerForDraggedTypes([.fileURL])
		outlineView.setDraggingSourceOperationMask(.move, forLocal: true)
		let menu = NSMenu()
		menu.delegate = self
		outlineView.menu = menu
		keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
			guard let self,
			      event.keyCode == 49,
			      self.window?.isKeyWindow == true,
			      self.window?.firstResponder === self.outlineView
			else {
				return event
			}
			self.previewSelection()
			return nil
		}
	}

	@objc private func doubleClickFileTree(_ sender: Any?) {
		let row = outlineView.clickedRow
		guard row >= 0, let url = outlineView.item(atRow: row) as? NSURL else {
			return
		}
		if isDirectory(url) {
			if outlineView.isItemExpanded(url) {
				outlineView.collapseItem(url)
			} else {
				outlineView.expandItem(url)
			}
			return
		}
		_ = openFile(url as URL)
	}

	private func previewSelection() {
		let row = outlineView.selectedRow
		guard row >= 0,
		      let url = outlineView.item(atRow: row) as? NSURL,
		      !isDirectory(url)
		else {
			return
		}
		preview(url as URL)
	}

	private func isDirectory(_ url: NSURL) -> Bool {
		let values = try? (url as URL).resourceValues(forKeys: [.isDirectoryKey])
		return values?.isDirectory == true
	}

	private func isWorkspaceRoot(_ url: NSURL) -> Bool {
		let path = (url as URL).standardizedFileURL.path
		return rootURLs.contains { ($0 as URL).standardizedFileURL.path == path }
	}

	private func title(for url: NSURL) -> String {
		let fileURL = url as URL
		let title = fileURL.lastPathComponent.isEmpty ? fileURL.path : fileURL.lastPathComponent
		guard let status = gitStatus(for: fileURL) else {
			return title
		}
		return "\(title) [\(status)]"
	}

	private func gitStatus(for url: URL) -> String? {
		guard let entry = gitSnapshot?.entry(for: url) else {
			return nil
		}
		if entry.kind == .untracked {
			return "?"
		}
		if entry.kind == .unmerged {
			return "U"
		}
		if entry.isStaged, entry.isUnstaged {
			return "*"
		}
		if entry.isStaged {
			return entry.indexStatus.map(String.init)
		}
		if entry.isUnstaged {
			return entry.worktreeStatus.map(String.init)
		}
		return nil
	}

	private func children(of url: NSURL) -> [NSURL] {
		guard isDirectory(url) else {
			return []
		}
		if let cached = childCache[url] {
			return cached
		}
		let keys: [URLResourceKey] = [.isDirectoryKey, .isHiddenKey]
		let contents = (try? FileManager.default.contentsOfDirectory(
			at: url as URL,
			includingPropertiesForKeys: keys,
			options: [.skipsPackageDescendants]
		)) ?? []
		let nodes = contents.compactMap { child -> NSURL? in
			let values = try? child.resourceValues(forKeys: Set(keys))
			if !showsHiddenFiles, values?.isHidden == true {
				return nil
			}
			return child as NSURL
		}
		let sorted = nodes.sorted { lhs, rhs in
			let lhsDirectory = isDirectory(lhs)
			let rhsDirectory = isDirectory(rhs)
			if lhsDirectory != rhsDirectory {
				return lhsDirectory && !rhsDirectory
			}
			return title(for: lhs).localizedStandardCompare(title(for: rhs)) == .orderedAscending
		}
		childCache[url] = sorted
		return sorted
	}

	func preview(_ url: URL) {
		previewURL = url
		guard let panel = sharedQuickLookPanel() else {
			return
		}
		_ = panel.perform(Self.quickLookDataSourceSelector, with: self)
		_ = panel.perform(Self.quickLookDelegateSelector, with: self)
		_ = panel.perform(Self.quickLookReloadSelector)
		_ = panel.perform(Self.quickLookOrderFrontSelector, with: nil)
	}

	@objc(numberOfPreviewItemsInPreviewPanel:)
	func numberOfPreviewItems(in panel: AnyObject) -> Int {
		previewURL == nil ? 0 : 1
	}

	@objc(previewPanel:previewItemAtIndex:)
	func previewPanel(_ panel: AnyObject, previewItemAt index: Int) -> AnyObject? {
		previewURL as NSURL?
	}

	private func sharedQuickLookPanel() -> NSObject? {
		ensureQuickLookLoaded()
		guard let panelClass = NSClassFromString("QLPreviewPanel") as AnyObject?,
		      panelClass.responds(to: Self.quickLookPanelSelector)
		else {
			return nil
		}
		return panelClass.perform(Self.quickLookPanelSelector)?.takeUnretainedValue() as? NSObject
	}

	private func ensureQuickLookLoaded() {
		guard Self.quickLookHandle == nil, NSClassFromString("QLPreviewPanel") == nil else {
			return
		}
		for path in Self.quickLookFrameworkPaths {
			if let handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL) {
				Self.quickLookHandle = handle
				return
			}
		}
	}

	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		if let url = item as? NSURL {
			return children(of: url).count
		}
		return rootURLs.count
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if let url = item as? NSURL {
			return children(of: url)[index]
		}
		return rootURLs[index]
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		(item as? NSURL).map(isDirectory(_:)) == true
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		guard let url = item as? NSURL else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("FileTreeIconCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let imageView = cell.imageView ?? NSImageView()
		imageView.image = NSWorkspace.shared.icon(forFile: (url as URL).path)
		imageView.image?.size = NSSize(width: 16, height: 16)
		imageView.imageScaling = .scaleProportionallyDown
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingMiddle
		textField.font = .systemFont(ofSize: 12)
		textField.stringValue = title(for: url)
		if imageView.superview == nil {
			imageView.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(imageView)
			cell.imageView = imageView
		}
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			cell.textField = textField
		}
		if cell.constraints.isEmpty {
			NSLayoutConstraint.activate([
				imageView.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
				imageView.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				imageView.widthAnchor.constraint(equalToConstant: 16),
				imageView.heightAnchor.constraint(equalToConstant: 16),
				textField.leadingAnchor.constraint(equalTo: imageView.trailingAnchor, constant: 6),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -2),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
		}
		return cell
	}

	func menuNeedsUpdate(_ menu: NSMenu) {
		menu.removeAllItems()
		let selected = contextURL()
		guard let directory = contextDirectory(for: selected) else {
			return
		}
		let newFile = menu.addItem(withTitle: L10n.string("New File"), action: #selector(newFile(_:)), keyEquivalent: "")
		let newFolder = menu.addItem(withTitle: L10n.string("New Folder"), action: #selector(newFolder(_:)), keyEquivalent: "")
		for item in [newFile, newFolder] {
			item.target = self
			item.representedObject = directory
		}
		guard let selected, !isWorkspaceRoot(selected) else {
			return
		}
		menu.addItem(.separator())
		menu.addItem(withTitle: L10n.string("Rename"), action: #selector(renameItem(_:)), keyEquivalent: "")
		menu.addItem(withTitle: L10n.string("Duplicate"), action: #selector(duplicateItem(_:)), keyEquivalent: "")
		menu.addItem(withTitle: L10n.string("Delete"), action: #selector(deleteItem(_:)), keyEquivalent: "")
		menu.addItem(withTitle: L10n.string("Move to Trash"), action: #selector(moveItemToTrash(_:)), keyEquivalent: "")
		for item in menu.items {
			item.target = self
			item.representedObject = directory
		}
	}

	@objc private func newFile(_ sender: NSMenuItem) {
		guard let directory = sender.representedObject as? URL,
		      let name = promptName(title: L10n.string("New File"), defaultName: "")
		else {
			return
		}
		if let url = createFileRequested(name, directory) {
			_ = openFile(url)
		}
	}

	@objc private func newFolder(_ sender: NSMenuItem) {
		guard let directory = sender.representedObject as? URL,
		      let name = promptName(title: L10n.string("New Folder"), defaultName: "")
		else {
			return
		}
		_ = createFolderRequested(name, directory)
	}

	@objc private func renameItem(_ sender: NSMenuItem) {
		guard let url = selectedURLForAction(),
		      let name = promptName(title: L10n.string("Rename"), defaultName: url.lastPathComponent)
		else {
			return
		}
		_ = renameItemRequested(url, name)
	}

	@objc private func duplicateItem(_ sender: NSMenuItem) {
		guard let url = selectedURLForAction() else {
			return
		}
		_ = duplicateItemRequested(url)
	}

	@objc private func deleteItem(_ sender: NSMenuItem) {
		guard let url = selectedURLForAction(), confirmDestructive(title: L10n.string("Delete \(url.lastPathComponent)?")) else {
			return
		}
		deleteItemRequested(url)
	}

	@objc private func moveItemToTrash(_ sender: NSMenuItem) {
		guard let url = selectedURLForAction() else {
			return
		}
		trashItemRequested(url)
	}

	func outlineView(_ outlineView: NSOutlineView, pasteboardWriterForItem item: Any) -> NSPasteboardWriting? {
		guard let url = item as? NSURL, !isWorkspaceRoot(url) else {
			return nil
		}
		return url
	}

	func outlineView(
		_ outlineView: NSOutlineView,
		validateDrop info: NSDraggingInfo,
		proposedItem item: Any?,
		proposedChildIndex index: Int
	) -> NSDragOperation {
		guard info.draggingSource as AnyObject? === outlineView,
		      let directory = dropDirectory(for: item),
		      canDrop(draggedFileURLs(info.draggingPasteboard), into: directory)
		else {
			return []
		}
		return .move
	}

	func outlineView(
		_ outlineView: NSOutlineView,
		acceptDrop info: NSDraggingInfo,
		item: Any?,
		childIndex index: Int
	) -> Bool {
		guard info.draggingSource as AnyObject? === outlineView,
		      let directory = dropDirectory(for: item)
		else {
			return false
		}
		let urls = draggedFileURLs(info.draggingPasteboard)
		for url in urls {
			_ = moveItemRequested(url, directory)
		}
		return !urls.isEmpty
	}

	private func contextURL() -> NSURL? {
		let row = outlineView.clickedRow >= 0 ? outlineView.clickedRow : outlineView.selectedRow
		guard row >= 0 else {
			return nil
		}
		outlineView.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
		return outlineView.item(atRow: row) as? NSURL
	}

	private func selectedURLForAction() -> URL? {
		contextURL().map { $0 as URL }
	}

	private func contextDirectory(for url: NSURL?) -> URL? {
		guard let url else {
			return rootURLs.first.map { $0 as URL }
		}
		if isDirectory(url) {
			return url as URL
		}
		return (url as URL).deletingLastPathComponent()
	}

	private func dropDirectory(for item: Any?) -> URL? {
		guard let url = item as? NSURL else {
			return nil
		}
		if isDirectory(url) {
			return url as URL
		}
		return (url as URL).deletingLastPathComponent()
	}

	private func draggedFileURLs(_ pasteboard: NSPasteboard) -> [URL] {
		(pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL]) ?? []
	}

	private func canDrop(_ urls: [URL], into directory: URL) -> Bool {
		guard !urls.isEmpty else {
			return false
		}
		let directoryPath = directory.standardizedFileURL.path
		return !urls.contains { url in
			let path = url.standardizedFileURL.path
			return directoryPath == path || directoryPath.hasPrefix(path + "/")
		}
	}

	private func promptName(title: String, defaultName: String) -> String? {
		let alert = NSAlert()
		alert.messageText = title
		let field = NSTextField(string: defaultName)
		field.frame = NSRect(x: 0, y: 0, width: 280, height: 24)
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("OK"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		let name = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		return name.isEmpty ? nil : name
	}

	private func confirmDestructive(title: String) -> Bool {
		let alert = NSAlert()
		alert.messageText = title
		alert.addButton(withTitle: L10n.string("Delete"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		alert.alertStyle = .warning
		return alert.runModal() == .alertFirstButtonReturn
	}
}
