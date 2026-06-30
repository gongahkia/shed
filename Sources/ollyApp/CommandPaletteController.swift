import AppKit
import Foundation
import ollyIPC
import ollyLayouts

final class CommandPaletteController: NSWindowController {
    private let catalog: CommandPaletteActionCatalog
    private let executor: CommandPaletteExecutor
    private let searchField = CommandPaletteSearchField()
    private let tableView = NSTableView()
    private let statusLabel = NSTextField(labelWithString: "")
    private var actions: [CommandPaletteAction] = []
    private var filteredActions: [CommandPaletteAction] = []

    init(
        catalog: CommandPaletteActionCatalog = CommandPaletteActionCatalog(),
        executor: CommandPaletteExecutor = CommandPaletteExecutor()
    ) {
        self.catalog = catalog
        self.executor = executor
        let panel = CommandPalettePanel(
            contentRect: CGRect(x: 0, y: 0, width: 680, height: 460),
            styleMask: [.titled, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        super.init(window: panel)
        configureWindow(panel)
        configureContent()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func show() {
        actions = catalog.actions()
        filteredActions = actions
        refreshTable()
        statusLabel.stringValue = ""
        searchField.stringValue = ""
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        window?.makeFirstResponder(searchField)
    }

    func hide() {
        window?.orderOut(nil)
    }

    private func configureWindow(_ panel: CommandPalettePanel) {
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        panel.onCancel = { [weak self] in self?.hide() }
    }

    private func configureContent() {
        let root = NSVisualEffectView()
        root.material = .hudWindow
        root.blendingMode = .behindWindow
        root.state = .active
        root.translatesAutoresizingMaskIntoConstraints = false

        let container = NSStackView()
        container.orientation = .vertical
        container.spacing = 10
        container.edgeInsets = NSEdgeInsets(top: 18, left: 18, bottom: 14, right: 18)
        container.translatesAutoresizingMaskIntoConstraints = false

        configureSearchField()
        configureTableView()

        let scrollView = NSScrollView()
        scrollView.documentView = tableView
        scrollView.hasVerticalScroller = true
        scrollView.drawsBackground = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false

        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.lineBreakMode = .byTruncatingTail

        container.addArrangedSubview(searchField)
        container.addArrangedSubview(scrollView)
        container.addArrangedSubview(statusLabel)
        root.addSubview(container)
        window?.contentView = root

        NSLayoutConstraint.activate([
            container.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            container.topAnchor.constraint(equalTo: root.topAnchor),
            container.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            searchField.heightAnchor.constraint(equalToConstant: 42),
            scrollView.heightAnchor.constraint(equalToConstant: 340),
            statusLabel.heightAnchor.constraint(equalToConstant: 18)
        ])
    }

    private func configureSearchField() {
        searchField.placeholderString = "Search olly actions"
        searchField.font = .systemFont(ofSize: 20)
        searchField.delegate = self
        searchField.onCancel = { [weak self] in self?.hide() }
        searchField.onCommit = { [weak self] in self?.executeSelectedAction() }
        searchField.onMoveSelection = { [weak self] offset in self?.moveSelection(by: offset) }
    }

    private func configureTableView() {
        let column = NSTableColumn(identifier: CommandPaletteRowView.identifier)
        column.resizingMask = .autoresizingMask
        tableView.addTableColumn(column)
        tableView.headerView = nil
        tableView.rowHeight = 58
        tableView.intercellSpacing = CGSize(width: 0, height: 4)
        tableView.backgroundColor = .clear
        tableView.selectionHighlightStyle = .regular
        tableView.delegate = self
        tableView.dataSource = self
        tableView.target = self
        tableView.doubleAction = #selector(executeSelectedAction)
    }

    private func refreshTable() {
        tableView.reloadData()
        if !filteredActions.isEmpty {
            tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
        }
    }

    private func moveSelection(by offset: Int) {
        guard !filteredActions.isEmpty else {
            return
        }
        let current = tableView.selectedRow < 0 ? 0 : tableView.selectedRow
        let next = min(max(current + offset, 0), filteredActions.count - 1)
        tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
        tableView.scrollRowToVisible(next)
    }

    @objc private func executeSelectedAction() {
        let row = tableView.selectedRow
        guard filteredActions.indices.contains(row) else {
            return
        }

        let action = filteredActions[row]
        statusLabel.stringValue = "Running \(action.title)..."
        executor.execute(action) { [weak self] result in
            DispatchQueue.main.async {
                self?.handleExecutionResult(result)
            }
        }
    }

    private func handleExecutionResult(_ result: CommandPaletteExecutionResult) {
        switch result {
        case .success:
            hide()
        case let .failure(message):
            statusLabel.stringValue = message
        }
    }
}

extension CommandPaletteController: NSSearchFieldDelegate {
    func controlTextDidChange(_ notification: Notification) {
        filteredActions = CommandPaletteMatcher.filter(actions, query: searchField.stringValue)
        refreshTable()
    }
}

extension CommandPaletteController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int {
        filteredActions.count
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard filteredActions.indices.contains(row) else {
            return nil
        }
        let rowView = tableView.makeView(
            withIdentifier: CommandPaletteRowView.identifier,
            owner: self
        ) as? CommandPaletteRowView ?? CommandPaletteRowView()
        rowView.identifier = CommandPaletteRowView.identifier
        rowView.configure(with: filteredActions[row])
        return rowView
    }
}

final class CommandPalettePanel: NSPanel {
    var onCancel: (() -> Void)?

    override var canBecomeKey: Bool {
        true
    }

    override var canBecomeMain: Bool {
        true
    }

    override func cancelOperation(_ sender: Any?) {
        onCancel?()
    }
}

final class CommandPaletteSearchField: NSSearchField {
    var onCancel: (() -> Void)?
    var onCommit: (() -> Void)?
    var onMoveSelection: ((Int) -> Void)?

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 36:
            onCommit?()
        case 53:
            onCancel?()
        case 125:
            onMoveSelection?(1)
        case 126:
            onMoveSelection?(-1)
        default:
            super.keyDown(with: event)
        }
    }
}

final class CommandPaletteRowView: NSTableCellView {
    static let identifier = NSUserInterfaceItemIdentifier("CommandPaletteRowView")
    private let titleLabel = NSTextField(labelWithString: "")
    private let detailLabel = NSTextField(labelWithString: "")

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func configure(with action: CommandPaletteAction) {
        configure(title: action.title, detail: action.detail)
    }

    func configure(title: String, detail: String) {
        titleLabel.stringValue = title
        detailLabel.stringValue = detail
        setAccessibilityLabel("\(title), \(detail)")
    }

    private func configure() {
        wantsLayer = true
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
        titleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        detailLabel.font = .systemFont(ofSize: 12)
        detailLabel.textColor = .secondaryLabelColor
        detailLabel.lineBreakMode = .byTruncatingTail

        let stack = NSStackView(views: [titleLabel, detailLabel])
        stack.orientation = .vertical
        stack.spacing = 3
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor)
        ])
    }
}

enum CommandPaletteExecutionResult: Equatable {
    case success
    case failure(String)
}
