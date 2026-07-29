@testable import ItsyApp
import AppKit
import Foundation
import ItsyConfig
import ItsyEditor
import Testing

@MainActor @Test func applicationServiceContainerRejectsUnconnectedServiceAccess() {
	let services = ApplicationServiceContainer(documentController: ItsyDocumentController())
	#expect(throws: ApplicationServiceContainerError.unavailableHost) {
		try services.connectedHost()
	}
}

@MainActor @Test func applicationServiceContainerConnectsOnceAndBuildsRegistryThroughHost() throws {
	let services = ApplicationServiceContainer(documentController: ItsyDocumentController())
	let host = ApplicationServiceHostStub()
	try services.connect(host: host)
	#expect((try services.connectedHost() as AnyObject) === host)
	#expect(services.commandRegistry.allCommands.isEmpty)
	#expect(host.commandRegistryRequests == 1)
	#expect(throws: ApplicationServiceContainerError.alreadyConnected) {
		try services.connect(host: host)
	}
}

@MainActor @Test func applicationServiceContainerRoutesSettingsThroughHostAndTypedEventBus() throws {
	let services = ApplicationServiceContainer(documentController: ItsyDocumentController())
	let host = ApplicationServiceHostStub()
	try services.connect(host: host)
	var publishedSettings: [ItsySettings] = []
	let subscription = services.eventBus.subscribe(ApplicationEvents.SettingsApplied.self) {
		publishedSettings.append($0.settings)
	}
	var settings = ItsySettings()
	settings.editor.tabWidth = 2
	services.applySettings(settings)

	#expect(host.appliedSettings == [settings])
	#expect(publishedSettings == [settings])
	_ = subscription
}

@MainActor private final class ApplicationServiceHostStub: ApplicationServiceHost {
	private(set) var commandRegistryRequests = 0
	private(set) var appliedSettings: [ItsySettings] = []

	func makeCommandRegistry(workspaceRoot _: URL?) -> CommandRegistry {
		commandRegistryRequests += 1
		return CommandRegistry()
	}

	func activeDocument() -> NSDocument? { nil }
	func activeEditorWindowController() -> EditorWindowController? { nil }
	func editorWindowController(gitHost _: NSView) -> EditorWindowController? { nil }
	func editorWindowController(debuggerHost _: NSView) -> EditorWindowController? { nil }
	func editorWindowController(terminalHost _: NSView) -> EditorWindowController? { nil }
	func applySettingsToOpenWindows(_ settings: ItsySettings) { appliedSettings.append(settings) }
	func applyTerminalSettings(_: ItsySettings.TerminalSettings) {}
	func openTerminalLocation(_: TerminalOpenLocation) {}
	func openWorkspace(at _: URL) -> Bool { false }
}
