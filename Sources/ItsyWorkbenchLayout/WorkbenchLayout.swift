import Foundation

public enum WorkbenchComponentID: String, CaseIterable, Codable, Equatable, Hashable, Sendable {
	case fileTree = "file_tree"
	case editor
	case terminal
	case git
	case debugger
	case problems
	case outline
	case references
	case tasks
	case undoTree = "undo_tree"
	case tabBar = "tab_bar"
	case statusBar = "status_bar"
}

public enum WorkbenchProfile: String, CaseIterable, Codable, Equatable, Sendable {
	case workbench
	case focus
	case review
}

public enum WorkbenchVisibility: String, CaseIterable, Codable, Equatable, Sendable {
	case automatic
	case visible
	case hidden
}

public enum WorkbenchGitLayoutMode: String, Codable, Equatable, Sendable {
	case full
	case compact
	case files
	case diff
}

public enum WorkbenchComponentPlacement: String, CaseIterable, Codable, Equatable, Sendable {
	case sidebar
	case editor
	case bottomPanel
	case secondarySidebar
	case editorChrome
	case utilityPanel
}

public struct WorkbenchComponentDescriptor: Equatable, Sendable {
	public let id: WorkbenchComponentID
	public let displayName: String
	public let placement: WorkbenchComponentPlacement
	public let defaultLifecycle: WorkbenchComponentLifecycle
	public let minimumWidth: CGFloat
	public let minimumHeight: CGFloat
	public let collapsesBefore: [WorkbenchComponentID]

	public init(
		id: WorkbenchComponentID,
		displayName: String? = nil,
		placement: WorkbenchComponentPlacement = .editor,
		defaultLifecycle: WorkbenchComponentLifecycle = .hidden,
		minimumWidth: CGFloat,
		minimumHeight: CGFloat = 0,
		collapsesBefore: [WorkbenchComponentID] = []
	) {
		self.id = id
		self.displayName = displayName ?? id.rawValue
		self.placement = placement
		self.defaultLifecycle = defaultLifecycle
		self.minimumWidth = minimumWidth
		self.minimumHeight = minimumHeight
		self.collapsesBefore = collapsesBefore
	}
}

public enum WorkbenchComponentRegistryError: Error, Equatable, Sendable {
	case duplicateComponent(WorkbenchComponentID)
}

public struct WorkbenchComponentRegistry: Sendable {
	public let descriptors: [WorkbenchComponentDescriptor]
	public let descriptorsByID: [WorkbenchComponentID: WorkbenchComponentDescriptor]

	public init(descriptors: [WorkbenchComponentDescriptor]) throws {
		var descriptorsByID: [WorkbenchComponentID: WorkbenchComponentDescriptor] = [:]
		for descriptor in descriptors {
			guard descriptorsByID[descriptor.id] == nil else {
				throw WorkbenchComponentRegistryError.duplicateComponent(descriptor.id)
			}
			descriptorsByID[descriptor.id] = descriptor
		}
		self.descriptors = descriptors
		self.descriptorsByID = descriptorsByID
	}

	public func descriptor(for id: WorkbenchComponentID) -> WorkbenchComponentDescriptor? {
		descriptorsByID[id]
	}
}

public enum WorkbenchComponents {
	public static let firstParty = try! WorkbenchComponentRegistry(descriptors: [
		.init(id: .fileTree, displayName: "File Tree", placement: .sidebar, defaultLifecycle: .visible, minimumWidth: 160, collapsesBefore: [.editor]),
		.init(id: .editor, displayName: "Editor", placement: .editor, defaultLifecycle: .visible, minimumWidth: 480, minimumHeight: 220),
		.init(id: .terminal, displayName: "Terminal", placement: .bottomPanel, minimumWidth: 320, minimumHeight: 140),
		.init(id: .git, displayName: "Git", placement: .secondarySidebar, minimumWidth: 320, collapsesBefore: [.editor]),
		.init(id: .debugger, displayName: "Debugger", placement: .secondarySidebar, minimumWidth: 320, collapsesBefore: [.editor]),
		.init(id: .problems, displayName: "Problems", placement: .utilityPanel, minimumWidth: 560, minimumHeight: 300),
		.init(id: .outline, displayName: "Outline", placement: .utilityPanel, minimumWidth: 320, minimumHeight: 360),
		.init(id: .references, displayName: "References", placement: .utilityPanel, minimumWidth: 560, minimumHeight: 300),
		.init(id: .tasks, displayName: "Tasks", placement: .utilityPanel, minimumWidth: 560, minimumHeight: 360),
		.init(id: .undoTree, displayName: "Undo Tree", placement: .utilityPanel, minimumWidth: 360, minimumHeight: 240),
		.init(id: .tabBar, displayName: "Tabs", placement: .editorChrome, defaultLifecycle: .visible, minimumWidth: 180),
		.init(id: .statusBar, displayName: "Status Bar", placement: .editorChrome, defaultLifecycle: .visible, minimumWidth: 180),
	])
	public static let registry = firstParty.descriptorsByID
}

public struct WorkbenchLayoutConfiguration: Codable, Equatable, Sendable {
	public var profile: WorkbenchProfile
	public var fileTree: WorkbenchVisibility
	public var terminal: WorkbenchVisibility
	public var git: WorkbenchVisibility

	public init(
		profile: WorkbenchProfile = .workbench,
		fileTree: WorkbenchVisibility = .automatic,
		terminal: WorkbenchVisibility = .automatic,
		git: WorkbenchVisibility = .automatic
	) {
		self.profile = profile
		self.fileTree = fileTree
		self.terminal = terminal
		self.git = git
	}
}

