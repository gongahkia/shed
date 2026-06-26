import AppKit
import Foundation
import ollyCore
import ollyKit
import ollyLayouts

@main
enum OllyApp {
    private static let delegate = OllyAppDelegate()

    static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        application.delegate = delegate
        application.run()
    }
}

final class OllyAppDelegate: NSObject, NSApplicationDelegate {
    private var statusController: OllyStatusMenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusController = OllyStatusMenuController()
        statusController?.install()
    }

    func applicationWillTerminate(_ notification: Notification) {
        statusController?.remove()
    }
}

final class OllyStatusMenuController: NSObject {
    private let displayMonitor: DisplayMonitor
    private let statusItem: NSStatusItem
    private var state = OllyMenuState.default

    init(
        displayMonitor: DisplayMonitor = DisplayMonitor(),
        statusItem: NSStatusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    ) {
        self.displayMonitor = displayMonitor
        self.statusItem = statusItem
        super.init()
    }

    func install() {
        configureButton()
        refreshState()
    }

    func remove() {
        NSStatusBar.system.removeStatusItem(statusItem)
    }

    private func configureButton() {
        if let button = statusItem.button {
            button.image = NSImage(
                systemSymbolName: "rectangle.3.group",
                accessibilityDescription: "Olly"
            )
            button.imagePosition = .imageOnly
            button.toolTip = "Olly"
        } else {
            statusItem.button?.title = "Olly"
        }
    }

    private func refreshState() {
        state = makeState()
        rebuildMenu()
    }

    private func makeState() -> OllyMenuState {
        let displays = displayMonitor.displays()
        let activeDisplay = displays.first(where: \.isMain) ?? displays.first
        return OllyMenuState(
            displayName: activeDisplay?.localizedName ?? "No display",
            displayID: activeDisplay?.id,
            activeTags: [0],
            currentEngineID: FloatingLayoutEngine.engineID,
            axStatus: AXPermission.status(prompt: false)
        )
    }

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.autoenablesItems = false
        menu.addItem(disabledItem("Display: \(state.displayLabel)"))
        menu.addItem(disabledItem("Tags: \(state.tagLabel)"))
        menu.addItem(disabledItem("Engine: \(state.currentEngineID.rawValue)"))
        menu.addItem(disabledItem("AX: \(state.axLabel)"))
        menu.addItem(.separator())
        menu.addItem(actionItem("Refresh Status", #selector(refreshStatus)))
        menu.addItem(actionItem("Open Config.swift", #selector(openConfig)))
        menu.addItem(actionItem("Copy `ollyctl state`", #selector(copyStateCommand)))
        menu.addItem(.separator())
        menu.addItem(actionItem("Quit Olly", #selector(quit)))
        statusItem.menu = menu
    }

    private func disabledItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func actionItem(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        item.isEnabled = true
        return item
    }

    @objc private func refreshStatus() {
        refreshState()
    }

    @objc private func openConfig() {
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("Config.swift")
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting([url.deletingLastPathComponent()])
        }
    }

    @objc private func copyStateCommand() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString("ollyctl state", forType: .string)
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }
}

struct OllyMenuState: Equatable {
    let displayName: String
    let displayID: DisplayID?
    let activeTags: [UInt8]
    let currentEngineID: LayoutEngineID
    let axStatus: AXPermissionStatus

    static let `default` = OllyMenuState(
        displayName: "No display",
        displayID: nil,
        activeTags: [0],
        currentEngineID: FloatingLayoutEngine.engineID,
        axStatus: .missing
    )

    var displayLabel: String {
        if let displayID {
            return "\(displayName) (\(displayID))"
        }
        return displayName
    }

    var tagLabel: String {
        activeTags.map { String(Int($0) + 1) }.joined(separator: ", ")
    }

    var axLabel: String {
        switch axStatus {
        case .trusted:
            return "Trusted"
        case .missing:
            return "Missing"
        }
    }
}
