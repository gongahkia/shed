import AppKit
import Darwin

final class FileTreeSidebarView: NSView {
	var onOpenFile: ((URL) -> Void)?
	private static let panelSelector = NSSelectorFromString("sharedPreviewPanel")
	private static let dataSourceSelector = NSSelectorFromString("setDataSource:")
	private static let delegateSelector = NSSelectorFromString("setDelegate:")
	private static let reloadSelector = NSSelectorFromString("reloadData")
	private static let orderFrontSelector = NSSelectorFromString("makeKeyAndOrderFront:")
	private static let quickLookFrameworkPaths = [
		"/System/Library/Frameworks/QuickLookUI.framework/QuickLookUI",
		"/System/Library/Frameworks/QuickLookUI.framework/Versions/A/QuickLookUI",
	]
	private static var quickLookHandle: UnsafeMutableRawPointer?
	private let outlineView = NSOutlineView()
	private let scrollView = NSScrollView()
	private var rootURL: NSURL?
	private var childCache: [NSURL: [NSURL]] = [:]
	private var keyMonitor: Any?
	private var previewURL: URL?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	deinit {
		if let keyMonitor {
			NSEvent.removeMonitor(keyMonitor)
		}
	}

	func setRootURL(_ url: URL?) {
		rootURL = url.map { $0 as NSURL }
		childCache.removeAll(keepingCapacity: true)
		outlineView.reloadData()
		if let rootURL {
			outlineView.expandItem(rootURL)
		}
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
		column.title = L10n.string("Files")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.dataSource = self
		outlineView.delegate = self
		outlineView.target = self
		outlineView.doubleAction = #selector(doubleClick(_:))
		outlineView.rowSizeStyle = .small
		outlineView.usesAlternatingRowBackgroundColors = false
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

		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
		])
	}

	@objc private func doubleClick(_ sender: Any?) {
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
		onOpenFile?(url as URL)
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
		return fileURL.lastPathComponent.isEmpty ? fileURL.path : fileURL.lastPathComponent
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
		guard let panel = sharedPanel() else {
			return
		}
		_ = panel.perform(Self.dataSourceSelector, with: self)
		_ = panel.perform(Self.delegateSelector, with: self)
		_ = panel.perform(Self.reloadSelector)
		_ = panel.perform(Self.orderFrontSelector, with: nil)
	}

	@objc(numberOfPreviewItemsInPreviewPanel:)
	func numberOfPreviewItems(in panel: AnyObject) -> Int {
		previewURL == nil ? 0 : 1
	}

	@objc(previewPanel:previewItemAtIndex:)
	func previewPanel(_ panel: AnyObject, previewItemAt index: Int) -> AnyObject? {
		previewURL as NSURL?
	}

	private func sharedPanel() -> NSObject? {
		ensureQuickLookLoaded()
		guard let panelClass = NSClassFromString("QLPreviewPanel") as AnyObject?,
		      panelClass.responds(to: FileTreeSidebarView.panelSelector)
		else {
			return nil
		}
		return panelClass.perform(FileTreeSidebarView.panelSelector)?.takeUnretainedValue() as? NSObject
	}

	private func ensureQuickLookLoaded() {
		guard Self.quickLookHandle == nil, NSClassFromString("QLPreviewPanel") == nil else {
			return
		}
		for path in FileTreeSidebarView.quickLookFrameworkPaths {
			if let handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL) {
				Self.quickLookHandle = handle
				return
			}
		}
	}
}

extension FileTreeSidebarView: NSOutlineViewDataSource, NSOutlineViewDelegate {
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
		let identifier = NSUserInterfaceItemIdentifier("FileTreeCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingMiddle
		textField.font = .systemFont(ofSize: 12)
		textField.stringValue = title(for: url)
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -2),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}
}

enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let sidebars = NSHashTable<FileTreeSidebarView>.weakObjects()
	private static var rootURL: URL?

	static var currentRootURL: URL? {
		rootURL
	}

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	static func register(_ sidebar: FileTreeSidebarView) {
		sidebars.add(sidebar)
		sidebar.onOpenFile = { url in
			documentController?.openDocument(at: url)
		}
		sidebar.setRootURL(rootURL)
	}

	static func openWorkspace(at url: URL) {
		rootURL = url
		for sidebar in sidebars.allObjects {
			sidebar.setRootURL(url)
		}
	}

	static func openFile(at url: URL) -> Bool {
		documentController?.openDocument(at: url) ?? false
	}
}
