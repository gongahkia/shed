import AppKit
import Darwin
import Foundation
import ItsyEditor

final class FileTreeSidebarController: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
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
	private var rootURL: NSURL?
	private var childCache: [NSURL: [NSURL]] = [:]
	private var gitSnapshot: GitWorkspaceSnapshot?
	private var keyMonitor: Any?
	private var previewURL: URL?
	var openFile: (URL) -> Bool = { _ in false }

	override init() {
		super.init()
		configureView()
		installActions()
	}

	deinit {
		if let keyMonitor {
			NSEvent.removeMonitor(keyMonitor)
		}
	}

	func attach(to window: NSWindow) {
		self.window = window
	}

	func setWorkspaceRootURL(_ url: URL?) {
		rootURL = url.map { $0 as NSURL }
		childCache.removeAll(keepingCapacity: true)
		outlineView.reloadData()
		if let rootURL {
			outlineView.expandItem(rootURL)
		}
	}

	func setGitSnapshot(_ snapshot: GitWorkspaceSnapshot?) {
		gitSnapshot = snapshot
		outlineView.reloadData()
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
			if values?.isHidden == true {
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
		return rootURL == nil ? 0 : 1
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if let url = item as? NSURL {
			return children(of: url)[index]
		}
		guard let rootURL else {
			preconditionFailure("root node requested before workspace root was set")
		}
		return rootURL
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
}
