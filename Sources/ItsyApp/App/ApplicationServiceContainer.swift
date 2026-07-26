import AppKit
import Foundation
import ItsyConfig
import ItsyEditor

@MainActor protocol ApplicationServiceHost: AnyObject {
	func makeCommandRegistry(workspaceRoot: URL?) -> CommandRegistry
	func activeDocument() -> NSDocument?
	func activeEditorWindowController() -> EditorWindowController?
	func editorWindowController(gitHost: NSView) -> EditorWindowController?
	func editorWindowController(debuggerHost: NSView) -> EditorWindowController?
	func editorWindowController(terminalHost: NSView) -> EditorWindowController?
	func applySettingsToOpenWindows(_ settings: ItsySettings)
	func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings)
	func openTerminalLocation(_ location: TerminalOpenLocation)
	func openWorkspace(at url: URL) -> Bool
}

@MainActor enum ApplicationServiceContainerError: Error, Equatable {
	case alreadyConnected
	case unavailableHost
}

@MainActor final class ApplicationServiceContainer {
	let documentController: ItsyDocumentController
	private weak var host: (any ApplicationServiceHost)?
	private var didConnect = false

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	func connect(host: any ApplicationServiceHost) throws {
		guard !didConnect else {
			throw ApplicationServiceContainerError.alreadyConnected
		}
		self.host = host
		didConnect = true
	}

	func connectedHost() throws -> any ApplicationServiceHost {
		guard let host else {
			throw ApplicationServiceContainerError.unavailableHost
		}
		return host
	}

	private var resolvedHost: any ApplicationServiceHost {
		do {
			return try connectedHost()
		} catch {
			preconditionFailure("application services require a connected host: \(error)")
		}
	}

	lazy var sparkleUpdateCoordinator = SparkleUpdateCoordinator()
	lazy var commandRegistryStorage = resolvedHost.makeCommandRegistry(workspaceRoot: nil)
	var commandRegistry: CommandRegistry {
		get { commandRegistryStorage }
		set { commandRegistryStorage = newValue }
	}
	lazy var commandPaletteCoordinator = CommandPaletteCoordinator(
		documentController: documentController,
		commandRegistryProvider: { [weak self] in self?.commandRegistry ?? CommandRegistry() },
		activeDocumentProvider: { [weak self] in self?.resolvedHost.activeDocument() },
		workspaceSymbolProvider: { [weak self] query in
			try await self?.resolvedHost.activeEditorWindowController()?.workspaceSymbols(matching: query) ?? []
		},
		fileSymbolProvider: { [weak self] in
			try await self?.resolvedHost.activeEditorWindowController()?.fileSymbolsFromLSP()
		}
	)
	lazy var settingsCoordinator = SettingsCoordinator(
		documentController: documentController,
		onSettingsChange: { [weak self] settings in
			self?.resolvedHost.applySettingsToOpenWindows(settings)
		},
		onTerminalSettingsChange: { [weak self] settings in
			self?.resolvedHost.applyTerminalSettings(settings)
		}
	)
	lazy var workbenchRecoveryPanel = WorkbenchRecoveryPanel(
		openSettings: { [weak self] in self?.settingsCoordinator.openSettingsFile(workspace: false) },
		restoreDefaults: { [weak self] in self?.settingsCoordinator.restoreWorkbenchDefaults() },
		generateDoctor: { [weak self] in self?.settingsCoordinator.generateWorkbenchDoctorFile() }
	)
	lazy var projectFindCoordinator = ProjectFindCoordinator(documentController: documentController)
	lazy var gitCoordinator = GitCoordinator(
		documentController: documentController,
		activeDocumentProvider: { [weak self] in self?.resolvedHost.activeDocument() },
		settingsProvider: { [weak self] in self?.settingsCoordinator.currentSettings.git ?? ItsySettings.GitSettings() },
		embeddedHostProvider: { [weak self] in self?.resolvedHost.activeEditorWindowController()?.embeddedGitHostView },
		setEmbeddedGitVisible: { [weak self] host, visible in
			self?.resolvedHost.editorWindowController(gitHost: host)?.setEmbeddedGitVisible(visible)
		}
	)
	lazy var gitReviewWorkspaceCoordinator = GitReviewWorkspaceCoordinator(
		persistWorkspaceState: { [weak self] in
			ItsyWorkspaceController.persistWindowState(from: self?.resolvedHost.activeEditorWindowController())
		},
		openWorkspace: { [weak self] url in
			self?.resolvedHost.openWorkspace(at: url) ?? false
		}
	)
	lazy var problemsCoordinator = ProblemsCoordinator(documentController: documentController)
	lazy var taskCoordinator = TaskCoordinator(
		problemsCoordinator: problemsCoordinator,
		activeDocumentProvider: { [weak self] in self?.resolvedHost.activeDocument() }
	)
	lazy var debuggerCoordinator = DebuggerCoordinator(
		documentController: documentController,
		settingsProvider: { [weak self] in self?.settingsCoordinator.currentSettings.debugger ?? ItsySettings.DebuggerSettings() },
		embeddedHostProvider: { [weak self] in self?.resolvedHost.activeEditorWindowController()?.embeddedDebuggerHostView },
		setEmbeddedDebuggerVisible: { [weak self] host, visible in
			self?.resolvedHost.editorWindowController(debuggerHost: host)?.setEmbeddedDebuggerVisible(visible)
		}
	)
	lazy var terminalCoordinator = TerminalCoordinator(
		settingsProvider: { [weak self] in self?.settingsCoordinator.currentSettings.terminal ?? ItsySettings.TerminalSettings() },
		activeDocumentProvider: { [weak self] in self?.resolvedHost.activeDocument() },
		openLocation: { [weak self] location in self?.resolvedHost.openTerminalLocation(location) },
		embeddedHostProvider: { [weak self] in self?.resolvedHost.activeEditorWindowController()?.embeddedTerminalHostView },
		setEmbeddedTerminalVisible: { [weak self] host, visible in
			self?.resolvedHost.editorWindowController(terminalHost: host)?.setEmbeddedTerminalVisible(visible)
		},
		editorFontProvider: { [weak self] in
			self?.settingsCoordinator.currentSettings.editor.font ?? ItsySettings.EditorSettings.defaultFont
		}
	)
	lazy var outlineCoordinator = OutlineCoordinator(
		documentController: documentController,
		activeDocumentProvider: { [weak self] in self?.resolvedHost.activeDocument() },
		fileSymbolProvider: { [weak self] in
			try await self?.resolvedHost.activeEditorWindowController()?.fileSymbolsFromLSP()
		}
	)
	lazy var extensionsCoordinator = ExtensionsCoordinator()
}
