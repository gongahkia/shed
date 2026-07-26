import Darwin
import Foundation
import ItsyWorkbenchDSL
import ItsyWorkbenchLayout

public enum ItsySettingsCompatibilityPolicy: String, Equatable, Sendable {
	case warnAndIgnoreUnknownFields
}

public enum ItsySettingsSchema {
	public static let currentVersion = 12
	public static let compatibilityPolicy: ItsySettingsCompatibilityPolicy = .warnAndIgnoreUnknownFields
}

public struct ItsySettings: Codable, Equatable, Sendable {
	public enum UIDensity: String, Codable, Equatable, Sendable {
		case compact
		case regular
		case comfortable
	}

	public enum UINotificationPosition: String, Codable, Equatable, Sendable {
		case bottomRight = "bottom_right"
		case topRight = "top_right"
	}

	public struct UISettings: Codable, Equatable, Sendable {
		public struct SurfaceSettings: Codable, Equatable, Sendable {
			public var width: Double?
			public var height: Double?
			public var rowHeight: Double?
			public var inputFontSize: Double?
			public var itemFontSize: Double?

			public init(
				width: Double? = nil,
				height: Double? = nil,
				rowHeight: Double? = nil,
				inputFontSize: Double? = nil,
				itemFontSize: Double? = nil
			) {
				self.width = width
				self.height = height
				self.rowHeight = rowHeight
				self.inputFontSize = inputFontSize
				self.itemFontSize = itemFontSize
			}
		}

		public static let minFontScale = 0.75
		public static let maxFontScale = 2.0
		public static let minCornerRadius = 0.0
		public static let maxCornerRadius = 32.0
		public static let minBorderWidth = 0.0
		public static let maxBorderWidth = 8.0
		public static let minPadding = 0.0
		public static let maxPadding = 48.0
		public static let knownSurfaceIDs = [
			"command_palette", "completion", "find", "project_find", "terminal", "outline", "problems", "references", "tasks",
			"undo_tree",
			"git", "git_graph", "git_stash", "debugger", "debug_console", "debug_variables", "debug_watches", "debug_launch",
			"lsp_status",
			"integration_health", "integration_output", "extensions", "settings_catalog", "lsp_configuration",
			"managed_support",
			"github_pull_request", "github_review_thread",
		]

		public var fontScale: Double
		public var density: UIDensity
		public var cornerRadius: Double
		public var borderWidth: Double
		public var padding: Double
		public var notificationPosition: UINotificationPosition
		public var surfaces: [String: SurfaceSettings]

		public init(
			fontScale: Double = 1,
			density: UIDensity = .regular,
			cornerRadius: Double = 8,
			borderWidth: Double = 1,
			padding: Double = 8,
			notificationPosition: UINotificationPosition = .bottomRight,
			surfaces: [String: SurfaceSettings] = [:]
		) {
			self.fontScale = fontScale
			self.density = density
			self.cornerRadius = cornerRadius
			self.borderWidth = borderWidth
			self.padding = padding
			self.notificationPosition = notificationPosition
			self.surfaces = surfaces
		}

		public func surface(_ id: String) -> SurfaceSettings {
			surfaces[id] ?? SurfaceSettings()
		}
	}

	public enum EditorStorage: String, Codable, Equatable, Sendable {
		case rope
		case pieceTree = "piecetree"
	}

	public enum SyntaxPreloadGrammars: String, Codable, Equatable, Sendable {
		case none
		case opened
		case all
	}

	public enum TabGroupScope: String, Codable, Equatable, Sendable {
		case window
		case pane
	}

	public enum KeymapMode: String, Codable, Equatable, Sendable {
		case plain
		case vim
		case emacs
	}

	public enum CursorStyle: String, Codable, Equatable, Sendable {
		case automatic
		case block
		case bar
	}

	public enum LineNumberMode: String, Codable, Equatable, Sendable {
		case off
		case absolute
		case relative
	}

	public enum WrapMode: String, Codable, Equatable, Sendable {
		case none
		case soft
		case hard
	}

	public enum FontRenderingMode: String, Codable, Equatable, Sendable {
		case grayscale
		case subpixel
	}

	public enum SidebarPosition: String, Codable, Equatable, Sendable {
		case leading
		case trailing
	}

	public struct EditorSettings: Codable, Equatable, Sendable {
		public struct LanguageSettings: Codable, Equatable, Sendable {
			public var font: String?
			public var fontSize: Double?
			public var lineNumbers: Bool?
			public var tabWidth: Int?
			public var useSpaces: Bool?
			public var detectIndentation: Bool?
			public var autoPairs: Bool?
			public var smartIndent: Bool?
			public var multipleSelections: Bool?
			public var fontRendering: FontRenderingMode?

