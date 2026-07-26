import AppKit
import Foundation
import ollyDSL
import ollyIPC
import ollyRuntime

final class SettingsWindowController: NSWindowController {
    private let runtime: OllyRuntime?
    let sourceURL: URL
    private let reloader: ConfigReloader
    let fileManager: FileManager
    private let reloadQueue = DispatchQueue(label: "dev.olly.app.settings.reload", qos: .userInitiated)
    private let pathLabel = NSTextField(labelWithString: "")
    let statusLabel = NSTextField(labelWithString: "")
    let errorTextView = NSTextView()
    private let profilePopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let createConfigButton = NSButton(title: L10n.s("Create Config", "create"), target: nil, action: nil)
    private let reloadButton = NSButton(title: L10n.s("Reload", "reload"), target: nil, action: nil)
    private let cooperativeAppsTableView = NSTableView()
    private var cooperativeAppsRows: [IPCCooperativeAppInfo] = []
    private var playgroundController: ConfigPlaygroundWindowController?
    private var errorLogController: SettingsErrorLogController?
    private var keybindsController: SettingsKeybindsController?

    init(
        runtime: OllyRuntime? = nil,
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        reloader: ConfigReloader? = nil,
        fileManager: FileManager = .default
    ) {
        self.runtime = runtime
        self.sourceURL = sourceURL
        self.fileManager = fileManager
        self.reloader = reloader ?? ConfigReloader(loader: Self.defaultLoader(sourceURL: sourceURL))
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 620, height: 420),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        super.init(window: window)
        configureWindow(window)
        configureContent()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func show() {
        pathLabel.stringValue = sourceURL.path
        refreshCreateConfigButton()
        refreshCooperativeApps()
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    private static func defaultLoader(sourceURL: URL) -> ConfigLoader {
        ConfigLoader(sourceURL: sourceURL, moduleSearchPaths: defaultModuleSearchPaths())
    }

    private static func defaultModuleSearchPaths() -> [URL] {
        let modulesURL = Bundle.main.bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        return [modulesURL]
    }

    private func configureWindow(_ window: NSWindow) {
        window.title = L10n.s("Olly Settings", "settings window title")
        window.isReleasedWhenClosed = false
    }

    private func configureContent() {
        let tabView = NSTabView()
        tabView.translatesAutoresizingMaskIntoConstraints = false
        let configTab = NSTabViewItem(identifier: "config")
        configTab.label = L10n.s("Config", "settings config tab")
        configTab.view = makeConfigView()
        let cooperativeTab = NSTabViewItem(identifier: "cooperative-apps")
        cooperativeTab.label = L10n.s("Cooperative Apps", "settings cooperative apps tab")
        cooperativeTab.view = makeCooperativeAppsView()
        let keybindsController = SettingsKeybindsController()
        self.keybindsController = keybindsController
        let keybindsTab = NSTabViewItem(identifier: "keybinds")
        keybindsTab.label = L10n.s("Keybinds", "settings keybinds tab")
        keybindsTab.view = keybindsController.makeView()
        let errorsController = SettingsErrorLogController(runtime: runtime)
        errorLogController = errorsController
        let errorsTab = NSTabViewItem(identifier: "errors")
        errorsTab.label = L10n.s("Errors", "settings errors tab")
        errorsTab.view = errorsController.makeView()
        tabView.addTabViewItem(configTab)
        tabView.addTabViewItem(cooperativeTab)
        tabView.addTabViewItem(keybindsTab)
        tabView.addTabViewItem(errorsTab)
        window?.contentView = tabView

        NSLayoutConstraint.activate([
            tabView.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
            tabView.heightAnchor.constraint(greaterThanOrEqualToConstant: 360)
        ])
    }

    private func makeConfigView() -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 12
        root.edgeInsets = NSEdgeInsets(top: 18, left: 18, bottom: 18, right: 18)
        root.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = NSTextField(labelWithString: L10n.s("Config.swift", "settings config title"))
        titleLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        pathLabel.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        pathLabel.textColor = .secondaryLabelColor
        pathLabel.lineBreakMode = .byTruncatingMiddle
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.stringValue = L10n.s("No reload run yet", "settings initial reload status")

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        configureProfilePopUp()
        createConfigButton.target = self
        createConfigButton.action = #selector(createConfig)
        buttonRow.addArrangedSubview(button(L10n.s("Open in Editor", "open config"), #selector(openConfig)))
        buttonRow.addArrangedSubview(createConfigButton)
        buttonRow.addArrangedSubview(profilePopUp)
        reloadButton.target = self
        reloadButton.action = #selector(reloadConfig)
        buttonRow.addArrangedSubview(reloadButton)
        buttonRow.addArrangedSubview(button(L10n.s("Export Config...", "export config"), #selector(exportConfig)))
        buttonRow.addArrangedSubview(button(L10n.s("Import Config...", "import config"), #selector(importConfig)))
        buttonRow.addArrangedSubview(button(L10n.s("Playground...", "playground"), #selector(openPlayground)))
        buttonRow.addArrangedSubview(NSView())

        let errorScrollView = makeErrorScrollView()
        root.addArrangedSubview(titleLabel)
        root.addArrangedSubview(pathLabel)
        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(statusLabel)
        root.addArrangedSubview(errorScrollView)

        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
            errorScrollView.heightAnchor.constraint(equalToConstant: 240)
        ])
        return root
    }

    private func makeErrorScrollView() -> NSScrollView {
        errorTextView.isEditable = false
        errorTextView.isSelectable = true
        errorTextView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        errorTextView.string = L10n.s("Last compile error appears here.", "settings compile error placeholder")
        errorTextView.textColor = .secondaryLabelColor
        errorTextView.drawsBackground = false

        let scrollView = NSScrollView()
        scrollView.documentView = errorTextView
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder
        return scrollView
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    private func configureProfilePopUp() {
        profilePopUp.removeAllItems()
        for profile in ConfigTemplateProfile.allCases {
            profilePopUp.addItem(withTitle: profile.displayName)
            profilePopUp.lastItem?.representedObject = profile.rawValue
        }
        profilePopUp.selectItem(withTitle: ConfigTemplateProfile.defaultProfile.displayName)
    }

    @objc private func openConfig() {
        do {
            try ensureConfigExists(profile: selectedProfile())
            NSWorkspace.shared.open(sourceURL)
            statusLabel.stringValue = L10n.s("Opened Config.swift", "settings opened config status")
            refreshCreateConfigButton()
        } catch {
            showError(error)
        }
    }

    @objc private func createConfig() {
        do {
            try ensureConfigExists(profile: selectedProfile())
            statusLabel.stringValue = L10n.f("Created %@ config", "created", selectedProfile().displayName)
            errorTextView.string = ""
        } catch {
            showError(error)
        }
        refreshCreateConfigButton()
    }

    @objc private func reloadConfig() {
        reloadButton.isEnabled = false
        statusLabel.stringValue = L10n.s("Reloading...", "settings reload in progress status")
        reloadQueue.async { [weak self] in
            guard let self else {
                return
            }
            let event = reloader.reloadNow()
            DispatchQueue.main.async {
                self.reloadButton.isEnabled = true
                self.handleReloadEvent(event)
            }
        }
    }

    @objc private func openPlayground() {
        let controller = ConfigPlaygroundWindowController(
            sourceURL: sourceURL,
            moduleSearchPaths: Self.defaultModuleSearchPaths()
        )
        controller.onClose = { [weak self] in
            self?.playgroundController = nil
        }
        playgroundController = controller
        guard let sheet = controller.window, let window else {
            controller.showWindow(nil)
            return
        }
        window.beginSheet(sheet)
    }

    private func handleReloadEvent(_ event: ConfigReloadEvent) {
        switch event {
        case let .reloaded(config):
            let source = config.didCompile ? L10n.s("compiled", "source") : L10n.s("cache", "source")
            statusLabel.stringValue = L10n.f("Reloaded from %@", "settings reload success status", source)
            errorTextView.string = ""
            refreshCooperativeApps()
        case let .failed(failure):
            statusLabel.stringValue = L10n.s("Reload failed", "settings reload failed status")
            errorTextView.textColor = .labelColor
            errorTextView.string = failure.message
        }
    }

    func selectedProfile() -> ConfigTemplateProfile {
        guard let rawValue = profilePopUp.selectedItem?.representedObject as? String,
              let profile = try? ConfigTemplateProfile(name: rawValue) else {
            return .defaultProfile
        }
        return profile
    }

    private func ensureConfigExists(profile: ConfigTemplateProfile) throws {
        try Self.ensureConfigExists(profile: profile, sourceURL: sourceURL, fileManager: fileManager)
    }

    static func ensureConfigExists(
        profile: ConfigTemplateProfile,
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        fileManager: FileManager = .default
    ) throws {
        try fileManager.createDirectory(at: sourceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard !fileManager.fileExists(atPath: sourceURL.path) else { return }
        try profile.source.write(to: sourceURL, atomically: true, encoding: .utf8)
    }

    func refreshCreateConfigButton() {
        createConfigButton.isEnabled = !fileManager.fileExists(atPath: sourceURL.path)
    }

    func showError(_ error: Error) {
        statusLabel.stringValue = L10n.s("Settings action failed", "settings action failed status")
        errorTextView.textColor = .labelColor
        errorTextView.string = String(describing: error)
    }
}

private extension SettingsWindowController {
    func makeCooperativeAppsView() -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 10
        root.edgeInsets = NSEdgeInsets(top: 14, left: 14, bottom: 14, right: 14)
        root.translatesAutoresizingMaskIntoConstraints = false

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        buttonRow.addArrangedSubview(button(
            L10n.s("Refresh", "refresh apps"),
            #selector(refreshCooperativeAppsFromButton)
        ))
        buttonRow.addArrangedSubview(NSView())

        configureCooperativeAppsTable()
        let scrollView = NSScrollView()
        scrollView.documentView = cooperativeAppsTableView
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder

        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(scrollView)
        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
            scrollView.heightAnchor.constraint(equalToConstant: 300)
        ])
        return root
    }

    func configureCooperativeAppsTable() {
        guard cooperativeAppsTableView.tableColumns.isEmpty else {
            return
        }
        for column in cooperativeAppsColumns() {
            cooperativeAppsTableView.addTableColumn(column)
        }
        cooperativeAppsTableView.headerView = NSTableHeaderView()
        cooperativeAppsTableView.dataSource = self
        cooperativeAppsTableView.delegate = self
    }

    func cooperativeAppsColumns() -> [NSTableColumn] {
        [
            ("bundleID", L10n.s("Bundle ID", "cooperative apps bundle column"), 290),
            ("behavior", L10n.s("Behavior", "cooperative apps behavior column"), 150),
            ("windows", L10n.s("Windows", "cooperative apps windows column"), 70)
        ].map { identifier, title, width in
            let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(identifier))
            column.title = title
            column.width = CGFloat(width)
            return column
        }
    }

    @objc func refreshCooperativeAppsFromButton() {
        refreshCooperativeApps()
    }

    func refreshCooperativeApps() {
        guard let runtime else {
            cooperativeAppsRows = []
            cooperativeAppsTableView.reloadData()
            return
        }
        Task { [weak self, runtime] in
            let info = await runtime.cooperativeAppsInfo()
            await MainActor.run {
                self?.cooperativeAppsRows = info.apps
                self?.cooperativeAppsTableView.reloadData()
            }
        }
    }
}

extension SettingsWindowController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int {
        tableView == cooperativeAppsTableView ? cooperativeAppsRows.count : 0
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard tableView == cooperativeAppsTableView,
              cooperativeAppsRows.indices.contains(row),
              let identifier = tableColumn?.identifier else {
            return nil
        }
        let value = cooperativeAppsValue(row: cooperativeAppsRows[row], identifier: identifier.rawValue)
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

    private func cooperativeAppsValue(row: IPCCooperativeAppInfo, identifier: String) -> String {
        switch identifier {
        case "behavior":
            return row.behavior
        case "windows":
            return String(row.detectedWindowCount)
        default:
            return row.bundleID
        }
    }
}
