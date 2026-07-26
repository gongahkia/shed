import Darwin
import Foundation
import ItsyWorkbenchDSL
import ItsyWorkbenchLayout

public enum ItsySettingsCompatibilityPolicy: String, Equatable, Sendable {
	case warnAndIgnoreUnknownFields
}

public enum ItsySettingsSchema {
	public static let currentVersion = 10
	public static let compatibilityPolicy: ItsySettingsCompatibilityPolicy = .warnAndIgnoreUnknownFields
}

public struct ItsySettings: Equatable, Sendable {
	public enum UIDensity: String, Equatable, Sendable {
		case compact
		case regular
		case comfortable
	}

	public enum UINotificationPosition: String, Equatable, Sendable {
		case bottomRight = "bottom_right"
		case topRight = "top_right"
	}

	public struct UISettings: Equatable, Sendable {
		public struct SurfaceSettings: Equatable, Sendable {
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

	public enum EditorStorage: String, Equatable, Sendable {
		case rope
		case pieceTree = "piecetree"
	}

	public enum SyntaxPreloadGrammars: String, Equatable, Sendable {
		case none
		case opened
		case all
	}

	public enum TabGroupScope: String, Equatable, Sendable {
		case window
		case pane
	}

	public enum KeymapMode: String, Equatable, Sendable {
		case plain
		case vim
		case emacs
	}

	public enum CursorStyle: String, Equatable, Sendable {
		case automatic
		case block
		case bar
	}

	public enum LineNumberMode: String, Equatable, Sendable {
		case off
		case absolute
		case relative
	}

	public enum WrapMode: String, Equatable, Sendable {
		case none
		case soft
		case hard
	}

	public enum FontRenderingMode: String, Equatable, Sendable {
		case grayscale
		case subpixel
	}

	public enum SidebarPosition: String, Equatable, Sendable {
		case leading
		case trailing
	}

	public struct EditorSettings: Equatable, Sendable {
		public struct LanguageSettings: Equatable, Sendable {
			public var font: String?
			public var fontSize: Double?
			public var lineNumbers: Bool?
			public var tabWidth: Int?
			public var useSpaces: Bool?
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
				self.autoPairs = autoPairs
				self.smartIndent = smartIndent
				self.multipleSelections = multipleSelections
				self.fontRendering = fontRendering
			}
		}

		public struct ExperimentalSettings: Equatable, Sendable {
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

	public struct ThemeSettings: Equatable, Sendable {
		public struct GitGutterSettings: Equatable, Sendable {
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

	public struct SyntaxSettings: Equatable, Sendable {
		public var preloadGrammars: SyntaxPreloadGrammars

		public init(preloadGrammars: SyntaxPreloadGrammars = .opened) {
			self.preloadGrammars = preloadGrammars
		}
	}

	public struct TerminalSettings: Equatable, Sendable {
		public enum Presentation: String, Equatable, Sendable {
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

	public struct GitSettings: Equatable, Sendable {
		public enum Presentation: String, Equatable, Sendable {
			case sidebar
			case window
		}

		public var presentation: Presentation

		public init(presentation: Presentation = .sidebar) {
			self.presentation = presentation
		}
	}

	public struct FindSettings: Equatable, Sendable {
		public var usesRegex: Bool
		public var isCaseSensitive: Bool
		public var matchesWholeWord: Bool

		public init(usesRegex: Bool = false, isCaseSensitive: Bool = false, matchesWholeWord: Bool = false) {
			self.usesRegex = usesRegex
			self.isCaseSensitive = isCaseSensitive
			self.matchesWholeWord = matchesWholeWord
		}
	}

	public struct RecoverySettings: Equatable, Sendable {
		public var journalEnabled: Bool

		public init(journalEnabled: Bool = true) {
			self.journalEnabled = journalEnabled
		}
	}

	public struct UpdateSettings: Equatable, Sendable {
		public var automaticallyCheck: Bool

		public init(automaticallyCheck: Bool = false) {
			self.automaticallyCheck = automaticallyCheck
		}
	}

	public struct LayoutSettings: Equatable, Sendable {
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
	public var find: FindSettings
	public var recovery: RecoverySettings
	public var updates: UpdateSettings
	public var workbench: WorkbenchLayoutConfiguration
	public var layout: LayoutSettings
	public var ui: UISettings