			public init(
				font: String? = nil,
				fontSize: Double? = nil,
				lineNumbers: Bool? = nil,
				tabWidth: Int? = nil,
				useSpaces: Bool? = nil,
				detectIndentation: Bool? = nil,
				autoPairs: Bool? = nil,
				smartIndent: Bool? = nil,
				multipleSelections: Bool? = nil,
				fontRendering: FontRenderingMode? = nil
			) {
				self.font = font
				self.fontSize = fontSize
				self.lineNumbers = lineNumbers
				self.tabWidth = tabWidth
				self.useSpaces = useSpaces
				self.detectIndentation = detectIndentation
				self.autoPairs = autoPairs
				self.smartIndent = smartIndent
				self.multipleSelections = multipleSelections
				self.fontRendering = fontRendering
			}
		}

		public struct ExperimentalSettings: Codable, Equatable, Sendable {
			public var storage: EditorStorage

			public init(storage: EditorStorage = .pieceTree) {
				self.storage = storage
			}
		}

		public static let defaultFont = "Menlo"
		public static let defaultFontSize = 14.95
		public static let minFontSize = 9.0
		public static let maxFontSize = 36.0
		public static let defaultTabWidth = 4
		public static let minTabWidth = 1
		public static let maxTabWidth = 16
		public static let defaultWrapColumn = 100
		public static let minWrapColumn = 20
		public static let maxWrapColumn = 240

		public var font: String
		public var fontSize: Double
		public var fontRendering: FontRenderingMode
		public var lineNumbers: Bool
		public var lineNumberMode: LineNumberMode
		public var tabWidth: Int
		public var useSpaces: Bool
		public var detectIndentation: Bool
		public var autoPairs: Bool
		public var smartIndent: Bool
		public var multipleSelections: Bool
		public var tabGroups: TabGroupScope
		public var keymap: KeymapMode
		public var cursorStyle: CursorStyle
		public var wrap: WrapMode
		public var wrapColumn: Int
		public var language: [String: LanguageSettings]
		public var experimental: ExperimentalSettings

		public init(
			font: String = Self.defaultFont,
			fontSize: Double = Self.defaultFontSize,
			fontRendering: FontRenderingMode = .grayscale,
			lineNumbers: Bool = false,
			lineNumberMode: LineNumberMode? = nil,
			tabWidth: Int = Self.defaultTabWidth,
			useSpaces: Bool = false,
			detectIndentation: Bool = true,
			autoPairs: Bool = true,
			smartIndent: Bool = true,
			multipleSelections: Bool = true,
			tabGroups: TabGroupScope = .window,
			keymap: KeymapMode = .plain,
			cursorStyle: CursorStyle = .automatic,
			wrap: WrapMode = .none,
			wrapColumn: Int = Self.defaultWrapColumn,
			language: [String: LanguageSettings] = [:],
			experimental: ExperimentalSettings = ExperimentalSettings()
		) {
			self.font = font
			self.fontSize = fontSize
			self.fontRendering = fontRendering
			self.lineNumbers = lineNumbers
			self.lineNumberMode = lineNumberMode ?? (lineNumbers ? .absolute : .off)
			self.tabWidth = tabWidth
			self.useSpaces = useSpaces
			self.detectIndentation = detectIndentation
			self.autoPairs = autoPairs
			self.smartIndent = smartIndent
			self.multipleSelections = multipleSelections
			self.tabGroups = tabGroups
			self.keymap = keymap
			self.cursorStyle = cursorStyle
			self.wrap = wrap
			self.wrapColumn = wrapColumn
			self.language = language
			self.experimental = experimental
		}
	}

	public struct ThemeSettings: Codable, Equatable, Sendable {
		public struct GitGutterSettings: Codable, Equatable, Sendable {
			public static let defaultAdded = "#47C775"
			public static let defaultModified = "#F2AD2E"
			public static let defaultRemoved = "#F24038"

			public var added: String
			public var modified: String
			public var removed: String

			public init(
				added: String = Self.defaultAdded,
				modified: String = Self.defaultModified,
				removed: String = Self.defaultRemoved
			) {
				self.added = added
				self.modified = modified
				self.removed = removed
			}
		}

		public static let defaultID = "bundled:default-light"

		public var id: String
		public var gitGutter: GitGutterSettings

		public init(id: String = Self.defaultID, gitGutter: GitGutterSettings = GitGutterSettings()) {
			self.id = id
			self.gitGutter = gitGutter
		}
	}

	public struct SyntaxSettings: Codable, Equatable, Sendable {
		public var preloadGrammars: SyntaxPreloadGrammars

		public init(preloadGrammars: SyntaxPreloadGrammars = .opened) {
			self.preloadGrammars = preloadGrammars
		}
	}

	public struct TerminalSettings: Codable, Equatable, Sendable {
		public enum Presentation: String, Codable, Equatable, Sendable {
			case bottom
			case window
		}

		public static let defaultFontSize = 12.0
		public static let minFontSize = 8.0
		public static let maxFontSize = 36.0
		public static let defaultScrollbackLines = 10000
		public static let minScrollbackLines = 0
		public static let maxScrollbackLines = 1_000_000

