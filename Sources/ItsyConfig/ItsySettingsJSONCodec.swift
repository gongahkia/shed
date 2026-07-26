import Foundation
import ItsyWorkbenchLayout

public struct ItsySettingsJSONDocument: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var settings: ItsySettings

	public init(schemaVersion: Int = ItsySettingsSchema.currentVersion, settings: ItsySettings) {
		self.schemaVersion = schemaVersion
		self.settings = settings
	}
}

public struct ItsySettingsJSONLayer: Equatable, Sendable {
	public let settings: ItsySettings
	public let assignedKeys: Set<String>

	public init(settings: ItsySettings, assignedKeys: Set<String>) {
		self.settings = settings
		self.assignedKeys = assignedKeys
	}
}

public enum ItsySettingsJSONCodecError: Error, Equatable, Sendable {
	case unsupportedSchemaVersion(Int)
	case invalidSettings
}

public enum ItsySettingsJSONCodec {
	public static func encode(_ settings: ItsySettings) throws -> Data {
		let encoder = JSONEncoder()
		encoder.keyEncodingStrategy = .convertToSnakeCase
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
		return try encoder.encode(ItsySettingsJSONDocument(settings: settings.normalized()))
	}

	public static func decode(_ data: Data) throws -> ItsySettings {
		try decodeLayer(data).settings
	}

	public static func decodeLayer(
		_ data: Data,
		fallback: ItsySettings = .default
	) throws -> ItsySettingsJSONLayer {
		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		let document = try decoder.decode(ItsySettingsJSONLayerDocument.self, from: data)
		guard document.schemaVersion == ItsySettingsSchema.currentVersion else {
			throw ItsySettingsJSONCodecError.unsupportedSchemaVersion(document.schemaVersion)
		}
		var settings = fallback.normalized()
		let assignedKeys = document.settings.apply(to: &settings)
		guard settings == settings.normalized() else {
			throw ItsySettingsJSONCodecError.invalidSettings
		}
		return ItsySettingsJSONLayer(settings: settings, assignedKeys: assignedKeys)
	}
}

private struct ItsySettingsJSONLayerDocument: Decodable {
	let schemaVersion: Int
	let settings: ItsySettingsJSONPatch
}

private struct ItsySettingsJSONPatch: Decodable {
	let editor: Editor?
	let theme: Theme?
	let syntax: Syntax?
	let terminal: Terminal?
	let git: Git?
	let debugger: Debugger?
	let find: Find?
	let recovery: Recovery?
	let updates: Updates?
	let lsp: LSP?
	let workbench: Workbench?
	let layout: Layout?
	let ui: UI?

	struct Editor: Decodable {
		let font: String?
		let fontSize: Double?
		let fontRendering: ItsySettings.FontRenderingMode?
		let lineNumbers: Bool?
		let lineNumberMode: ItsySettings.LineNumberMode?
		let tabWidth: Int?
		let useSpaces: Bool?
		let autoPairs: Bool?
		let smartIndent: Bool?
		let multipleSelections: Bool?
		let tabGroups: ItsySettings.TabGroupScope?
		let keymap: ItsySettings.KeymapMode?
		let cursorStyle: ItsySettings.CursorStyle?
		let wrap: ItsySettings.WrapMode?
		let wrapColumn: Int?
		let language: [String: Language]?
		let experimental: Experimental?

		struct Language: Decodable {
			let font: String?
			let fontSize: Double?
			let fontRendering: ItsySettings.FontRenderingMode?
			let lineNumbers: Bool?
			let tabWidth: Int?
			let useSpaces: Bool?
			let autoPairs: Bool?
			let smartIndent: Bool?
			let multipleSelections: Bool?
		}

		struct Experimental: Decodable {
			let storage: ItsySettings.EditorStorage?
		}
	}

	struct Theme: Decodable {
		let id: String?
		let gitGutter: GitGutter?

		struct GitGutter: Decodable {
			let added: String?
			let modified: String?
			let removed: String?
		}
	}

