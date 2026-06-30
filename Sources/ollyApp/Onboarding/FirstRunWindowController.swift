import AppKit
import Foundation
import ollyDSL
import ollyKit

final class FirstRunWindowController: NSWindowController, NSWindowDelegate {
    typealias AXStatusProvider = () -> AXPermissionStatus
    typealias DisplayProvider = () -> [Display]

    var onComplete: (() -> Void)?

    private let sourceURL: URL
    private let fileManager: FileManager
    private let axStatusProvider: AXStatusProvider
    private let displayProvider: DisplayProvider
    private let safeZoneCalculator: SafeZoneCalculator
    private var step: FirstRunStep = .welcome
    private var selectedProfile = ConfigTemplateProfile.defaultProfile
    private let profilePopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private var axStatusText = ""

    init(
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        fileManager: FileManager = .default,
        axStatusProvider: @escaping AXStatusProvider = { AXPermission.status(prompt: false) },
        displayProvider: @escaping DisplayProvider = { DisplayMonitor().displays() },
        safeZoneCalculator: SafeZoneCalculator = SafeZoneCalculator()
    ) {
        self.sourceURL = sourceURL
        self.fileManager = fileManager
        self.axStatusProvider = axStatusProvider
        self.displayProvider = displayProvider
        self.safeZoneCalculator = safeZoneCalculator
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 720, height: 520),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = L10n.s("Olly First Run", "first-run window title")
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
        configureProfilePopUp()
        render()
        window.center()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func showWindow(_ sender: Any?) {
        super.showWindow(sender)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        _ = sender
        return true
    }

    func selectProfile(_ profile: ConfigTemplateProfile) {
        selectedProfile = profile
        profilePopUp.selectItem(withTitle: profile.displayName)
    }

    func completeSetup() throws {
        try SettingsWindowController.ensureConfigExists(
            profile: selectedProfile,
            sourceURL: sourceURL,
            fileManager: fileManager
        )
        onComplete?()
        close()
    }
}

private extension FirstRunWindowController {
    private func render() {
        if step == .accessibility {
            AXPermission.requestSystemPrompt()
            updateAXStatusText()
        }
        window?.contentViewController = FirstRunStepViewController(view: rootView())
    }