		public var font: String?
		public var fontSize: Double
		public var scrollbackLines: Int
		public var presentation: Presentation

		public init(
			fontSize: Double = Self.defaultFontSize,
			scrollbackLines: Int = Self.defaultScrollbackLines,
			presentation: Presentation = .bottom,
			font: String? = nil
		) {
			self.font = font
			self.fontSize = fontSize
			self.scrollbackLines = scrollbackLines
			self.presentation = presentation
		}

		public func resolvedFontName(inheriting editorFont: String) -> String {
			let font = font?.trimmingCharacters(in: .whitespacesAndNewlines)
			if let font, !font.isEmpty {
				return font
			}
			return editorFont
		}
	}

	public struct GitSettings: Codable, Equatable, Sendable {
		public enum Presentation: String, Codable, Equatable, Sendable {
			case sidebar
			case window
		}

		public var presentation: Presentation

		public init(presentation: Presentation = .sidebar) {
			self.presentation = presentation
		}
	}

	public struct DebuggerSettings: Codable, Equatable, Sendable {
		public enum Presentation: String, Codable, Equatable, Sendable {
			case sidebar
			case window
		}

		public var presentation: Presentation

		public init(presentation: Presentation = .sidebar) {
			self.presentation = presentation
		}
	}

	public struct FindSettings: Codable, Equatable, Sendable {
		public var usesRegex: Bool
		public var isCaseSensitive: Bool
		public var matchesWholeWord: Bool

		public init(usesRegex: Bool = false, isCaseSensitive: Bool = false, matchesWholeWord: Bool = false) {
			self.usesRegex = usesRegex
			self.isCaseSensitive = isCaseSensitive
			self.matchesWholeWord = matchesWholeWord
		}
	}

	public struct RecoverySettings: Codable, Equatable, Sendable {
		public var journalEnabled: Bool

		public init(journalEnabled: Bool = true) {
			self.journalEnabled = journalEnabled
		}
	}

	public struct UpdateSettings: Codable, Equatable, Sendable {
		public var automaticallyCheck: Bool

		public init(automaticallyCheck: Bool = false) {
			self.automaticallyCheck = automaticallyCheck
		}
	}

	public enum LSPMode: String, CaseIterable, Codable, Equatable, Sendable {
		case automatic = "auto"
		case system
		case managed
		case disabled
	}

	public struct LSPSettings: Codable, Equatable, Sendable {
		public var catalogAutomaticallyCheck: Bool
		public var modes: [String: LSPMode]

		public init(catalogAutomaticallyCheck: Bool = false, modes: [String: LSPMode] = [:]) {
			self.catalogAutomaticallyCheck = catalogAutomaticallyCheck
			self.modes = modes
		}

		public func mode(for languageID: String) -> LSPMode {
			modes[languageID] ?? modes[languageID.lowercased()] ?? .automatic
		}
	}

	public struct LayoutSettings: Codable, Equatable, Sendable {
		public static let defaultSidebarWidth = 240
		public static let minSidebarWidth = 160
		public static let maxSidebarWidth = 480
		public static let defaultInterfaceScale = 1.0
		public static let minInterfaceScale = 0.8
		public static let maxInterfaceScale = 2.0

		public var sidebarVisible: Bool
		public var sidebarPosition: SidebarPosition
		public var sidebarWidth: Int
		public var tabBarVisible: Bool
		public var statusBarVisible: Bool
		public var interfaceScale: Double

		public init(
			sidebarVisible: Bool = true,
			sidebarPosition: SidebarPosition = .leading,
			sidebarWidth: Int = Self.defaultSidebarWidth,
			tabBarVisible: Bool = true,
			statusBarVisible: Bool = true,
			interfaceScale: Double = Self.defaultInterfaceScale
		) {
			self.sidebarVisible = sidebarVisible
			self.sidebarPosition = sidebarPosition
			self.sidebarWidth = sidebarWidth
			self.tabBarVisible = tabBarVisible
			self.statusBarVisible = statusBarVisible
			self.interfaceScale = interfaceScale
		}
	}

	public static let `default` = ItsySettings()

	public var editor: EditorSettings
	public var theme: ThemeSettings
	public var syntax: SyntaxSettings
	public var terminal: TerminalSettings
	public var git: GitSettings
	public var debugger: DebuggerSettings
	public var find: FindSettings
	public var recovery: RecoverySettings
	public var updates: UpdateSettings
	public var lsp: LSPSettings
	public var workbench: WorkbenchLayoutConfiguration
	public var layout: LayoutSettings
	public var ui: UISettings