	public init(
		editor: EditorSettings = EditorSettings(),
		theme: ThemeSettings = ThemeSettings(),
		syntax: SyntaxSettings = SyntaxSettings(),
		terminal: TerminalSettings = TerminalSettings(),
		git: GitSettings = GitSettings(),
		find: FindSettings = FindSettings(),
		recovery: RecoverySettings = RecoverySettings(),
		updates: UpdateSettings = UpdateSettings(),
		workbench: WorkbenchLayoutConfiguration = WorkbenchProfileBuilder.workbench(),
		layout: LayoutSettings = LayoutSettings(),
		ui: UISettings = UISettings()
	) {
		self.editor = editor
		self.theme = theme
		self.syntax = syntax
		self.terminal = terminal
		self.git = git
		self.find = find
		self.recovery = recovery
		self.updates = updates
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
		self.init(fileURL: Self.defaultFileURL(fileManager: fileManager), fileManager: fileManager)
	}

	public init(fileURL: URL, fileManager: FileManager = .default) {
		self.fileURL = fileURL
		self.fileManager = fileManager
	}

	public static func defaultFileURL(fileManager: FileManager = .default) -> URL {
		fileManager.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("settings.toml")
	}

	public static func workspaceFileURL(workspaceRoot: URL) -> URL {
		workspaceRoot
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("settings.toml")
	}