	struct Syntax: Decodable {
		let preloadGrammars: ItsySettings.SyntaxPreloadGrammars?
	}

	struct Terminal: Decodable {
		let font: String?
		let fontSize: Double?
		let scrollbackLines: Int?
		let presentation: ItsySettings.TerminalSettings.Presentation?
	}

	struct Git: Decodable {
		let presentation: ItsySettings.GitSettings.Presentation?
	}

	struct Debugger: Decodable {
		let presentation: ItsySettings.DebuggerSettings.Presentation?
	}

	struct Find: Decodable {
		let usesRegex: Bool?
		let isCaseSensitive: Bool?
		let matchesWholeWord: Bool?
	}

	struct Recovery: Decodable {
		let journalEnabled: Bool?
	}

	struct Updates: Decodable {
		let automaticallyCheck: Bool?
	}

	struct LSP: Decodable {
		let catalogAutomaticallyCheck: Bool?
		let modes: [String: ItsySettings.LSPMode]?
	}

	struct Workbench: Decodable {
		let profile: WorkbenchProfile?
		let fileTree: WorkbenchVisibility?
		let terminal: WorkbenchVisibility?
		let git: WorkbenchVisibility?
	}

	struct Layout: Decodable {
		let sidebarVisible: Bool?
		let sidebarPosition: ItsySettings.SidebarPosition?
		let sidebarWidth: Int?
		let tabBarVisible: Bool?
		let statusBarVisible: Bool?
		let interfaceScale: Double?
	}

	struct UI: Decodable {
		let fontScale: Double?
		let density: ItsySettings.UIDensity?
		let cornerRadius: Double?
		let borderWidth: Double?
		let padding: Double?
		let notificationPosition: ItsySettings.UINotificationPosition?
		let surfaces: [String: Surface]?

		struct Surface: Decodable {
			let width: Double?
			let height: Double?
			let rowHeight: Double?
			let inputFontSize: Double?
			let itemFontSize: Double?
		}
	}