	public init(
		editor: EditorSettings = EditorSettings(),
		theme: ThemeSettings = ThemeSettings(),
		syntax: SyntaxSettings = SyntaxSettings(),
		terminal: TerminalSettings = TerminalSettings(),
		git: GitSettings = GitSettings(),
		debugger: DebuggerSettings = DebuggerSettings(),
		find: FindSettings = FindSettings(),
		recovery: RecoverySettings = RecoverySettings(),
		updates: UpdateSettings = UpdateSettings(),
		lsp: LSPSettings = LSPSettings(),
		workbench: WorkbenchLayoutConfiguration = WorkbenchProfileBuilder.workbench(),
		layout: LayoutSettings = LayoutSettings(),
		ui: UISettings = UISettings()
	) {
		self.editor = editor
		self.theme = theme
		self.syntax = syntax
		self.terminal = terminal
		self.git = git
		self.debugger = debugger
		self.find = find
		self.recovery = recovery
		self.updates = updates
		self.lsp = lsp
		self.workbench = workbench
		self.layout = layout
		self.ui = ui
	}

	public func normalized() -> ItsySettings {
		var copy = self
		if copy.editor.font.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.editor.font = EditorSettings.defaultFont
		}
		copy.editor.fontSize = Self.clamp(
			copy.editor.fontSize,
			min: EditorSettings.minFontSize,
			max: EditorSettings.maxFontSize
		)
		copy.editor.tabWidth = Self.clamp(
			copy.editor.tabWidth,
			min: EditorSettings.minTabWidth,
			max: EditorSettings.maxTabWidth
		)
		copy.editor.lineNumbers = copy.editor.lineNumberMode != .off
		copy.editor.wrapColumn = Self.clamp(
			copy.editor.wrapColumn,
			min: EditorSettings.minWrapColumn,
			max: EditorSettings.maxWrapColumn
		)
		copy.editor.language = copy.editor.language.reduce(into: [:]) { result, entry in
			let key = entry.key.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !key.isEmpty else {
				return
			}
			var value = entry.value
			if value.font?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
				value.font = nil
			}
			if let fontSize = value.fontSize {
				value.fontSize = Self.clamp(fontSize, min: EditorSettings.minFontSize, max: EditorSettings.maxFontSize)
			}
			if let tabWidth = value.tabWidth {
				value.tabWidth = Self.clamp(tabWidth, min: EditorSettings.minTabWidth, max: EditorSettings.maxTabWidth)
			}
			result[key] = value
		}
		if copy.theme.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.id = ThemeSettings.defaultID
		}
		if copy.theme.gitGutter.added.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.gitGutter.added = ThemeSettings.GitGutterSettings.defaultAdded
		}
		if copy.theme.gitGutter.modified.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.gitGutter.modified = ThemeSettings.GitGutterSettings.defaultModified
		}
		if copy.theme.gitGutter.removed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			copy.theme.gitGutter.removed = ThemeSettings.GitGutterSettings.defaultRemoved
		}
		if copy.terminal.font?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
			copy.terminal.font = nil
		}
		copy.lsp.modes = copy.lsp.modes.reduce(into: [:]) { result, entry in
			let languageID = entry.key.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
			guard !languageID.isEmpty else { return }
			result[languageID] = entry.value
		}
		copy.terminal.fontSize = Self.clamp(
			copy.terminal.fontSize,
			min: TerminalSettings.minFontSize,
			max: TerminalSettings.maxFontSize
		)
		copy.terminal.scrollbackLines = Self.clamp(
			copy.terminal.scrollbackLines,
			min: TerminalSettings.minScrollbackLines,
			max: TerminalSettings.maxScrollbackLines
		)
		copy.layout.sidebarWidth = Self.clamp(
			copy.layout.sidebarWidth,
			min: LayoutSettings.minSidebarWidth,
			max: LayoutSettings.maxSidebarWidth
		)
		copy.layout.interfaceScale = Self.clamp(
			copy.layout.interfaceScale,
			min: LayoutSettings.minInterfaceScale,
			max: LayoutSettings.maxInterfaceScale
		)
		copy.ui.fontScale = Self.clamp(copy.ui.fontScale, min: UISettings.minFontScale, max: UISettings.maxFontScale)
		copy.ui.cornerRadius = Self.clamp(
			copy.ui.cornerRadius,
			min: UISettings.minCornerRadius,
			max: UISettings.maxCornerRadius
		)
		copy.ui.borderWidth = Self.clamp(copy.ui.borderWidth, min: UISettings.minBorderWidth, max: UISettings.maxBorderWidth)
		copy.ui.padding = Self.clamp(copy.ui.padding, min: UISettings.minPadding, max: UISettings.maxPadding)
		copy.ui.surfaces = copy.ui.surfaces.reduce(into: [:]) { result, entry in
			let id = entry.key.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !id.isEmpty else { return }
			var value = entry.value
			value.width = value.width.map { Self.clamp($0, min: 200, max: 2400) }
			value.height = value.height.map { Self.clamp($0, min: 120, max: 1800) }
			value.rowHeight = value.rowHeight.map { Self.clamp($0, min: 16, max: 80) }
			value.inputFontSize = value.inputFontSize.map { Self.clamp($0, min: 9, max: 48) }
			value.itemFontSize = value.itemFontSize.map { Self.clamp($0, min: 9, max: 48) }
			result[id] = value
		}
		return copy
	}

	public func editorSettings(languageID: String?) -> EditorSettings {
		var editor = normalized().editor
		guard let languageID = languageID?.trimmingCharacters(in: .whitespacesAndNewlines), !languageID.isEmpty else {
			return editor
		}
		guard let override = editor.language[languageID] ?? editor.language[languageID.lowercased()] else {
			return editor
		}
		editor.font = override.font ?? editor.font
		editor.fontSize = override.fontSize ?? editor.fontSize
		if let lineNumbers = override.lineNumbers {
			editor.lineNumbers = lineNumbers
			editor.lineNumberMode = lineNumbers ? (editor.lineNumberMode == .off ? .absolute : editor.lineNumberMode) : .off
		}
		editor.tabWidth = override.tabWidth ?? editor.tabWidth
		editor.useSpaces = override.useSpaces ?? editor.useSpaces
		editor.detectIndentation = override.detectIndentation ?? editor.detectIndentation
		editor.autoPairs = override.autoPairs ?? editor.autoPairs
		editor.smartIndent = override.smartIndent ?? editor.smartIndent
		editor.multipleSelections = override.multipleSelections ?? editor.multipleSelections
		editor.fontRendering = override.fontRendering ?? editor.fontRendering
		return ItsySettings(editor: editor).normalized().editor
	}

	private static func clamp(_ value: Double, min: Double, max: Double) -> Double {
		Swift.min(Swift.max(value, min), max)
	}

	private static func clamp(_ value: Int, min: Int, max: Int) -> Int {
		Swift.min(Swift.max(value, min), max)
	}
}