public struct WorkbenchLayoutInput: Equatable, Sendable {
	public var width: CGFloat
	public var height: CGFloat
	public var interfaceScale: CGFloat
	public var configuration: WorkbenchLayoutConfiguration
	public var sidebarRequested: Bool
	public var terminalVisible: Bool
	public var gitVisible: Bool
	public var preferredSidebarWidth: CGFloat
	public var previousGitMode: WorkbenchGitLayoutMode?

	public init(
		width: CGFloat,
		height: CGFloat,
		interfaceScale: CGFloat = 1,
		configuration: WorkbenchLayoutConfiguration = .init(),
		sidebarRequested: Bool,
		terminalVisible: Bool,
		gitVisible: Bool,
		preferredSidebarWidth: CGFloat,
		previousGitMode: WorkbenchGitLayoutMode? = nil
	) {
		self.width = width
		self.height = height
		self.interfaceScale = interfaceScale
		self.configuration = configuration
		self.sidebarRequested = sidebarRequested
		self.terminalVisible = terminalVisible
		self.gitVisible = gitVisible
		self.preferredSidebarWidth = preferredSidebarWidth
		self.previousGitMode = previousGitMode
	}
}

public struct WorkbenchLayoutResult: Equatable, Sendable {
	public var showsFileTree: Bool
	public var showsTerminal: Bool
	public var showsGit: Bool
	public var sidebarWidth: CGFloat
	public var gitWidth: CGFloat
	public var terminalHeight: CGFloat
	public var gitMode: WorkbenchGitLayoutMode

	public init(
		showsFileTree: Bool,
		showsTerminal: Bool,
		showsGit: Bool,
		sidebarWidth: CGFloat,
		gitWidth: CGFloat,
		terminalHeight: CGFloat,
		gitMode: WorkbenchGitLayoutMode
	) {
		self.showsFileTree = showsFileTree
		self.showsTerminal = showsTerminal
		self.showsGit = showsGit
		self.sidebarWidth = sidebarWidth
		self.gitWidth = gitWidth
		self.terminalHeight = terminalHeight
		self.gitMode = gitMode
	}
}

public enum WorkbenchLayoutSolver {
	public static let compactHysteresis: CGFloat = 24

	public static func resolve(_ input: WorkbenchLayoutInput) -> WorkbenchLayoutResult {
		let scale = max(input.interfaceScale, 0.8)
		let editorMinimum = WorkbenchComponents.registry[.editor]!.minimumWidth * scale
		let sidebarMinimum = WorkbenchComponents.registry[.fileTree]!.minimumWidth * scale
		let sidebarPreferred = max(sidebarMinimum, input.preferredSidebarWidth * scale)
		let terminalMinimum = WorkbenchComponents.registry[.terminal]!.minimumHeight * scale
		let wantsFileTree = resolvedVisibility(input.configuration.fileTree, default: input.sidebarRequested)
		let wantsTerminal = resolvedVisibility(input.configuration.terminal, default: input.terminalVisible)
		let wantsGit = resolvedVisibility(input.configuration.git, default: input.gitVisible)
		let profileAllowsFileTree = input.configuration.profile != .focus && input.configuration.profile != .review
		let fileTreeFits = input.width >= editorMinimum + sidebarMinimum
		let showsFileTree = wantsFileTree && profileAllowsFileTree && fileTreeFits
		let sidebarWidth = showsFileTree ? min(sidebarPreferred, max(sidebarMinimum, input.width * 0.30)) : 0
		let availableForGit = max(0, input.width - sidebarWidth - editorMinimum)
		let gitMode = gitMode(
			availableWidth: availableForGit,
			previous: input.previousGitMode,
			isVisible: wantsGit
		)
		let showsGit = wantsGit && availableForGit >= WorkbenchComponents.registry[.git]!.minimumWidth * scale
		let gitWidth: CGFloat = switch gitMode {
		case .full: min(max(640 * scale, availableForGit * 0.46), availableForGit)
		case .compact: min(max(440 * scale, availableForGit * 0.42), availableForGit)
		case .files, .diff: min(max(320 * scale, availableForGit * 0.38), availableForGit)
		}
		let terminalHeight = wantsTerminal ? max(terminalMinimum, min(280 * scale, input.height * 0.42)) : 0
		return WorkbenchLayoutResult(
			showsFileTree: showsFileTree,
			showsTerminal: wantsTerminal,
			showsGit: showsGit,
			sidebarWidth: sidebarWidth,
			gitWidth: gitWidth,
			terminalHeight: terminalHeight,
			gitMode: gitMode
		)
	}

	private static func resolvedVisibility(_ visibility: WorkbenchVisibility, default defaultValue: Bool) -> Bool {
		switch visibility {
		case .automatic: defaultValue
		case .visible: true
		case .hidden: false
		}
	}

	private static func gitMode(
		availableWidth: CGFloat,
		previous: WorkbenchGitLayoutMode?,
		isVisible: Bool
	) -> WorkbenchGitLayoutMode {
		guard isVisible else { return .full }
		if let previous, previous == .full, availableWidth >= 720 - compactHysteresis {
			return .full
		}
		if let previous, previous == .compact, availableWidth >= 480 - compactHysteresis, availableWidth < 720 + compactHysteresis {
			return .compact
		}
		if availableWidth >= 720 { return .full }
		if availableWidth >= 480 { return .compact }
		return previous == .diff ? .diff : .files
	}
}