	public func load(fallback: ItsySettings = .default) -> ItsySettingsLoadResult {
		guard fileManager.fileExists(atPath: fileURL.path) else {
			return ItsySettingsLoadResult(settings: fallback.normalized(), loadedFromFile: false)
		}
		do {
			let contents = try String(contentsOf: fileURL, encoding: .utf8)
			var parser = ItsySettingsParser(settings: fallback, source: fileURL.path)
			let result = parser.parse(contents)
			return ItsySettingsLoadResult(
				settings: result.settings.normalized(),
				warnings: result.warnings,
				loadedFromFile: true,
				assignedKeys: result.assignedKeys
			)
		} catch {
			return ItsySettingsLoadResult(
				settings: fallback.normalized(),
				warnings: [ItsySettingsWarning(message: "failed to read \(fileURL.path): \(error)")],
				loadedFromFile: true
			)
		}
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
		let workspacePersonalKeys = workspace.assignedKeys.filter { $0.hasPrefix("ui.") || $0.hasPrefix("updates.") }
		if !workspacePersonalKeys.isEmpty {
			workspace.settings.ui = global.settings.ui
			workspace.settings.updates = global.settings.updates
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
			let workspacePersonalKeys = loadedWorkspace.assignedKeys.filter { $0.hasPrefix("ui.") || $0.hasPrefix("updates.") }
			if !workspacePersonalKeys.isEmpty {
				loadedWorkspace.settings.ui = global.settings.ui
				loadedWorkspace.settings.updates = global.settings.updates
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
		try Self.serialize(settings.normalized()).write(to: fileURL, atomically: true, encoding: .utf8)
	}

	public static func serialize(_ settings: ItsySettings) -> String {
		let settings = settings.normalized()
		let terminalFont = settings.terminal.font.map { "font = \"\(escape($0))\"\n" } ?? ""
		return """
		# Itsy settings. Changes reload while Itsy is running.
		schema_version = \(ItsySettingsSchema.currentVersion)

		[editor]
		font = "\(escape(settings.editor.font))"
		font_size = \(format(settings.editor.fontSize))
		font_rendering = "\(settings.editor.fontRendering.rawValue)"
		line_numbers = \(settings.editor.lineNumbers ? "true" : "false")
		line_number_mode = "\(settings.editor.lineNumberMode.rawValue)"
		tab_width = \(settings.editor.tabWidth)
		use_spaces = \(settings.editor.useSpaces ? "true" : "false")
		auto_pairs = \(settings.editor.autoPairs ? "true" : "false")
		smart_indent = \(settings.editor.smartIndent ? "true" : "false")
		multiple_selections = \(settings.editor.multipleSelections ? "true" : "false")
		keymap = "\(settings.editor.keymap.rawValue)"
		cursor_style = "\(settings.editor.cursorStyle.rawValue)"
		tab_groups = "\(settings.editor.tabGroups.rawValue)"
		wrap = "\(settings.editor.wrap.rawValue)"
		wrap_column = \(settings.editor.wrapColumn)

		[editor.experimental]
		storage = "\(settings.editor.experimental.storage.rawValue)"

		[theme]
		id = "\(escape(settings.theme.id))"
		git.gutter.added = "\(escape(settings.theme.gitGutter.added))"
		git.gutter.modified = "\(escape(settings.theme.gitGutter.modified))"
		git.gutter.removed = "\(escape(settings.theme.gitGutter.removed))"

		[syntax]
		preload_grammars = "\(settings.syntax.preloadGrammars.rawValue)"

		[terminal]
		\(terminalFont)font_size = \(format(settings.terminal.fontSize))
		scrollback_lines = \(settings.terminal.scrollbackLines)
		presentation = "\(settings.terminal.presentation.rawValue)"

		[git]
		presentation = "\(settings.git.presentation.rawValue)"

		[find]
		uses_regex = \(settings.find.usesRegex ? "true" : "false")
		case_sensitive = \(settings.find.isCaseSensitive ? "true" : "false")
		whole_word = \(settings.find.matchesWholeWord ? "true" : "false")

		[recovery]
		journal_enabled = \(settings.recovery.journalEnabled ? "true" : "false")

		[updates]
		automatically_check = \(settings.updates.automaticallyCheck ? "true" : "false")

		[workbench]
		profile = "\(settings.workbench.profile.rawValue)"
		file_tree = "\(settings.workbench.fileTree.rawValue)"
		terminal = "\(settings.workbench.terminal.rawValue)"
		git = "\(settings.workbench.git.rawValue)"

		[layout]
		sidebar_visible = \(settings.layout.sidebarVisible ? "true" : "false")
		sidebar_position = "\(settings.layout.sidebarPosition.rawValue)"
		sidebar_width = \(settings.layout.sidebarWidth)
		tab_bar_visible = \(settings.layout.tabBarVisible ? "true" : "false")
		status_bar_visible = \(settings.layout.statusBarVisible ? "true" : "false")
		interface_scale = \(format(settings.layout.interfaceScale))

		[ui]
		font_scale = \(format(settings.ui.fontScale))
		density = "\(settings.ui.density.rawValue)"
		corner_radius = \(format(settings.ui.cornerRadius))
		border_width = \(format(settings.ui.borderWidth))
		padding = \(format(settings.ui.padding))
		notification_position = "\(settings.ui.notificationPosition.rawValue)"
		""" + serializeLanguageSettings(settings.editor.language) + serializeUISurfaces(settings.ui.surfaces)
	}

	private static func format(_ value: Double) -> String {
		let rounded = (value * 100).rounded() / 100
		var text = String(rounded)
		while text.contains("."), text.last == "0" {
			text.removeLast()
		}
		if text.last == "." {
			text.removeLast()
		}
		return text
	}

	private static func escape(_ value: String) -> String {
		value
			.replacingOccurrences(of: "\\", with: "\\\\")
			.replacingOccurrences(of: "\"", with: "\\\"")
			.replacingOccurrences(of: "\n", with: "\\n")
			.replacingOccurrences(of: "\t", with: "\\t")
	}

	private static func serializeLanguageSettings(_ language: [String: ItsySettings.EditorSettings.LanguageSettings])
		-> String
	{
		guard !language.isEmpty else {
			return ""
		}
		return language.keys.sorted().map { key in
			guard let settings = language[key] else {
				return ""
			}
			var lines = ["", "[editor.language.\(key)]"]
			if let font = settings.font {
				lines.append("font = \"\(escape(font))\"")
			}
			if let fontSize = settings.fontSize {
				lines.append("font_size = \(format(fontSize))")
			}
			if let fontRendering = settings.fontRendering {
				lines.append("font_rendering = \"\(fontRendering.rawValue)\"")
			}
			if let lineNumbers = settings.lineNumbers {
				lines.append("line_numbers = \(lineNumbers ? "true" : "false")")
			}
			if let tabWidth = settings.tabWidth {
				lines.append("tab_width = \(tabWidth)")
			}
			if let useSpaces = settings.useSpaces {
				lines.append("use_spaces = \(useSpaces ? "true" : "false")")
			}
			if let autoPairs = settings.autoPairs {
				lines.append("auto_pairs = \(autoPairs ? "true" : "false")")
			}
			if let smartIndent = settings.smartIndent {
				lines.append("smart_indent = \(smartIndent ? "true" : "false")")
			}
			if let multipleSelections = settings.multipleSelections {
				lines.append("multiple_selections = \(multipleSelections ? "true" : "false")")
			}
			return lines.joined(separator: "\n")
		}.joined(separator: "\n") + "\n"
	}

	private static func serializeUISurfaces(_ surfaces: [String: ItsySettings.UISettings.SurfaceSettings]) -> String {
		surfaces.keys.sorted().compactMap { id in
			guard let surface = surfaces[id] else { return nil }
			var lines = ["", "[ui.surface.\(id)]"]
			if let width = surface.width {
				lines.append("width = \(format(width))")
			}
			if let height = surface.height {
				lines.append("height = \(format(height))")
			}
			if let rowHeight = surface.rowHeight {
				lines.append("row_height = \(format(rowHeight))")
			}
			if let inputFontSize = surface.inputFontSize {
				lines.append("input_font_size = \(format(inputFontSize))")
			}
			if let itemFontSize = surface.itemFontSize {
				lines.append("item_font_size = \(format(itemFontSize))")
			}
			return lines.joined(separator: "\n")
		}.joined(separator: "\n") + (surfaces.isEmpty ? "" : "\n")
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
		case "find.uses_regex": target.find.usesRegex = source.find.usesRegex
		case "find.case_sensitive": target.find.isCaseSensitive = source.find.isCaseSensitive
		case "find.whole_word": target.find.matchesWholeWord = source.find.matchesWholeWord
		case "recovery.journal_enabled": target.recovery.journalEnabled = source.recovery.journalEnabled
		case "updates.automatically_check": target.updates.automaticallyCheck = source.updates.automaticallyCheck
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
		default: applyLanguage(key: key, from: source, to: &target)
		}
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

enum ItsySettingsValue: Equatable {
	case string(String)
	case bool(Bool)
	case int(Int)
	case double(Double)
}

struct ItsySettingsParser {
	private var settings: ItsySettings
	private var warnings: [ItsySettingsWarning] = []
	private var assignedKeys: Set<String> = []
	private let source: String?

	init(settings: ItsySettings = .default, source: String? = nil) {
		self.settings = settings
		self.source = source
	}

	mutating func parse(_ contents: String) -> ItsySettingsLoadResult {
		var section = ""
		for (offset, rawLine) in contents.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("["), line.hasSuffix("]") {
				section = String(line.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
				if !["editor", "editor.experimental", "theme", "syntax", "terminal", "git", "find", "recovery", "updates", "workbench", "layout", "ui"]
					.contains(section),
					!section.hasPrefix("editor.language."), !section.hasPrefix("ui.surface.")
				{
					warnings.append(ItsySettingsWarning(line: lineNumber, message: "unknown section [\(section)]"))
				}
				continue
			}
			guard let equals = line.firstIndex(of: "=") else {
				warnings.append(ItsySettingsWarning(line: lineNumber, message: "expected key = value"))
				continue
			}
			let rawKey = line[..<equals].trimmingCharacters(in: .whitespaces)
			let rawValue = line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces)
			let key = section.isEmpty ? String(rawKey) : "\(section).\(rawKey)"
			guard let value = parseValue(String(rawValue)) else {
				warnings.append(ItsySettingsWarning(
					line: lineNumber,
					key: key,
					source: source,
					retainedFallback: true,
					message: "invalid value for \(key)"
				))
				continue
			}
			if key == "schema_version" {
				assignSchemaVersion(value, line: lineNumber)
				continue
			}
			let warningCount = warnings.count
			assign(value, key: key, line: lineNumber)
			if warnings.count == warningCount {
				assignedKeys.insert(key)
			}
		}
		if let message = WorkbenchConfigurationValidator.validate(settings.workbench) {
			warnings.append(ItsySettingsWarning(
				key: "workbench",
				source: source,
				retainedFallback: true,
				message: message
			))
			settings.workbench.fileTree = .automatic
		}
		return ItsySettingsLoadResult(
			settings: settings,
			warnings: warnings,
			loadedFromFile: true,
			assignedKeys: assignedKeys
		)
	}

	private mutating func assignSchemaVersion(_ value: ItsySettingsValue, line: Int) {
		guard case let .int(version) = value, version > 0 else {
			warnType("schema_version", line: line, expected: "positive integer")
			return
		}
		guard version <= ItsySettingsSchema.currentVersion else {
			warnings.append(ItsySettingsWarning(
				line: line,
				key: "schema_version",
				source: source,
				expected: "version <= \(ItsySettingsSchema.currentVersion)",
				retainedFallback: true,
				message: "schema_version \(version) is newer than supported \(ItsySettingsSchema.currentVersion); unknown settings are ignored"
			))
			return
		}
	}

	private mutating func assign(_ value: ItsySettingsValue, key: String, line: Int) {
		if assignLanguageEditor(value, key: key, line: line) {
			return
		}
		if assignUISurface(value, key: key, line: line) {
			return
		}
		switch key {
		case "editor.font":
			if case let .string(font) = value {
				settings.editor.font = font
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "editor.font_size":
			if let number = doubleValue(value),
			   (ItsySettings.EditorSettings.minFontSize ... ItsySettings.EditorSettings.maxFontSize).contains(number)
			{
				settings.editor.fontSize = number
			} else if doubleValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "number between \(ItsySettings.EditorSettings.minFontSize) and \(ItsySettings.EditorSettings.maxFontSize)"
				)
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "editor.font_rendering":
			if case let .string(mode) = value, let mode = ItsySettings.FontRenderingMode(rawValue: mode.lowercased()) {
				settings.editor.fontRendering = mode
			} else {
				warnType(key, line: line, expected: #""grayscale" or "subpixel""#)
			}
		case "editor.line_numbers":
			if case let .bool(lineNumbers) = value {
				settings.editor.lineNumbers = lineNumbers
				settings.editor.lineNumberMode = lineNumbers ? .absolute : .off
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.line_number_mode":
			if case let .string(mode) = value, let mode = ItsySettings.LineNumberMode(rawValue: mode.lowercased()) {
				settings.editor.lineNumberMode = mode
				settings.editor.lineNumbers = mode != .off
			} else {
				warnType(key, line: line, expected: #""off", "absolute", or "relative""#)
			}
		case "editor.tab_width":
			if let integer = intValue(value),
			   (ItsySettings.EditorSettings.minTabWidth ... ItsySettings.EditorSettings.maxTabWidth).contains(integer)
			{
				settings.editor.tabWidth = integer
			} else if intValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "integer between \(ItsySettings.EditorSettings.minTabWidth) and \(ItsySettings.EditorSettings.maxTabWidth)"
				)
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "editor.use_spaces":
			if case let .bool(useSpaces) = value {
				settings.editor.useSpaces = useSpaces
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.auto_pairs":
			if case let .bool(autoPairs) = value {
				settings.editor.autoPairs = autoPairs
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.smart_indent":
			if case let .bool(smartIndent) = value {
				settings.editor.smartIndent = smartIndent
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.multiple_selections":
			if case let .bool(multipleSelections) = value {
				settings.editor.multipleSelections = multipleSelections
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "editor.keymap":
			if case let .string(mode) = value, let mode = ItsySettings.KeymapMode(rawValue: mode.lowercased()) {
				settings.editor.keymap = mode
			} else {
				warnType(key, line: line, expected: #""plain", "vim", or "emacs""#)
			}
		case "editor.cursor_style":
			if case let .string(style) = value {
				let style = style.lowercased()
				if let cursorStyle = ItsySettings.CursorStyle(rawValue: style) {
					settings.editor.cursorStyle = cursorStyle
				} else if style == "immediate" {
					settings.editor.cursorStyle = .bar
				} else {
					warnType(key, line: line, expected: #""automatic", "block", or "bar""#)
				}
			} else {
				warnType(key, line: line, expected: #""automatic", "block", or "bar""#)
			}
		case "editor.tab_groups":
			if case let .string(scope) = value, let scope = ItsySettings.TabGroupScope(rawValue: scope.lowercased()) {
				settings.editor.tabGroups = scope
			} else {
				warnType(key, line: line, expected: #""window" or "pane""#)
			}
		case "editor.wrap":
			if case let .string(mode) = value, let mode = ItsySettings.WrapMode(rawValue: mode.lowercased()) {
				settings.editor.wrap = mode
			} else {
				warnType(key, line: line, expected: #""none", "soft", or "hard""#)
			}
		case "editor.wrap_column":
			if let integer = intValue(value),
			   (ItsySettings.EditorSettings.minWrapColumn ... ItsySettings.EditorSettings.maxWrapColumn).contains(integer)
			{
				settings.editor.wrapColumn = integer
			} else if intValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "integer between \(ItsySettings.EditorSettings.minWrapColumn) and \(ItsySettings.EditorSettings.maxWrapColumn)"
				)
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "editor.experimental.storage":
			if case let .string(storage) = value, let storage = ItsySettings.EditorStorage(rawValue: storage.lowercased()) {
				settings.editor.experimental.storage = storage
			} else {
				warnType(key, line: line, expected: #""rope" or "piecetree""#)
			}
		case "theme.id":
			if case let .string(id) = value {
				settings.theme.id = id
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "theme.git.gutter.added":
			if case let .string(color) = value {
				settings.theme.gitGutter.added = color
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "theme.git.gutter.modified":
			if case let .string(color) = value {
				settings.theme.gitGutter.modified = color
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "theme.git.gutter.removed":
			if case let .string(color) = value {
				settings.theme.gitGutter.removed = color
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "syntax.preload_grammars":
			if case let .string(mode) = value, let mode = ItsySettings.SyntaxPreloadGrammars(rawValue: mode.lowercased()) {
				settings.syntax.preloadGrammars = mode
			} else {
				warnType(key, line: line, expected: #""none", "opened", or "all""#)
			}
		case "terminal.font_size":
			if let number = doubleValue(value),
			   (ItsySettings.TerminalSettings.minFontSize ... ItsySettings.TerminalSettings.maxFontSize).contains(number)
			{
				settings.terminal.fontSize = number
			} else if doubleValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "number between \(ItsySettings.TerminalSettings.minFontSize) and \(ItsySettings.TerminalSettings.maxFontSize)"
				)
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "terminal.font":
			if case let .string(font) = value, !font.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				settings.terminal.font = font
			} else {
				warnType(key, line: line, expected: "non-empty string")
			}
		case "terminal.scrollback_lines":
			if let integer = intValue(value),
			   (ItsySettings.TerminalSettings.minScrollbackLines ... ItsySettings.TerminalSettings.maxScrollbackLines)
			   .contains(integer)
			{
				settings.terminal.scrollbackLines = integer
			} else if intValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "integer between \(ItsySettings.TerminalSettings.minScrollbackLines) and \(ItsySettings.TerminalSettings.maxScrollbackLines)"
				)
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "terminal.presentation":
			if case let .string(presentation) = value,
			   let presentation = ItsySettings.TerminalSettings.Presentation(rawValue: presentation.lowercased())
			{
				settings.terminal.presentation = presentation
			} else {
				warnType(key, line: line, expected: #""bottom" or "window""#)
			}
		case "git.presentation":
			if case let .string(presentation) = value,
			   let presentation = ItsySettings.GitSettings.Presentation(rawValue: presentation.lowercased())
			{
				settings.git.presentation = presentation
			} else {
				warnType(key, line: line, expected: #""sidebar" or "window""#)
			}
		case "find.uses_regex":
			if case let .bool(usesRegex) = value {
				settings.find.usesRegex = usesRegex
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "find.case_sensitive":
			if case let .bool(isCaseSensitive) = value {
				settings.find.isCaseSensitive = isCaseSensitive
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "find.whole_word":
			if case let .bool(matchesWholeWord) = value {
				settings.find.matchesWholeWord = matchesWholeWord
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "recovery.journal_enabled":
			if case let .bool(journalEnabled) = value {
				settings.recovery.journalEnabled = journalEnabled
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "updates.automatically_check":
			if case let .bool(automaticallyCheck) = value {
				settings.updates.automaticallyCheck = automaticallyCheck
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "workbench.profile":
			if case let .string(profile) = value, let profile = WorkbenchProfile(rawValue: profile.lowercased()) {
				settings.workbench.profile = profile
			} else {
				warnType(key, line: line, expected: #""workbench", "focus", or "review""#)
			}
		case "workbench.file_tree":
			assignWorkbenchVisibility(value, key: key, line: line) { settings.workbench.fileTree = $0 }
		case "workbench.terminal":
			assignWorkbenchVisibility(value, key: key, line: line) { settings.workbench.terminal = $0 }
		case "workbench.git":
			assignWorkbenchVisibility(value, key: key, line: line) { settings.workbench.git = $0 }
		case "layout.sidebar_visible":
			if case let .bool(sidebarVisible) = value {
				settings.layout.sidebarVisible = sidebarVisible
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "layout.sidebar_position":
			if case let .string(position) = value, let position = ItsySettings.SidebarPosition(rawValue: position.lowercased()) {
				settings.layout.sidebarPosition = position
			} else {
				warnType(key, line: line, expected: #""leading" or "trailing""#)
			}
		case "layout.sidebar_width":
			if let integer = intValue(value),
			   (ItsySettings.LayoutSettings.minSidebarWidth ... ItsySettings.LayoutSettings.maxSidebarWidth).contains(integer)
			{
				settings.layout.sidebarWidth = integer
			} else if intValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "integer between \(ItsySettings.LayoutSettings.minSidebarWidth) and \(ItsySettings.LayoutSettings.maxSidebarWidth)"
				)
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "layout.tab_bar_visible":
			if case let .bool(tabBarVisible) = value {
				settings.layout.tabBarVisible = tabBarVisible
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "layout.status_bar_visible":
			if case let .bool(statusBarVisible) = value {
				settings.layout.statusBarVisible = statusBarVisible
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "layout.interface_scale":
			if let number = doubleValue(value),
			   (ItsySettings.LayoutSettings.minInterfaceScale ... ItsySettings.LayoutSettings.maxInterfaceScale)
			   .contains(number)
			{
				settings.layout.interfaceScale = number
			} else if doubleValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "number between \(ItsySettings.LayoutSettings.minInterfaceScale) and \(ItsySettings.LayoutSettings.maxInterfaceScale)"
				)
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "ui.font_scale":
			if let number = validatedUIValue(
				value,
				key: key,
				line: line,
				range: ItsySettings.UISettings.minFontScale ... ItsySettings.UISettings.maxFontScale
			) {
				settings.ui.fontScale = number
			}
		case "ui.density":
			if case let .string(density) = value, let density = ItsySettings.UIDensity(rawValue: density.lowercased()) {
				settings.ui.density = density
			} else {
				warnType(key, line: line, expected: #""compact", "regular", or "comfortable""#)
			}
		case "ui.corner_radius":
			if let number = validatedUIValue(
				value,
				key: key,
				line: line,
				range: ItsySettings.UISettings.minCornerRadius ... ItsySettings.UISettings.maxCornerRadius
			) {
				settings.ui.cornerRadius = number
			}
		case "ui.border_width":
			if let number = validatedUIValue(
				value,
				key: key,
				line: line,
				range: ItsySettings.UISettings.minBorderWidth ... ItsySettings.UISettings.maxBorderWidth
			) {
				settings.ui.borderWidth = number
			}
		case "ui.padding":
			if let number = validatedUIValue(
				value,
				key: key,
				line: line,
				range: ItsySettings.UISettings.minPadding ... ItsySettings.UISettings.maxPadding
			) {
				settings.ui.padding = number
			}
		case "ui.notification_position":
			if case let .string(position) = value,
			   let position = ItsySettings.UINotificationPosition(rawValue: position.lowercased())
			{
				settings.ui.notificationPosition = position
			} else {
				warnType(key, line: line, expected: #""bottom_right" or "top_right""#)
			}
		default:
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
		}
	}

	private mutating func assignWorkbenchVisibility(
		_ value: ItsySettingsValue,
		key: String,
		line: Int,
		assign: (WorkbenchVisibility) -> Void
	) {
		if case let .string(visibility) = value,
		   let visibility = WorkbenchVisibility(rawValue: visibility.lowercased())
		{
			assign(visibility)
		} else {
			warnType(key, line: line, expected: #""automatic", "visible", or "hidden""#)
		}
	}

	private mutating func validatedUIValue(_ value: ItsySettingsValue, key: String, line: Int,
	                                       range: ClosedRange<Double>) -> Double?
	{
		guard let number = doubleValue(value), range.contains(number) else {
			warnType(key, line: line, expected: "number between \(range.lowerBound) and \(range.upperBound)")
			return nil
		}
		return number
	}

	private mutating func assignUISurface(_ value: ItsySettingsValue, key: String, line: Int) -> Bool {
		let prefix = "ui.surface."
		guard key.hasPrefix(prefix) else { return false }
		let suffix = key.dropFirst(prefix.count)
		guard let dot = suffix.firstIndex(of: ".") else {
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
			return true
		}
		let id = String(suffix[..<dot]).trimmingCharacters(in: .whitespacesAndNewlines)
		let property = String(suffix[suffix.index(after: dot)...])
		guard !id.isEmpty else {
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
			return true
		}
		guard let number = doubleValue(value) else {
			warnType(key, line: line, expected: "number")
			return true
		}
		var surface = settings.ui.surfaces[id] ?? ItsySettings.UISettings.SurfaceSettings()
		switch property {
		case "width" where (200 ... 2400).contains(number): surface.width = number
		case "height" where (120 ... 1800).contains(number): surface.height = number
		case "row_height" where (16 ... 80).contains(number): surface.rowHeight = number
		case "input_font_size" where (9 ... 48).contains(number): surface.inputFontSize = number
		case "item_font_size" where (9 ... 48).contains(number): surface.itemFontSize = number
		case "width", "height", "row_height", "input_font_size", "item_font_size": warnType(
				key,
				line: line,
				expected: "number in the supported range"
			)
		default:
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
			return true
		}
		settings.ui.surfaces[id] = surface
		return true
	}

	private mutating func assignLanguageEditor(_ value: ItsySettingsValue, key: String, line: Int) -> Bool {
		let prefix = "editor.language."
		guard key.hasPrefix(prefix) else {
			return false
		}
		let suffix = key.dropFirst(prefix.count)
		guard let dot = suffix.firstIndex(of: ".") else {
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
			return true
		}
		let languageID = suffix[..<dot].trimmingCharacters(in: .whitespacesAndNewlines)
		let setting = String(suffix[suffix.index(after: dot)...])
		guard !languageID.isEmpty else {
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
			return true
		}
		var language = settings.editor.language[languageID] ?? ItsySettings.EditorSettings.LanguageSettings()
		switch setting {
		case "font":
			if case let .string(font) = value {
				language.font = font
			} else {
				warnType(key, line: line, expected: "string")
			}
		case "font_size":
			if let number = doubleValue(value),
			   (ItsySettings.EditorSettings.minFontSize ... ItsySettings.EditorSettings.maxFontSize).contains(number)
			{
				language.fontSize = number
			} else if doubleValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "number between \(ItsySettings.EditorSettings.minFontSize) and \(ItsySettings.EditorSettings.maxFontSize)"
				)
			} else {
				warnType(key, line: line, expected: "number")
			}
		case "font_rendering":
			if case let .string(mode) = value, let mode = ItsySettings.FontRenderingMode(rawValue: mode.lowercased()) {
				language.fontRendering = mode
			} else {
				warnType(key, line: line, expected: #""grayscale" or "subpixel""#)
			}
		case "line_numbers":
			if case let .bool(lineNumbers) = value {
				language.lineNumbers = lineNumbers
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "tab_width":
			if let integer = intValue(value),
			   (ItsySettings.EditorSettings.minTabWidth ... ItsySettings.EditorSettings.maxTabWidth).contains(integer)
			{
				language.tabWidth = integer
			} else if intValue(value) != nil {
				warnType(
					key,
					line: line,
					expected: "integer between \(ItsySettings.EditorSettings.minTabWidth) and \(ItsySettings.EditorSettings.maxTabWidth)"
				)
			} else {
				warnType(key, line: line, expected: "integer")
			}
		case "use_spaces":
			if case let .bool(useSpaces) = value {
				language.useSpaces = useSpaces
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "auto_pairs":
			if case let .bool(autoPairs) = value {
				language.autoPairs = autoPairs
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "smart_indent":
			if case let .bool(smartIndent) = value {
				language.smartIndent = smartIndent
			} else {
				warnType(key, line: line, expected: "bool")
			}
		case "multiple_selections":
			if case let .bool(multipleSelections) = value {
				language.multipleSelections = multipleSelections
			} else {
				warnType(key, line: line, expected: "bool")
			}
		default:
			warnings.append(ItsySettingsWarning(
				line: line,
				key: key,
				source: source,
				retainedFallback: true,
				message: "unknown setting \(key)"
			))
		}
		settings.editor.language[languageID] = language
		return true
	}

	private mutating func warnType(_ key: String, line: Int, expected: String) {
		warnings.append(ItsySettingsWarning(
			line: line,
			key: key,
			source: source,
			expected: expected,
			retainedFallback: true,
			message: "\(key) expects \(expected)"
		))
	}

	private func doubleValue(_ value: ItsySettingsValue) -> Double? {
		switch value {
		case let .double(value):
			value
		case let .int(value):
			Double(value)
		default:
			nil
		}
	}

	private func intValue(_ value: ItsySettingsValue) -> Int? {
		if case let .int(value) = value {
			return value
		}
		return nil
	}

	private func parseValue(_ raw: String) -> ItsySettingsValue? {
		if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
			return .string(unescape(String(raw.dropFirst().dropLast())))
		}
		switch raw.lowercased() {
		case "true":
			return .bool(true)
		case "false":
			return .bool(false)
		default:
			break
		}
		if raw.contains("."), let value = Double(raw) {
			return .double(value)
		}
		if let value = Int(raw) {
			return .int(value)
		}
		return nil
	}

	private func stripComment(_ line: String) -> String {
		var quoted = false
		var escaped = false
		for index in line.indices {
			let character = line[index]
			if escaped {
				escaped = false
				continue
			}
			if character == "\\" {
				escaped = true
				continue
			}
			if character == "\"" {
				quoted.toggle()
				continue
			}
			if character == "#", !quoted {
				return String(line[..<index])
			}
		}
		return line
	}

	private func unescape(_ value: String) -> String {
		var result = ""
		var escaping = false
		for character in value {
			if escaping {
				switch character {
				case "n":
					result.append("\n")
				case "t":
					result.append("\t")
				default:
					result.append(character)
				}
				escaping = false
				continue
			}
			if character == "\\" {
				escaping = true
			} else {
				result.append(character)
			}
		}
		if escaping {
			result.append("\\")
		}
		return result
	}
}