public struct ItsySettingsWarning: Equatable, Sendable, CustomStringConvertible {
	public var line: Int?
	public var key: String?
	public var source: String?
	public var expected: String?
	public var retainedFallback: Bool
	public var message: String

	public init(
		line: Int? = nil,
		key: String? = nil,
		source: String? = nil,
		expected: String? = nil,
		retainedFallback: Bool = false,
		message: String
	) {
		self.line = line
		self.key = key
		self.source = source
		self.expected = expected
		self.retainedFallback = retainedFallback
		self.message = message
	}

	public var description: String {
		if let line {
			return "line \(line): \(message)"
		}
		return message
	}
}

public struct ItsySettingsLoadResult: Equatable, Sendable {
	public var settings: ItsySettings
	public var warnings: [ItsySettingsWarning]
	public var loadedFromFile: Bool
	public var assignedKeys: Set<String>

	public init(
		settings: ItsySettings,
		warnings: [ItsySettingsWarning] = [],
		loadedFromFile: Bool = false,
		assignedKeys: Set<String> = []
	) {
		self.settings = settings
		self.warnings = warnings
		self.loadedFromFile = loadedFromFile
		self.assignedKeys = assignedKeys
	}
}

public enum ItsySettingsScope: String, Equatable, Sendable {
	case `default`
	case global
	case workspace
	case language
	case session
}

public struct ItsySettingsSessionLayer: Equatable, Sendable {
	public let settings: ItsySettings
	public let assignedKeys: Set<String>

	public init(settings: ItsySettings, assignedKeys: Set<String>) {
		self.settings = settings
		self.assignedKeys = assignedKeys
	}
}

public struct ItsySettingsResolution: Equatable, Sendable {
	public let settings: ItsySettings
	public let sources: [String: ItsySettingsScope]

	public init(settings: ItsySettings, sources: [String: ItsySettingsScope]) {
		self.settings = settings
		self.sources = sources
	}

	public func source(for key: String, languageID: String? = nil) -> ItsySettingsScope? {
		if let languageID, key.hasPrefix("editor."),
		   sources["editor.language.\(languageID).\(key.dropFirst("editor.".count))"] != nil
		{
			return .language
		}
		return sources[key]
	}
}

public final class ItsySettingsStore {
	public let fileURL: URL
	private let fileManager: FileManager

	public convenience init(fileManager: FileManager = .default) {
		self.init(fileURL: Self.globalFileURL(fileManager: fileManager), fileManager: fileManager)
	}

	public init(fileURL: URL, fileManager: FileManager = .default) {
		self.fileURL = fileURL
		self.fileManager = fileManager
	}

	public static func globalFileURL(fileManager: FileManager = .default) -> URL {
		fileManager.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("settings.json")
	}

	public static func defaultFileURL(fileManager: FileManager = .default) -> URL {
		globalFileURL(fileManager: fileManager)
	}

	public static func workspaceFileURL(workspaceRoot: URL) -> URL {
		workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("settings.json")
	}

	public func load(fallback: ItsySettings = .default) -> ItsySettingsLoadResult {
		guard fileManager.fileExists(atPath: fileURL.path) else {
			return ItsySettingsLoadResult(settings: fallback.normalized(), loadedFromFile: false)
		}
		return loadJSON(fallback: fallback)
	}

