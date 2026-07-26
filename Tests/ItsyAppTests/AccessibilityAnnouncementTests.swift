@testable import ItsyApp
import AppKit
import ItsyEditor
import Testing

@Test func accessibilityAnnouncementFixturesDescribeAsyncFailureAndRecovery() {
	let failure = AccessibilityAnnouncement.languageServerFailure(language: "Swift")
	let recovery = AccessibilityAnnouncement.languageServerRecovery(language: "Swift")
	let restored = AccessibilityAnnouncement.recoveredEdits(fileName: "note.txt")

	#expect(failure.message == "Language server for Swift failed")
	#expect(failure.priority == .high)
	#expect(recovery.message == "Language server for Swift recovered")
	#expect(recovery.priority == .medium)
	#expect(restored.message == "Recovered unsaved edits for note.txt")
	#expect(restored.priority == .medium)
}

@MainActor
@Test func commandPaletteExposesSearchAndResultsToAccessibility() {
	let coordinator = CommandPaletteCoordinator(
		documentController: ItsyDocumentController(),
		commandRegistryProvider: { CommandRegistry() },
		activeDocumentProvider: { nil }
	)
	let labels = accessibilityLabels(in: coordinator.makeCommandPaletteContentView())

	#expect(labels.contains("Command palette search"))
	#expect(labels.contains("Command palette results"))
}

@MainActor
@Test func tabAndStatusControlsExposeAccessibilityLabels() throws {
	let first = ItsyDocument()
	let second = ItsyDocument()
	let tabBar = EditorPaneTabBarController()
	tabBar.setTabs([
		ItsyTab(id: ObjectIdentifier(first), title: "first.swift", isDirty: false, isSelected: true),
		ItsyTab(id: ObjectIdentifier(second), title: "second.swift", isDirty: true, isSelected: false),
	])
	let tabLabels = accessibilityLabels(in: tabBar.view)
	#expect(tabLabels.contains("Tab: first.swift"))
	#expect(tabLabels.contains("Close tab: second.swift"))

	let controller = EditorWindowController(document: first)
	defer { controller.close() }
	let window = try #require(controller.window)
	#expect(accessibilityLabels(in: window.contentView ?? NSView()).contains("Language server status"))
	let recovery = WorkbenchRecoveryPanel(openSettings: {}, restoreDefaults: {}, generateDoctor: { nil })
	let recoveryLabels = accessibilityLabels(in: recovery.contentViewForTesting(diagnostic: "workbench.profile is invalid"))
	#expect(recoveryLabels.contains("Workbench layout is disabled"))
}

@MainActor
@Test func settingsExposeExplicitWorkbenchComponentOverrides() {
	_ = NSApplication.shared
	let coordinator = SettingsCoordinator(
		documentController: ItsyDocumentController(),
		onSettingsChange: { _ in },
		onTerminalSettingsChange: { _ in }
	)
	let popups = accessibilityDescendants(in: coordinator.settingsContentViewForTesting()).compactMap { $0 as? NSPopUpButton }
	let expected = [
		("workbench.file_tree", "Workbench file tree visibility"),
		("workbench.terminal", "Workbench terminal visibility"),
		("workbench.git", "Workbench Git visibility"),
	]

	for (identifier, label) in expected {
		let popup = popups.first { $0.identifier?.rawValue == identifier }
		#expect(popup?.accessibilityLabel() == label)
		#expect(popup?.itemArray.compactMap { $0.representedObject as? String } == ["automatic", "visible", "hidden"])
	}
	let debuggerPopup = popups.first { $0.identifier?.rawValue == "debugger.presentation" }
	#expect(debuggerPopup?.accessibilityLabel() == "Debugger presentation")
	#expect(debuggerPopup?.itemArray.compactMap { $0.representedObject as? String } == ["sidebar", "window"])
	let terminalPopup = popups.first { $0.identifier?.rawValue == "terminal.presentation" }
	#expect(terminalPopup?.accessibilityLabel() == "Terminal presentation")
	#expect(terminalPopup?.itemArray.compactMap { $0.representedObject as? String } == ["bottom", "window"])
	let gitPopup = popups.first { $0.identifier?.rawValue == "git.presentation" }
	#expect(gitPopup?.accessibilityLabel() == "Git presentation")
	#expect(gitPopup?.itemArray.compactMap { $0.representedObject as? String } == ["sidebar", "window"])
}

private func accessibilityLabels(in view: NSView) -> [String] {
	let label = view.accessibilityLabel().map { [$0] } ?? []
	return label + view.subviews.flatMap(accessibilityLabels)
}

private func accessibilityDescendants(in view: NSView) -> [NSView] {
	view.subviews + view.subviews.flatMap(accessibilityDescendants)
}
