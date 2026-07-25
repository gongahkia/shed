import AppKit
import Foundation
@testable import ItsyApp
import ItsyConfig
import Testing

@Test @MainActor func terminalCoordinatorManagesTabsPanesFocusSearchCloseAndRestart() throws {
	_ = NSApplication.shared
	let coordinator = TerminalCoordinator(
		settingsProvider: { ItsySettings.TerminalSettings(presentation: .window) },
		activeDocumentProvider: { nil },
		sessionFactory: terminalCoordinatorTestSession
	)
	coordinator.showTerminal(nil)
	let panel = try #require(NSApp.windows.compactMap { $0 as? NSPanel }.first { $0.title == "Terminal" })
	let contentView = try #require(panel.contentView)
	defer {
		coordinator.terminate()
		panel.close()
	}

	#expect(coordinator.state.tabCount == 1)
	#expect(coordinator.state.paneCount == 1)
	let newTab = try #require(terminalButton(in: contentView, identifier: "terminal.new-tab"))
	newTab.performClick(nil)
	#expect(coordinator.state.tabCount == 2)

	let split = try #require(terminalButton(in: contentView, identifier: "terminal.split-horizontal"))
	split.performClick(nil)
	#expect(coordinator.state.paneCount == 2)
	#expect(Set(coordinator.state.processIdentifiers.compactMap { $0 }).count == 2)

	let terminalViews = terminalDescendants(in: contentView).compactMap { $0 as? ItsyTerminalView }
	#expect(terminalViews.count == 2)
	panel.makeFirstResponder(terminalViews[0])
	#expect(coordinator.state.activePaneIndex == 0)
	terminalViews[0].ingest(Data("focus-search-target".utf8))
	let find = try #require(terminalButton(in: contentView, identifier: "terminal.find"))
	find.performClick(nil)
	let searchField = try #require(terminalDescendants(in: contentView).compactMap { $0 as? NSSearchField }.first)
	#expect(searchField.superview?.isHidden == false)
	#expect(terminalViews[0].setSearch(query: "search-target", regex: false) == 1)

	let activePaneIndex = try #require(coordinator.state.activePaneIndex)
	let processBeforeRestart = try #require(coordinator.state.processIdentifiers[activePaneIndex])
	let restart = try #require(terminalButton(in: contentView, identifier: "terminal.restart"))
	restart.performClick(nil)
	#expect(try #require(coordinator.state.processIdentifiers[activePaneIndex]) != processBeforeRestart)

	let closePane = try #require(terminalButton(in: contentView, identifier: "terminal.close-pane"))
	closePane.performClick(nil)
	#expect(coordinator.state.paneCount == 1)
	let closeTab = try #require(terminalButton(in: contentView, identifier: "terminal.close-tab.1"))
	closeTab.performClick(nil)
	#expect(coordinator.state.tabCount == 1)
}

@Test func terminalWorkspaceStatePersistsOnlyRestorablePaneConfiguration() throws {
	let state = TerminalWorkspaceState(
		selectedTabIndex: 0,
		tabs: [
			TerminalTabState(
				currentDirectoryPath: "/tmp/project",
				layout: "H[L,L]",
				paneCurrentDirectoryPaths: ["/tmp/project", "/tmp/project/tests"]
			),
		]
	)
	let data = try JSONEncoder().encode(state)

	#expect(try JSONDecoder().decode(TerminalWorkspaceState.self, from: data) == state)
	#expect(!String(decoding: data, as: UTF8.self).lowercased().contains("pid"))
	#expect(!String(decoding: data, as: UTF8.self).lowercased().contains("session"))
}

@Test @MainActor func terminalCoordinatorEmbedsByDefault() {
	_ = NSApplication.shared
	let host = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 280))
	var visibility: [Bool] = []
	let coordinator = TerminalCoordinator(
		settingsProvider: { ItsySettings.TerminalSettings() },
		activeDocumentProvider: { nil },
		sessionFactory: terminalCoordinatorTestSession,
		embeddedHostProvider: { host },
		setEmbeddedTerminalVisible: { visibility.append($0) }
	)
	defer { coordinator.terminate() }

	coordinator.showTerminal(nil)
	#expect(coordinator.state.tabCount == 1)
	#expect(host.subviews.count == 1)
	#expect(visibility == [true])
	#expect(!NSApp.windows.compactMap { $0 as? NSPanel }.contains { $0.title == "Terminal" && $0.isVisible })

	coordinator.showTerminal(nil)
	#expect(visibility == [true, false])
}

@MainActor private func terminalCoordinatorTestSession(_ directory: URL) -> ItsyTerminalSession {
	var environment = ProcessInfo.processInfo.environment
	environment["SHELL"] = "/bin/sh"
	return ItsyTerminalSession(currentDirectoryURL: directory, environment: environment)
}

private func terminalButton(in view: NSView, identifier: String) -> NSButton? {
	terminalDescendants(in: view).compactMap { $0 as? NSButton }.first { $0.identifier?.rawValue == identifier }
}

private func terminalDescendants(in view: NSView) -> [NSView] {
	view.subviews + view.subviews.flatMap { terminalDescendants(in: $0) }
}