	public func load(workspaceRoot: URL?, fallback: ItsySettings = .default) -> ItsySettingsLoadResult {
		let global = load(fallback: fallback)
		guard let workspaceRoot else {
			return global
		}
		let workspaceStore = ItsySettingsStore(
			fileURL: Self.workspaceFileURL(workspaceRoot: workspaceRoot),
			fileManager: fileManager
		)
		var workspace = workspaceStore.load(fallback: global.settings)
		let workspacePersonalKeys = workspace.assignedKeys.filter {
			$0.hasPrefix("ui.") || $0.hasPrefix("updates.") || $0.hasPrefix("lsp.")
		}
		if !workspacePersonalKeys.isEmpty {
			workspace.settings.ui = global.settings.ui
			workspace.settings.updates = global.settings.updates
			workspace.settings.lsp = global.settings.lsp
			workspace.assignedKeys.subtract(workspacePersonalKeys)
			workspace.warnings += workspacePersonalKeys.sorted().map {
				ItsySettingsWarning(
					key: $0,
					source: workspaceStore.fileURL.path,
					retainedFallback: true,
					message: "\($0) is user-only and is ignored in workspace settings"
				)
			}
		}
		return ItsySettingsLoadResult(
			settings: workspace.settings,
			warnings: global.warnings + workspace.warnings,
			loadedFromFile: global.loadedFromFile || workspace.loadedFromFile,
			assignedKeys: global.assignedKeys.union(workspace.assignedKeys)
		)
	}

	public func resolve(
		workspaceRoot: URL? = nil,
		fallback: ItsySettings = .default,
		session: ItsySettingsSessionLayer? = nil
	) -> ItsySettingsResolution {
		let global = load(fallback: fallback)
		let workspace: ItsySettingsLoadResult?
		if let workspaceRoot {
			let workspaceStore = ItsySettingsStore(
				fileURL: Self.workspaceFileURL(workspaceRoot: workspaceRoot),
				fileManager: fileManager
			)
			var loadedWorkspace = workspaceStore.load(fallback: global.settings)
			let workspacePersonalKeys = loadedWorkspace.assignedKeys.filter {
				$0.hasPrefix("ui.") || $0.hasPrefix("updates.") || $0.hasPrefix("lsp.")
			}
			if !workspacePersonalKeys.isEmpty {
				loadedWorkspace.settings.ui = global.settings.ui
				loadedWorkspace.settings.updates = global.settings.updates
				loadedWorkspace.settings.lsp = global.settings.lsp
				loadedWorkspace.assignedKeys.subtract(workspacePersonalKeys)
			}
			workspace = loadedWorkspace
		} else {
			workspace = nil
		}
		return ItsySettingsResolver.resolve(
			defaults: fallback.normalized(),
			global: global,
			workspace: workspace,
			session: session
		)
	}

	public func save(_ settings: ItsySettings) throws {
		let directory = fileURL.deletingLastPathComponent()
		try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
		try ItsySettingsJSONCodec.encode(settings).write(to: fileURL, options: .atomic)
	}

	private func loadJSON(fallback: ItsySettings) -> ItsySettingsLoadResult {
		do {
			let layer = try ItsySettingsJSONCodec.decodeLayer(
				Data(contentsOf: fileURL),
				fallback: fallback
			)
			return ItsySettingsLoadResult(
				settings: layer.settings,
				loadedFromFile: true,
				assignedKeys: layer.assignedKeys
			)
		} catch {
			return ItsySettingsLoadResult(
				settings: fallback.normalized(),
				warnings: [jsonWarning(for: error)],
				loadedFromFile: true
			)
		}
	}

	private func jsonWarning(for error: Error) -> ItsySettingsWarning {
		let diagnostic: (key: String?, expected: String?, message: String)
		switch error {
		case let .unsupportedSchemaVersion(version) as ItsySettingsJSONCodecError:
			diagnostic = (
				"schema_version",
				"version == \(ItsySettingsSchema.currentVersion)",
				"unsupported schema_version \(version)"
			)
		case .invalidSettings as ItsySettingsJSONCodecError:
			diagnostic = (
				"settings",
				"values within supported ranges",
				"settings contain unsupported values"
			)
		default:
			diagnostic = (nil, "valid JSON settings document", "invalid JSON settings document: \(error)")
		}
		return ItsySettingsWarning(
			key: diagnostic.key,
			source: fileURL.path,
			expected: diagnostic.expected,
			retainedFallback: true,
			message: diagnostic.message
		)
	}

}

public extension Notification.Name {
	static let itsySettingsChanged = Notification.Name("dev.itsy.settings.changed")
}

public enum ItsySettingsResolver {
	private static let defaultKeys = Set(ItsySettingsCatalog.baseEntries.map(\.key))