    private func rootView() -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 14
        root.edgeInsets = NSEdgeInsets(top: 22, left: 24, bottom: 18, right: 24)
        root.addArrangedSubview(progressLabel())
        root.addArrangedSubview(titleLabel(step.title))
        root.addArrangedSubview(stepBody())
        root.addArrangedSubview(NSView())
        root.addArrangedSubview(buttonRow())
        return root
    }

    private func progressLabel() -> NSTextField {
        let index = (FirstRunStep.allCases.firstIndex(of: step) ?? 0) + 1
        let label = NSTextField(labelWithString: L10n.f(
            "Step %d of %d",
            "first-run progress label",
            index,
            FirstRunStep.allCases.count
        ))
        label.textColor = .secondaryLabelColor
        label.font = .systemFont(ofSize: 12, weight: .medium)
        return label
    }

    private func titleLabel(_ title: String) -> NSTextField {
        let label = NSTextField(labelWithString: title)
        label.font = .systemFont(ofSize: 22, weight: .semibold)
        return label
    }

    private func stepBody() -> NSView {
        switch step {
        case .welcome: return welcomeView()
        case .accessibility: return accessibilityView()
        case .display: return displayView()
        case .preset: return presetView()
        case .cheatsheet: return cheatsheetView()
        case .done: return doneView()
        }
    }

    private func welcomeView() -> NSView {
        textView(L10n.s("""
        Olly controls windows through public Accessibility APIs only. Setup will request permission, detect display \
        safe zones, create a starter Config.swift, and show the keybinds from the chosen preset.
        """, "first-run welcome body"))
    }

    private func accessibilityView() -> NSView {
        let stack = verticalStack(spacing: 10)
        stack.addArrangedSubview(textView(L10n.s("""
        Enable Olly in Privacy & Security > Accessibility so it can inspect, focus, move, and resize windows.
        """, "first-run accessibility body")))
        stack.addArrangedSubview(deepLinkField())
        stack.addArrangedSubview(axButtonRow())
        stack.addArrangedSubview(NSTextField(labelWithString: axStatusText))
        return stack
    }

    private func displayView() -> NSView {
        let stack = verticalStack(spacing: 10)
        let displays = displayProvider()
        if displays.isEmpty {
            stack.addArrangedSubview(textView(L10n.s(
                "No displays were reported by AppKit. Olly will use defaults.",
                "first-run no displays body"
            )))
        }
        for display in displays {
            stack.addArrangedSubview(displayRow(display))
        }
        stack.addArrangedSubview(codeBlock("""
        SafeZones {
            notchPadding(\(Int(SafeZoneCalculator.defaultNotchPadding)))
        }
        """))
        return scroll(stack, height: 290)
    }

    private func presetView() -> NSView {
        let stack = verticalStack(spacing: 12)
        stack.addArrangedSubview(textView(L10n.f(
            "Choose the starting config profile to write to %@.",
            "first-run preset body",
            sourceURL.path
        )))
        stack.addArrangedSubview(profilePopUp)
        stack.addArrangedSubview(textView(selectedProfile.summary))
        return stack
    }

    private func cheatsheetView() -> NSView {
        let view = CheatsheetView()
        view.configure(entries: CheatsheetCatalog.entries(from: previewKeybinds()))
        view.heightAnchor.constraint(equalToConstant: 300).isActive = true
        return view
    }

    private func doneView() -> NSView {
        textView(L10n.f("""
        Setup is ready to write %@ to Config.swift. You can edit or reload it from \
        the menu bar after this window closes.
        """, "first-run done body", selectedProfile.displayName))
    }

    private func buttonRow() -> NSView {
        let row = NSStackView()
        row.orientation = .horizontal
        row.spacing = 8
        row.addArrangedSubview(button(L10n.s("Back", "back"), #selector(back), enabled: step.rawValue > 0))
        row.addArrangedSubview(NSView())
        let nextTitle = step == .done ? L10n.s("Finish", "finish") : L10n.s("Continue", "continue")
        let canContinue = step != .accessibility || axStatusProvider() == .trusted
        row.addArrangedSubview(button(nextTitle, #selector(next), enabled: canContinue))
        return row
    }

    private func displayRow(_ display: Display) -> NSView {
        let result = safeZoneCalculator.result(for: display)
        let title = L10n.f(
            "%@ %dx%d",
            "first-run display title",
            display.localizedName,
            Int(display.frame.width),
            Int(display.frame.height)
        )
        let detail = reserveSummary(result)
        let stack = verticalStack(spacing: 4)
        let titleLabel = NSTextField(labelWithString: title)
        titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        stack.addArrangedSubview(titleLabel)
        stack.addArrangedSubview(textView(detail, width: 620))
        return stack
    }

    private func reserveSummary(_ result: SafeZoneResult) -> String {
        guard !result.reserves.isEmpty else {
            return L10n.s("No menu bar, notch, or dock reserves detected.", "first-run no safe zone reserves")
        }
        return result.reserves.map { reserve in
            L10n.f(
                "%@: %dx%dpt",
                "first-run safe zone reserve summary",
                reserve.kind.rawValue,
                Int(reserve.rect.width),
                Int(reserve.rect.height)
            )
        }.joined(separator: ", ")
    }

    private func deepLinkField() -> NSTextField {
        let field = NSTextField(labelWithString: AXPermission.accessibilitySettingsDeepLink)
        field.isSelectable = true
        field.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        field.lineBreakMode = .byTruncatingMiddle
        return field
    }

    private func axButtonRow() -> NSView {
        let row = NSStackView()
        row.orientation = .horizontal
        row.spacing = 8
        row.addArrangedSubview(button(L10n.s("Open System Settings", "open AX"), #selector(openSettings)))
        row.addArrangedSubview(button(L10n.s("Copy Deeplink", "copy link"), #selector(copyDeepLink)))
        row.addArrangedSubview(button(L10n.s("Refresh", "first-run AX refresh button"), #selector(refreshAXStatus)))
        return row
    }

    private func configureProfilePopUp() {
        profilePopUp.removeAllItems()
        for profile in ConfigTemplateProfile.allCases {
            profilePopUp.addItem(withTitle: profile.displayName)
            profilePopUp.lastItem?.representedObject = profile.rawValue
        }
        profilePopUp.target = self
        profilePopUp.action = #selector(profileChanged)
        selectProfile(.defaultProfile)
    }

    private func previewKeybinds() -> Keybinds {
        switch selectedProfile {
        case .minimal:
            return Keybinds { Keybind(KeyChord([.command, .option], .return), do: .cycleEngine) }
        case .niri:
            return Keybinds {
                Keybind(KeyChord([.option], .h), do: .focus(.left))
                Keybind(KeyChord([.option], .l), do: .focus(.right))
                Keybind(KeyChord([.option], .tab), do: .focus(.next))
            }
        default:
            return Keybinds {
                Keybind(KeyChord([.option], .h), do: .focus(.left))
                Keybind(KeyChord([.option], .j), do: .focus(.down))
                Keybind(KeyChord([.option], .k), do: .focus(.up))
                Keybind(KeyChord([.option], .l), do: .focus(.right))
                Keybind(KeyChord([.command, .option], .space), do: .cycleEngine)
            }
        }
    }

    private func textView(_ text: String, width: CGFloat = 640) -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: text)
        label.preferredMaxLayoutWidth = width
        return label
    }

    private func codeBlock(_ text: String) -> NSTextField {
        let label = textView(text)
        label.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        label.textColor = .secondaryLabelColor
        return label
    }

    private func verticalStack(spacing: CGFloat) -> NSStackView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = spacing
        return stack
    }

    private func scroll(_ documentView: NSView, height: CGFloat) -> NSView {
        let scrollView = NSScrollView()
        scrollView.documentView = documentView
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder
        scrollView.heightAnchor.constraint(equalToConstant: height).isActive = true
        return scrollView
    }

    private func button(_ title: String, _ action: Selector, enabled: Bool = true) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        button.isEnabled = enabled
        return button
    }

    @objc private func back() {
        guard let previous = FirstRunStep(rawValue: step.rawValue - 1) else { return }
        step = previous
        render()
    }

    @objc private func next() {
        if step == .done {
            try? completeSetup()
            return
        }
        guard step != .accessibility || axStatusProvider() == .trusted,
              let next = FirstRunStep(rawValue: step.rawValue + 1) else {
            refreshAXStatus()
            return
        }
        step = next
        render()
    }

    @objc private func profileChanged() {
        guard let rawValue = profilePopUp.selectedItem?.representedObject as? String,
              let profile = try? ConfigTemplateProfile(name: rawValue) else { return }
        selectedProfile = profile
        render()
    }

    @objc private func openSettings() {
        AXPermission.openAccessibilitySettings()
    }

    @objc private func copyDeepLink() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(AXPermission.accessibilitySettingsDeepLink, forType: .string)
    }

    @objc private func refreshAXStatus() {
        updateAXStatusText()
        if step == .accessibility {
            render()
        }
    }

    private func updateAXStatusText() {
        let trusted = axStatusProvider() == .trusted
        axStatusText = trusted
            ? L10n.s("Accessibility permission granted.", "first-run AX granted status")
            : L10n.s("Waiting for permission...", "first-run AX waiting status")
    }
}

private final class FirstRunStepViewController: NSViewController {
    private let content: NSView

    init(view: NSView) {
        self.content = view
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func loadView() {
        view = content
    }
}