	func apply(to settings: inout ItsySettings) -> Set<String> {
		var assignedKeys = Set<String>()
		if let editor {
			if let font = editor.font { settings.editor.font = font; assignedKeys.insert("editor.font") }
			if let fontSize = editor.fontSize { settings.editor.fontSize = fontSize; assignedKeys.insert("editor.font_size") }
			if let fontRendering = editor.fontRendering { settings.editor.fontRendering = fontRendering; assignedKeys.insert("editor.font_rendering") }
			if let lineNumbers = editor.lineNumbers {
				settings.editor.lineNumbers = lineNumbers
				if editor.lineNumberMode == nil { settings.editor.lineNumberMode = lineNumbers ? .absolute : .off }
				assignedKeys.insert("editor.line_numbers")
			}
			if let lineNumberMode = editor.lineNumberMode { settings.editor.lineNumberMode = lineNumberMode; assignedKeys.insert("editor.line_number_mode") }
			if let tabWidth = editor.tabWidth { settings.editor.tabWidth = tabWidth; assignedKeys.insert("editor.tab_width") }
			if let useSpaces = editor.useSpaces { settings.editor.useSpaces = useSpaces; assignedKeys.insert("editor.use_spaces") }
			if let autoPairs = editor.autoPairs { settings.editor.autoPairs = autoPairs; assignedKeys.insert("editor.auto_pairs") }
			if let smartIndent = editor.smartIndent { settings.editor.smartIndent = smartIndent; assignedKeys.insert("editor.smart_indent") }
			if let multipleSelections = editor.multipleSelections { settings.editor.multipleSelections = multipleSelections; assignedKeys.insert("editor.multiple_selections") }
			if let tabGroups = editor.tabGroups { settings.editor.tabGroups = tabGroups; assignedKeys.insert("editor.tab_groups") }
			if let keymap = editor.keymap { settings.editor.keymap = keymap; assignedKeys.insert("editor.keymap") }
			if let cursorStyle = editor.cursorStyle { settings.editor.cursorStyle = cursorStyle; assignedKeys.insert("editor.cursor_style") }
			if let wrap = editor.wrap { settings.editor.wrap = wrap; assignedKeys.insert("editor.wrap") }
			if let wrapColumn = editor.wrapColumn { settings.editor.wrapColumn = wrapColumn; assignedKeys.insert("editor.wrap_column") }
			if let storage = editor.experimental?.storage { settings.editor.experimental.storage = storage; assignedKeys.insert("editor.experimental.storage") }
			for (languageID, language) in editor.language ?? [:] {
				var target = settings.editor.language[languageID] ?? .init()
				let prefix = "editor.language.\(languageID)."
				if let font = language.font { target.font = font; assignedKeys.insert(prefix + "font") }
				if let fontSize = language.fontSize { target.fontSize = fontSize; assignedKeys.insert(prefix + "font_size") }
				if let fontRendering = language.fontRendering { target.fontRendering = fontRendering; assignedKeys.insert(prefix + "font_rendering") }
				if let lineNumbers = language.lineNumbers { target.lineNumbers = lineNumbers; assignedKeys.insert(prefix + "line_numbers") }
				if let tabWidth = language.tabWidth { target.tabWidth = tabWidth; assignedKeys.insert(prefix + "tab_width") }
				if let useSpaces = language.useSpaces { target.useSpaces = useSpaces; assignedKeys.insert(prefix + "use_spaces") }
				if let autoPairs = language.autoPairs { target.autoPairs = autoPairs; assignedKeys.insert(prefix + "auto_pairs") }
				if let smartIndent = language.smartIndent { target.smartIndent = smartIndent; assignedKeys.insert(prefix + "smart_indent") }
				if let multipleSelections = language.multipleSelections { target.multipleSelections = multipleSelections; assignedKeys.insert(prefix + "multiple_selections") }
				settings.editor.language[languageID] = target
			}
		}
		if let theme {
			if let id = theme.id { settings.theme.id = id; assignedKeys.insert("theme.id") }
			if let added = theme.gitGutter?.added { settings.theme.gitGutter.added = added; assignedKeys.insert("theme.git.gutter.added") }
			if let modified = theme.gitGutter?.modified { settings.theme.gitGutter.modified = modified; assignedKeys.insert("theme.git.gutter.modified") }
			if let removed = theme.gitGutter?.removed { settings.theme.gitGutter.removed = removed; assignedKeys.insert("theme.git.gutter.removed") }
		}
		if let preloadGrammars = syntax?.preloadGrammars { settings.syntax.preloadGrammars = preloadGrammars; assignedKeys.insert("syntax.preload_grammars") }
		if let terminal {
			if let font = terminal.font { settings.terminal.font = font; assignedKeys.insert("terminal.font") }
			if let fontSize = terminal.fontSize { settings.terminal.fontSize = fontSize; assignedKeys.insert("terminal.font_size") }
			if let scrollbackLines = terminal.scrollbackLines { settings.terminal.scrollbackLines = scrollbackLines; assignedKeys.insert("terminal.scrollback_lines") }
			if let presentation = terminal.presentation { settings.terminal.presentation = presentation; assignedKeys.insert("terminal.presentation") }
		}
		if let presentation = git?.presentation { settings.git.presentation = presentation; assignedKeys.insert("git.presentation") }
		if let presentation = debugger?.presentation { settings.debugger.presentation = presentation; assignedKeys.insert("debugger.presentation") }
		if let find {
			if let usesRegex = find.usesRegex { settings.find.usesRegex = usesRegex; assignedKeys.insert("find.uses_regex") }
			if let isCaseSensitive = find.isCaseSensitive { settings.find.isCaseSensitive = isCaseSensitive; assignedKeys.insert("find.case_sensitive") }
			if let matchesWholeWord = find.matchesWholeWord { settings.find.matchesWholeWord = matchesWholeWord; assignedKeys.insert("find.whole_word") }
		}
		if let journalEnabled = recovery?.journalEnabled { settings.recovery.journalEnabled = journalEnabled; assignedKeys.insert("recovery.journal_enabled") }
		if let automaticallyCheck = updates?.automaticallyCheck { settings.updates.automaticallyCheck = automaticallyCheck; assignedKeys.insert("updates.automatically_check") }
		if let lsp {
			if let catalogAutomaticallyCheck = lsp.catalogAutomaticallyCheck { settings.lsp.catalogAutomaticallyCheck = catalogAutomaticallyCheck; assignedKeys.insert("lsp.catalog_automatically_check") }
			for (languageID, mode) in lsp.modes ?? [:] { settings.lsp.modes[languageID] = mode; assignedKeys.insert("lsp.\(languageID).mode") }
		}
		if let workbench {
			if let profile = workbench.profile { settings.workbench.profile = profile; assignedKeys.insert("workbench.profile") }
			if let fileTree = workbench.fileTree { settings.workbench.fileTree = fileTree; assignedKeys.insert("workbench.file_tree") }
			if let terminal = workbench.terminal { settings.workbench.terminal = terminal; assignedKeys.insert("workbench.terminal") }
			if let git = workbench.git { settings.workbench.git = git; assignedKeys.insert("workbench.git") }
		}
		if let layout {
			if let sidebarVisible = layout.sidebarVisible { settings.layout.sidebarVisible = sidebarVisible; assignedKeys.insert("layout.sidebar_visible") }
			if let sidebarPosition = layout.sidebarPosition { settings.layout.sidebarPosition = sidebarPosition; assignedKeys.insert("layout.sidebar_position") }
			if let sidebarWidth = layout.sidebarWidth { settings.layout.sidebarWidth = sidebarWidth; assignedKeys.insert("layout.sidebar_width") }
			if let tabBarVisible = layout.tabBarVisible { settings.layout.tabBarVisible = tabBarVisible; assignedKeys.insert("layout.tab_bar_visible") }
			if let statusBarVisible = layout.statusBarVisible { settings.layout.statusBarVisible = statusBarVisible; assignedKeys.insert("layout.status_bar_visible") }
			if let interfaceScale = layout.interfaceScale { settings.layout.interfaceScale = interfaceScale; assignedKeys.insert("layout.interface_scale") }
		}
		if let ui {
			if let fontScale = ui.fontScale { settings.ui.fontScale = fontScale; assignedKeys.insert("ui.font_scale") }
			if let density = ui.density { settings.ui.density = density; assignedKeys.insert("ui.density") }
			if let cornerRadius = ui.cornerRadius { settings.ui.cornerRadius = cornerRadius; assignedKeys.insert("ui.corner_radius") }
			if let borderWidth = ui.borderWidth { settings.ui.borderWidth = borderWidth; assignedKeys.insert("ui.border_width") }
			if let padding = ui.padding { settings.ui.padding = padding; assignedKeys.insert("ui.padding") }
			if let notificationPosition = ui.notificationPosition { settings.ui.notificationPosition = notificationPosition; assignedKeys.insert("ui.notification_position") }
			for (id, surface) in ui.surfaces ?? [:] {
				var target = settings.ui.surfaces[id] ?? .init()
				let prefix = "ui.surface.\(id)."
				if let width = surface.width { target.width = width; assignedKeys.insert(prefix + "width") }
				if let height = surface.height { target.height = height; assignedKeys.insert(prefix + "height") }
				if let rowHeight = surface.rowHeight { target.rowHeight = rowHeight; assignedKeys.insert(prefix + "row_height") }
				if let inputFontSize = surface.inputFontSize { target.inputFontSize = inputFontSize; assignedKeys.insert(prefix + "input_font_size") }
				if let itemFontSize = surface.itemFontSize { target.itemFontSize = itemFontSize; assignedKeys.insert(prefix + "item_font_size") }
				settings.ui.surfaces[id] = target
			}
		}
		return assignedKeys
	}
}