	public static func resolve(
		defaults: ItsySettings = .default,
		global: ItsySettingsLoadResult? = nil,
		workspace: ItsySettingsLoadResult? = nil,
		session: ItsySettingsSessionLayer? = nil
	) -> ItsySettingsResolution {
		let globalSettings = global?.settings ?? defaults
		var settings = workspace?.settings ?? globalSettings
		var sources = Dictionary(uniqueKeysWithValues: defaultKeys.map { ($0, ItsySettingsScope.default) })
		global?.assignedKeys.forEach { sources[$0] = .global }
		workspace?.assignedKeys.forEach { sources[$0] = .workspace }
		if let session {
			for key in session.assignedKeys {
				apply(key: key, from: session.settings, to: &settings)
				sources[key] = .session
			}
		}
		return ItsySettingsResolution(settings: settings.normalized(), sources: sources)
	}

	private static func apply(key: String, from source: ItsySettings, to target: inout ItsySettings) {
		switch key {
		case "editor.font": target.editor.font = source.editor.font
		case "editor.font_size": target.editor.fontSize = source.editor.fontSize
		case "editor.font_rendering": target.editor.fontRendering = source.editor.fontRendering
		case "editor.line_numbers": target.editor.lineNumbers = source.editor.lineNumbers
		case "editor.line_number_mode": target.editor.lineNumberMode = source.editor.lineNumberMode
		case "editor.tab_width": target.editor.tabWidth = source.editor.tabWidth
		case "editor.use_spaces": target.editor.useSpaces = source.editor.useSpaces
		case "editor.detect_indentation": target.editor.detectIndentation = source.editor.detectIndentation
		case "editor.auto_pairs": target.editor.autoPairs = source.editor.autoPairs
		case "editor.smart_indent": target.editor.smartIndent = source.editor.smartIndent
		case "editor.multiple_selections": target.editor.multipleSelections = source.editor.multipleSelections
		case "editor.keymap": target.editor.keymap = source.editor.keymap
		case "editor.cursor_style": target.editor.cursorStyle = source.editor.cursorStyle
		case "editor.tab_groups": target.editor.tabGroups = source.editor.tabGroups
		case "editor.wrap": target.editor.wrap = source.editor.wrap
		case "editor.wrap_column": target.editor.wrapColumn = source.editor.wrapColumn
		case "editor.experimental.storage": target.editor.experimental.storage = source.editor.experimental.storage
		case "theme.id": target.theme.id = source.theme.id
		case "theme.git.gutter.added": target.theme.gitGutter.added = source.theme.gitGutter.added
		case "theme.git.gutter.modified": target.theme.gitGutter.modified = source.theme.gitGutter.modified
		case "theme.git.gutter.removed": target.theme.gitGutter.removed = source.theme.gitGutter.removed
		case "syntax.preload_grammars": target.syntax.preloadGrammars = source.syntax.preloadGrammars
		case "terminal.font": target.terminal.font = source.terminal.font
		case "terminal.font_size": target.terminal.fontSize = source.terminal.fontSize
		case "terminal.scrollback_lines": target.terminal.scrollbackLines = source.terminal.scrollbackLines
		case "terminal.presentation": target.terminal.presentation = source.terminal.presentation
		case "git.presentation": target.git.presentation = source.git.presentation
		case "debugger.presentation": target.debugger.presentation = source.debugger.presentation
		case "find.uses_regex": target.find.usesRegex = source.find.usesRegex
		case "find.case_sensitive": target.find.isCaseSensitive = source.find.isCaseSensitive
		case "find.whole_word": target.find.matchesWholeWord = source.find.matchesWholeWord
		case "recovery.journal_enabled": target.recovery.journalEnabled = source.recovery.journalEnabled
		case "updates.automatically_check": target.updates.automaticallyCheck = source.updates.automaticallyCheck
		case "lsp.catalog_automatically_check": target.lsp.catalogAutomaticallyCheck = source.lsp.catalogAutomaticallyCheck
		case "workbench.profile": target.workbench.profile = source.workbench.profile
		case "workbench.file_tree": target.workbench.fileTree = source.workbench.fileTree
		case "workbench.terminal": target.workbench.terminal = source.workbench.terminal
		case "workbench.git": target.workbench.git = source.workbench.git
		case "layout.sidebar_visible": target.layout.sidebarVisible = source.layout.sidebarVisible
		case "layout.sidebar_position": target.layout.sidebarPosition = source.layout.sidebarPosition
		case "layout.sidebar_width": target.layout.sidebarWidth = source.layout.sidebarWidth
		case "layout.tab_bar_visible": target.layout.tabBarVisible = source.layout.tabBarVisible
		case "layout.status_bar_visible": target.layout.statusBarVisible = source.layout.statusBarVisible
		case "layout.interface_scale": target.layout.interfaceScale = source.layout.interfaceScale
		case "ui.font_scale": target.ui.fontScale = source.ui.fontScale
		case "ui.density": target.ui.density = source.ui.density
		case "ui.corner_radius": target.ui.cornerRadius = source.ui.cornerRadius
		case "ui.border_width": target.ui.borderWidth = source.ui.borderWidth
		case "ui.padding": target.ui.padding = source.ui.padding
		case "ui.notification_position": target.ui.notificationPosition = source.ui.notificationPosition
		default:
			if !applyLSP(key: key, from: source, to: &target) {
				applyLanguage(key: key, from: source, to: &target)
			}
		}
	}

