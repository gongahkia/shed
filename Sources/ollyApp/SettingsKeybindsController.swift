import AppKit
import Foundation
import ollyDSL

struct SettingsKeybindConflictRow: Equatable {
    let chord: String
    let action: String
    let owner: String
    let detail: String
}

enum SettingsKeybindDiagnosticsRenderer {
    static func rows(from report: HotKeyCollisionReport?) -> [SettingsKeybindConflictRow] {
        report?.collisions.map { collision in
            SettingsKeybindConflictRow(
                chord: collision.chord.description,
                action: String(describing: collision.action),
                owner: collision.externalOwner.rawValue,
                detail: collision.externalDetail
            )
        } ?? []
    }

    static func status(from report: HotKeyCollisionReport?) -> String {
        guard let report else {
            return L10n.s("No hotkey scan has run yet.", "settings keybinds no scan status")
        }
        switch report.collisions.count {
        case 0:
            return L10n.s("No keybind conflicts detected.", "settings keybinds no conflicts status")
        case 1:
            return L10n.s("1 keybind conflict detected.", "settings keybinds one conflict status")
        default:
            return L10n.f("%d keybind conflicts detected.", "conflict count", report.collisions.count)
        }
    }

    static func sourceErrors(from report: HotKeyCollisionReport?) -> String {
        guard let report else {
            return ""
        }
        guard !report.sourceErrors.isEmpty else {
            return L10n.s("No scanner source errors.", "settings keybinds no source errors status")
        }
        return report.sourceErrors.map { error in
            "\(error.owner.rawValue): \(error.detail)"
        }.joined(separator: "\n")
    }
}

final class SettingsKeybindsController: NSObject {
    private let store: HotKeyDiagnosticsStore
    private let diagnostics: HotKeyStartupDiagnostics
    private let scanQueue = DispatchQueue(label: "dev.olly.app.settings.hotkeys", qos: .userInitiated)
    private let statusLabel = NSTextField(labelWithString: "")
    private let tableView = NSTableView()
    private let sourceErrorsTextView = NSTextView()
    private var rows: [SettingsKeybindConflictRow] = []

    init(
        store: HotKeyDiagnosticsStore = .shared,
        diagnostics: HotKeyStartupDiagnostics = HotKeyStartupDiagnostics()
    ) {
        self.store = store
        self.diagnostics = diagnostics
    }

    func makeView() -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 10
        root.edgeInsets = NSEdgeInsets(top: 14, left: 14, bottom: 14, right: 14)
        root.translatesAutoresizingMaskIntoConstraints = false

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        buttonRow.addArrangedSubview(button(L10n.s("Refresh", "refresh"), #selector(refreshFromButton)))
        buttonRow.addArrangedSubview(NSView())

        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        configureTable()
        configureSourceErrorsTextView()

        let tableScrollView = scrollView(for: tableView, height: 220)
        let sourceScrollView = scrollView(for: sourceErrorsTextView, height: 70)
        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(statusLabel)
        root.addArrangedSubview(tableScrollView)
        root.addArrangedSubview(sourceScrollView)
        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 560)
        ])
        refreshFromStore()
        return root
    }

    func refreshFromStore() {
        render(report: store.currentReport())
    }

    @objc private func refreshFromButton() {
        scan()
    }

    private func scan() {
        statusLabel.stringValue = L10n.s("Scanning keybinds...", "settings keybinds scanning status")
        scanQueue.async { [diagnostics, store, weak self] in
            do {
                let report = try diagnostics.scan()
                store.update(report)
                DispatchQueue.main.async {
                    self?.render(report: report)
                }
            } catch {
                store.clear()
                DispatchQueue.main.async {
                    self?.render(error: error)
                }
            }
        }
    }

    private func configureTable() {
        guard tableView.tableColumns.isEmpty else {
            return
        }
        for column in columns() {
            tableView.addTableColumn(column)
        }
        tableView.headerView = NSTableHeaderView()
        tableView.dataSource = self
        tableView.delegate = self
    }

    private func configureSourceErrorsTextView() {
        sourceErrorsTextView.isEditable = false
        sourceErrorsTextView.isSelectable = true
        sourceErrorsTextView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        sourceErrorsTextView.textColor = .secondaryLabelColor
        sourceErrorsTextView.drawsBackground = false
    }

    private func render(report: HotKeyCollisionReport?) {
        rows = SettingsKeybindDiagnosticsRenderer.rows(from: report)
        statusLabel.textColor = rows.isEmpty ? .secondaryLabelColor : .labelColor
        statusLabel.stringValue = SettingsKeybindDiagnosticsRenderer.status(from: report)
        sourceErrorsTextView.string = SettingsKeybindDiagnosticsRenderer.sourceErrors(from: report)
        tableView.reloadData()
    }

    private func render(error: Error) {
        rows = []
        statusLabel.textColor = .labelColor
        statusLabel.stringValue = L10n.s("Hotkey scan failed.", "settings keybinds scan failed status")
        sourceErrorsTextView.string = String(describing: error)
        tableView.reloadData()
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    private func scrollView(for view: NSView, height: CGFloat) -> NSScrollView {
        let scrollView = NSScrollView()
        scrollView.documentView = view
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder
        scrollView.heightAnchor.constraint(equalToConstant: height).isActive = true
        return scrollView
    }

    private func columns() -> [NSTableColumn] {
        [
            ("chord", L10n.s("Chord", "settings keybinds chord column"), 100),
            ("action", L10n.s("Olly Action", "settings keybinds action column"), 140),
            ("owner", L10n.s("Conflicts With", "settings keybinds conflict owner column"), 150),
            ("detail", L10n.s("Detail", "settings keybinds detail column"), 170)
        ].map { identifier, title, width in
            let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(identifier))
            column.title = title
            column.width = CGFloat(width)
            return column
        }
    }
}

extension SettingsKeybindsController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int {
        tableView == self.tableView ? rows.count : 0
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard tableView == self.tableView,
              rows.indices.contains(row),
              let identifier = tableColumn?.identifier else {
            return nil
        }
        let value = value(row: rows[row], identifier: identifier.rawValue)
        let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
        cell.identifier = identifier
        cell.textField = cell.textField ?? NSTextField(labelWithString: "")
        cell.textField?.stringValue = value
        cell.textField?.lineBreakMode = .byTruncatingTail
        if cell.textField?.superview == nil, let textField = cell.textField {
            textField.translatesAutoresizingMaskIntoConstraints = false
            cell.addSubview(textField)
            NSLayoutConstraint.activate([
                textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4),
                textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -4),
                textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor)
            ])
        }
        return cell
    }

    private func value(row: SettingsKeybindConflictRow, identifier: String) -> String {
        switch identifier {
        case "action":
            return row.action
        case "owner":
            return row.owner
        case "detail":
            return row.detail
        default:
            return row.chord
        }
    }
}
