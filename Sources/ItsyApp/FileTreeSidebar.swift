import AppKit

final class FileTreeNode: NSObject {
	let url: URL
	let isDirectory: Bool
	private var cachedChildren: [FileTreeNode]?

	init(url: URL) {
		self.url = url
		let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
		isDirectory = values?.isDirectory == true
	}

	var title: String {
		url.lastPathComponent.isEmpty ? url.path : url.lastPathComponent
	}

	var children: [FileTreeNode] {
		guard isDirectory else {
			return []
		}
		if let cachedChildren {
			return cachedChildren
		}
		let keys: [URLResourceKey] = [.isDirectoryKey, .isHiddenKey]
		let contents = (try? FileManager.default.contentsOfDirectory(
			at: url,
			includingPropertiesForKeys: keys,
			options: [.skipsPackageDescendants]
		)) ?? []
		let nodes = contents.compactMap { child -> FileTreeNode? in
			let values = try? child.resourceValues(forKeys: Set(keys))
			if values?.isHidden == true {
				return nil
			}
			return FileTreeNode(url: child)
		}
		cachedChildren = nodes.sorted { lhs, rhs in
			if lhs.isDirectory != rhs.isDirectory {
				return lhs.isDirectory && !rhs.isDirectory
			}
			return lhs.title.localizedStandardCompare(rhs.title) == .orderedAscending
		}
		return cachedChildren ?? []
	}
}

final class FileTreeSidebarView: NSView {
	var onOpenFile: ((URL) -> Void)?
	private let outlineView = NSOutlineView()
	private let scrollView = NSScrollView()
	private var rootNode: FileTreeNode?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	func setRootURL(_ url: URL?) {
		rootNode = url.map(FileTreeNode.init(url:))
		outlineView.reloadData()
		if rootNode != nil {
			outlineView.expandItem(rootNode)
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
		guard row >= 0, let node = outlineView.item(atRow: row) as? FileTreeNode else {
			return
		}
		if node.isDirectory {
			if outlineView.isItemExpanded(node) {
				outlineView.collapseItem(node)
			} else {
				outlineView.expandItem(node)
			}
			return
		}
		onOpenFile?(node.url)
	}
}

extension FileTreeSidebarView: NSOutlineViewDataSource, NSOutlineViewDelegate {
	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		if let node = item as? FileTreeNode {
			return node.children.count
		}
		return rootNode == nil ? 0 : 1
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if let node = item as? FileTreeNode {
			return node.children[index]
		}
		guard let rootNode else {
			preconditionFailure("root node requested before workspace root was set")
		}
		return rootNode
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		(item as? FileTreeNode)?.isDirectory == true
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		guard let node = item as? FileTreeNode else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("FileTreeCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingMiddle
		textField.font = .systemFont(ofSize: 12)
		textField.stringValue = node.title
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

final class ItsyWorkspaceController {
	static let shared = ItsyWorkspaceController()

	private weak var documentController: ItsyDocumentController?
	private let sidebars = NSHashTable<FileTreeSidebarView>.weakObjects()
	private var rootURL: URL?

	private init() {}

	var currentRootURL: URL? {
		rootURL
	}

	func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	func register(_ sidebar: FileTreeSidebarView) {
		sidebars.add(sidebar)
		sidebar.onOpenFile = { [weak self] url in
			self?.documentController?.openDocument(at: url)
		}
		sidebar.setRootURL(rootURL)
	}

	func openWorkspace(at url: URL) {
		rootURL = url
		for sidebar in sidebars.allObjects {
			sidebar.setRootURL(url)
		}
	}

	func openFile(at url: URL) -> Bool {
		documentController?.openDocument(at: url) ?? false
	}
}