	private static func applyLSP(key: String, from source: ItsySettings, to target: inout ItsySettings) -> Bool {
		let prefix = "lsp."
		guard key.hasPrefix(prefix), key != "lsp.catalog_automatically_check" else { return false }
		let suffix = key.dropFirst(prefix.count)
		guard let dot = suffix.firstIndex(of: "."), String(suffix[suffix.index(after: dot)...]) == "mode" else {
			return false
		}
		let languageID = String(suffix[..<dot]).lowercased()
		guard let mode = source.lsp.modes[languageID] else { return false }
		target.lsp.modes[languageID] = mode
		return true
	}

	private static func applyLanguage(key: String, from source: ItsySettings, to target: inout ItsySettings) {
		let prefix = "editor.language."
		guard key.hasPrefix(prefix) else { return }
		let suffix = key.dropFirst(prefix.count)
		guard let dot = suffix.firstIndex(of: ".") else { return }
		let languageID = String(suffix[..<dot])
		let property = String(suffix[suffix.index(after: dot)...])
		guard let sourceLanguage = source.editor.language[languageID] else { return }
		var targetLanguage = target.editor.language[languageID] ?? ItsySettings.EditorSettings.LanguageSettings()
		switch property {
		case "font": targetLanguage.font = sourceLanguage.font
		case "font_size": targetLanguage.fontSize = sourceLanguage.fontSize
		case "font_rendering": targetLanguage.fontRendering = sourceLanguage.fontRendering
		case "line_numbers": targetLanguage.lineNumbers = sourceLanguage.lineNumbers
		case "tab_width": targetLanguage.tabWidth = sourceLanguage.tabWidth
		case "use_spaces": targetLanguage.useSpaces = sourceLanguage.useSpaces
		case "detect_indentation": targetLanguage.detectIndentation = sourceLanguage.detectIndentation
		case "auto_pairs": targetLanguage.autoPairs = sourceLanguage.autoPairs
		case "smart_indent": targetLanguage.smartIndent = sourceLanguage.smartIndent
		case "multiple_selections": targetLanguage.multipleSelections = sourceLanguage.multipleSelections
		default: return
		}
		target.editor.language[languageID] = targetLanguage
	}
}

public enum ItsySettingsNotificationUserInfoKey {
	public static let settings = "settings"
	public static let statusMessage = "statusMessage"
	public static let statusIsError = "statusIsError"
}

public final class ItsySettingsWatcher: @unchecked Sendable {
	public typealias Handler = () -> Void

	private let urls: [URL]
	private let queue: DispatchQueue
	private let debounce: TimeInterval
	private let handler: Handler
	private var sources: [DispatchSourceFileSystemObject] = []
	private var fileDescriptors: [Int32] = []
	private var scheduled = false

	public init(
		urls: [URL],
		queue: DispatchQueue = DispatchQueue(label: "dev.itsy.settings-watcher"),
		debounce: TimeInterval = 0.12,
		handler: @escaping Handler
	) {
		self.urls = urls
		self.queue = queue
		self.debounce = debounce
		self.handler = handler
	}

	deinit {
		stop()
	}

	@discardableResult
	public func start() -> Bool {
		stop()
		var watched: Set<String> = []
		for url in urls {
			let directory = watchDirectory(for: url)
			guard watched.insert(directory.path).inserted else {
				continue
			}
			let descriptor = open(directory.path, O_EVTONLY)
			guard descriptor >= 0 else {
				continue
			}
			let source = DispatchSource.makeFileSystemObjectSource(
				fileDescriptor: descriptor,
				eventMask: [.write, .delete, .rename, .attrib, .extend, .link],
				queue: queue
			)
			source.setEventHandler { [weak self] in
				self?.schedule()
			}
			source.setCancelHandler {
				close(descriptor)
			}
			fileDescriptors.append(descriptor)
			sources.append(source)
			source.resume()
		}
		return !sources.isEmpty
	}

	public func stop() {
		sources.forEach { $0.cancel() }
		sources.removeAll()
		fileDescriptors.removeAll()
		scheduled = false
	}

	private func schedule() {
		guard !scheduled else {
			return
		}
		scheduled = true
		queue.asyncAfter(deadline: .now() + debounce) { [weak self] in
			guard let self else {
				return
			}
			scheduled = false
			handler()
		}
	}

	private func watchDirectory(for url: URL) -> URL {
		var directory = url.deletingLastPathComponent().standardizedFileURL
		while !FileManager.default.fileExists(atPath: directory.path) {
			let parent = directory.deletingLastPathComponent().standardizedFileURL
			if parent.path == directory.path {
				break
			}
			directory = parent
		}
		return directory
	}
}
