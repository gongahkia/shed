import Foundation

public enum ItsySettingsCatalog {
	public enum ReloadBehavior: String, Equatable, Sendable {
		case hotReload
		case restartRequired

		public var displayName: String {
			switch self {
			case .hotReload: "Hot reload"
			case .restartRequired: "Restart required"
			}
		}
	}

	public struct Entry: Equatable, Sendable, Identifiable {
		public let key: String
		public let title: String
		public let description: String
		public let reloadBehavior: ReloadBehavior
		public let isLanguageTemplate: Bool
		public let isResettable: Bool

		public var id: String { key }

		public init(key: String, title: String, description: String, reloadBehavior: ReloadBehavior = .hotReload, isLanguageTemplate: Bool = false, isResettable: Bool = true) {
			self.key = key
			self.title = title
			self.description = description
			self.reloadBehavior = reloadBehavior
			self.isLanguageTemplate = isLanguageTemplate
			self.isResettable = isResettable
		}
	}

	public static let entries: [Entry] = [
		.init(key: "schema_version", title: "Schema Version", description: "Version used to validate settings.toml.", isResettable: false),
		.init(key: "editor.font", title: "Editor Font", description: "Font family for editor text."),
		.init(key: "editor.font_size", title: "Editor Font Size", description: "Point size for editor text."),
		.init(key: "editor.font_rendering", title: "Font Rendering", description: "Glyph atlas rendering mode."),
		.init(key: "editor.line_numbers", title: "Line Numbers", description: "Shows line numbers in the editor gutter."),
		.init(key: "editor.line_number_mode", title: "Line Number Mode", description: "Off, absolute, or relative line numbers."),
		.init(key: "editor.tab_width", title: "Tab Width", description: "Columns inserted or displayed for a tab."),
		.init(key: "editor.use_spaces", title: "Indent Using Spaces", description: "Uses spaces instead of tab characters for indentation."),
		.init(key: "editor.auto_pairs", title: "Auto Pairs", description: "Inserts matching brackets and quotes."),
		.init(key: "editor.smart_indent", title: "Smart Indent", description: "Carries indentation into a new line."),
		.init(key: "editor.multiple_selections", title: "Multiple Cursors", description: "Enables multiple selections and cursors."),
		.init(key: "editor.keymap", title: "Keymap", description: "Plain, Vim, or Emacs editing commands."),
		.init(key: "editor.tab_groups", title: "Tab Groups", description: "Shares tabs across a window or keeps them per pane."),
		.init(key: "editor.wrap", title: "Wrap Mode", description: "No wrapping, soft wrapping, or hard wrapping."),
		.init(key: "editor.wrap_column", title: "Wrap Column", description: "Column used for hard wrapping."),
		.init(key: "editor.experimental.storage", title: "Editor Storage", description: "Text storage implementation for newly opened documents.", reloadBehavior: .restartRequired),
		.init(key: "theme.id", title: "Theme", description: "Color theme identifier."),
		.init(key: "theme.git.gutter.added", title: "Git Added Color", description: "Gutter color for added lines."),
		.init(key: "theme.git.gutter.modified", title: "Git Modified Color", description: "Gutter color for modified lines."),
		.init(key: "theme.git.gutter.removed", title: "Git Removed Color", description: "Gutter color for removed lines."),
		.init(key: "syntax.preload_grammars", title: "Syntax Grammar Preload", description: "Controls when syntax grammars are loaded."),
		.init(key: "terminal.font_size", title: "Terminal Font Size", description: "Point size for terminal text."),
		.init(key: "terminal.scrollback_lines", title: "Terminal Scrollback", description: "Maximum retained terminal lines."),
		.init(key: "find.uses_regex", title: "Find Uses Regex", description: "Uses regular expressions by default."),
		.init(key: "find.case_sensitive", title: "Find Case Sensitive", description: "Matches case by default."),
		.init(key: "find.whole_word", title: "Find Whole Word", description: "Matches complete words by default."),
		.init(key: "recovery.journal_enabled", title: "Recovery Journal", description: "Keeps local crash-recovery journals."),
		.init(key: "layout.sidebar_visible", title: "Show Sidebar", description: "Shows the workspace sidebar."),
		.init(key: "layout.sidebar_position", title: "Sidebar Position", description: "Places the sidebar at the leading or trailing edge."),
		.init(key: "layout.sidebar_width", title: "Sidebar Width", description: "Sidebar width in points before interface scale."),
		.init(key: "layout.tab_bar_visible", title: "Show Tab Bar", description: "Shows document and pane tab bars."),
		.init(key: "layout.status_bar_visible", title: "Show Status Bar", description: "Shows editor status information."),
		.init(key: "layout.interface_scale", title: "Interface Scale", description: "Scales supported editor-shell dimensions."),
		.init(key: "ui.font_scale", title: "UI Font Scale", description: "Scales first-party panel typography."),
		.init(key: "ui.density", title: "UI Density", description: "Compact, regular, or comfortable panel spacing."),
		.init(key: "ui.corner_radius", title: "UI Corner Radius", description: "Corner radius for configurable first-party panels."),
		.init(key: "ui.border_width", title: "UI Border Width", description: "Border width for configurable first-party panels."),
		.init(key: "ui.padding", title: "UI Padding", description: "Base padding for configurable first-party panels."),
		.init(key: "editor.language.<language>.font", title: "Language Font Override", description: "Overrides the editor font for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.font_size", title: "Language Font Size Override", description: "Overrides editor font size for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.font_rendering", title: "Language Font Rendering Override", description: "Overrides glyph rendering for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.line_numbers", title: "Language Line Numbers Override", description: "Overrides line-number visibility for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.tab_width", title: "Language Tab Width Override", description: "Overrides tab width for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.use_spaces", title: "Language Indent Override", description: "Overrides spaces-versus-tabs for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.auto_pairs", title: "Language Auto Pairs Override", description: "Overrides automatic pairs for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.smart_indent", title: "Language Smart Indent Override", description: "Overrides smart indentation for one language.", isLanguageTemplate: true),
		.init(key: "editor.language.<language>.multiple_selections", title: "Language Multiple Cursors Override", description: "Overrides multiple cursors for one language.", isLanguageTemplate: true),
	] + surfaceEntries

	private static let surfaceEntries: [Entry] = ItsySettings.UISettings.knownSurfaceIDs.flatMap { id in
		[
			.init(key: "ui.surface.\(id).width", title: "\(id) Width", description: "Default width for the \(id) panel."),
			.init(key: "ui.surface.\(id).height", title: "\(id) Height", description: "Default height for the \(id) panel."),
			.init(key: "ui.surface.\(id).row_height", title: "\(id) Row Height", description: "Row density for the \(id) panel."),
			.init(key: "ui.surface.\(id).input_font_size", title: "\(id) Input Font Size", description: "Input typography for the \(id) panel."),
			.init(key: "ui.surface.\(id).item_font_size", title: "\(id) Item Font Size", description: "Item typography for the \(id) panel."),
		]
	}

	public static var baseEntries: [Entry] {
		entries.filter { !$0.isLanguageTemplate && $0.isResettable }
	}

	public static func matching(_ query: String) -> [Entry] {
		let query = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		guard !query.isEmpty else {
			return entries
		}
		return entries.filter {
			$0.key.lowercased().contains(query)
				|| $0.title.lowercased().contains(query)
				|| $0.description.lowercased().contains(query)
		}
	}

	public static func effectiveValue(for key: String, in settings: ItsySettings) -> String {
		let settings = settings.normalized()
		if let value = surfaceValue(for: key, in: settings) { return value }
		return switch key {
		case "schema_version": String(ItsySettingsSchema.currentVersion)
		case "editor.font": settings.editor.font
		case "editor.font_size": number(settings.editor.fontSize)
		case "editor.font_rendering": settings.editor.fontRendering.rawValue
		case "editor.line_numbers": bool(settings.editor.lineNumbers)
		case "editor.line_number_mode": settings.editor.lineNumberMode.rawValue
		case "editor.tab_width": String(settings.editor.tabWidth)
		case "editor.use_spaces": bool(settings.editor.useSpaces)
		case "editor.auto_pairs": bool(settings.editor.autoPairs)
		case "editor.smart_indent": bool(settings.editor.smartIndent)
		case "editor.multiple_selections": bool(settings.editor.multipleSelections)
		case "editor.keymap": settings.editor.keymap.rawValue
		case "editor.tab_groups": settings.editor.tabGroups.rawValue
		case "editor.wrap": settings.editor.wrap.rawValue
		case "editor.wrap_column": String(settings.editor.wrapColumn)
		case "editor.experimental.storage": settings.editor.experimental.storage.rawValue
		case "theme.id": settings.theme.id
		case "theme.git.gutter.added": settings.theme.gitGutter.added
		case "theme.git.gutter.modified": settings.theme.gitGutter.modified
		case "theme.git.gutter.removed": settings.theme.gitGutter.removed
		case "syntax.preload_grammars": settings.syntax.preloadGrammars.rawValue
		case "terminal.font_size": number(settings.terminal.fontSize)
		case "terminal.scrollback_lines": String(settings.terminal.scrollbackLines)
		case "find.uses_regex": bool(settings.find.usesRegex)
		case "find.case_sensitive": bool(settings.find.isCaseSensitive)
		case "find.whole_word": bool(settings.find.matchesWholeWord)
		case "recovery.journal_enabled": bool(settings.recovery.journalEnabled)
		case "layout.sidebar_visible": bool(settings.layout.sidebarVisible)
		case "layout.sidebar_position": settings.layout.sidebarPosition.rawValue
		case "layout.sidebar_width": String(settings.layout.sidebarWidth)
		case "layout.tab_bar_visible": bool(settings.layout.tabBarVisible)
		case "layout.status_bar_visible": bool(settings.layout.statusBarVisible)
		case "layout.interface_scale": number(settings.layout.interfaceScale)
		case "ui.font_scale": number(settings.ui.fontScale)
		case "ui.density": settings.ui.density.rawValue
		case "ui.corner_radius": number(settings.ui.cornerRadius)
		case "ui.border_width": number(settings.ui.borderWidth)
		case "ui.padding": number(settings.ui.padding)
		default: "Language override template"
		}
	}

	@discardableResult public static func reset(_ key: String, in settings: inout ItsySettings) -> Bool {
		if resetSurfaceValue(for: key, in: &settings) { return true }
		let defaults = ItsySettings.default
		switch key {
		case "schema_version": return false
		case "editor.font": settings.editor.font = defaults.editor.font
		case "editor.font_size": settings.editor.fontSize = defaults.editor.fontSize
		case "editor.font_rendering": settings.editor.fontRendering = defaults.editor.fontRendering
		case "editor.line_numbers": settings.editor.lineNumbers = defaults.editor.lineNumbers
		case "editor.line_number_mode": settings.editor.lineNumberMode = defaults.editor.lineNumberMode
		case "editor.tab_width": settings.editor.tabWidth = defaults.editor.tabWidth
		case "editor.use_spaces": settings.editor.useSpaces = defaults.editor.useSpaces
		case "editor.auto_pairs": settings.editor.autoPairs = defaults.editor.autoPairs
		case "editor.smart_indent": settings.editor.smartIndent = defaults.editor.smartIndent
		case "editor.multiple_selections": settings.editor.multipleSelections = defaults.editor.multipleSelections
		case "editor.keymap": settings.editor.keymap = defaults.editor.keymap
		case "editor.tab_groups": settings.editor.tabGroups = defaults.editor.tabGroups
		case "editor.wrap": settings.editor.wrap = defaults.editor.wrap
		case "editor.wrap_column": settings.editor.wrapColumn = defaults.editor.wrapColumn
		case "editor.experimental.storage": settings.editor.experimental.storage = defaults.editor.experimental.storage
		case "theme.id": settings.theme.id = defaults.theme.id
		case "theme.git.gutter.added": settings.theme.gitGutter.added = defaults.theme.gitGutter.added
		case "theme.git.gutter.modified": settings.theme.gitGutter.modified = defaults.theme.gitGutter.modified
		case "theme.git.gutter.removed": settings.theme.gitGutter.removed = defaults.theme.gitGutter.removed
		case "syntax.preload_grammars": settings.syntax.preloadGrammars = defaults.syntax.preloadGrammars
		case "terminal.font_size": settings.terminal.fontSize = defaults.terminal.fontSize
		case "terminal.scrollback_lines": settings.terminal.scrollbackLines = defaults.terminal.scrollbackLines
		case "find.uses_regex": settings.find.usesRegex = defaults.find.usesRegex
		case "find.case_sensitive": settings.find.isCaseSensitive = defaults.find.isCaseSensitive
		case "find.whole_word": settings.find.matchesWholeWord = defaults.find.matchesWholeWord
		case "recovery.journal_enabled": settings.recovery.journalEnabled = defaults.recovery.journalEnabled
		case "layout.sidebar_visible": settings.layout.sidebarVisible = defaults.layout.sidebarVisible
		case "layout.sidebar_position": settings.layout.sidebarPosition = defaults.layout.sidebarPosition
		case "layout.sidebar_width": settings.layout.sidebarWidth = defaults.layout.sidebarWidth
		case "layout.tab_bar_visible": settings.layout.tabBarVisible = defaults.layout.tabBarVisible
		case "layout.status_bar_visible": settings.layout.statusBarVisible = defaults.layout.statusBarVisible
		case "layout.interface_scale": settings.layout.interfaceScale = defaults.layout.interfaceScale
		case "ui.font_scale": settings.ui.fontScale = defaults.ui.fontScale
		case "ui.density": settings.ui.density = defaults.ui.density
		case "ui.corner_radius": settings.ui.cornerRadius = defaults.ui.cornerRadius
		case "ui.border_width": settings.ui.borderWidth = defaults.ui.borderWidth
		case "ui.padding": settings.ui.padding = defaults.ui.padding
		default: return false
		}
		return true
	}

	public static func update(value rawValue: String, for key: String, in settings: inout ItsySettings) -> String? {
		let isSurface = surfaceKey(key) != nil
		guard isSurface || (entries.first(where: { $0.key == key }).map { $0.isResettable && !$0.isLanguageTemplate } == true) else {
			return "This catalog entry is read-only."
		}
		let rawValue = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !rawValue.isEmpty else {
			return "A value is required."
		}
		let literals = rawValue.hasPrefix("\"") ? [rawValue] : [rawValue, "\"\(escape(rawValue))\""]
		var warning: ItsySettingsWarning?
		for literal in literals {
			var parser = ItsySettingsParser(settings: settings)
			let result = parser.parse(tomlAssignment(key: key, literal: literal))
			if result.warnings.isEmpty {
				settings = result.settings.normalized()
				return nil
			}
			warning = result.warnings.first
		}
		return warning?.description ?? "Invalid value."
	}

	private static func surfaceKey(_ key: String) -> (String, String)? {
		let prefix = "ui.surface."
		guard key.hasPrefix(prefix) else { return nil }
		let suffix = key.dropFirst(prefix.count)
		guard let separator = suffix.lastIndex(of: ".") else { return nil }
		let id = String(suffix[..<separator])
		let property = String(suffix[suffix.index(after: separator)...])
		guard ItsySettings.UISettings.knownSurfaceIDs.contains(id), ["width", "height", "row_height", "input_font_size", "item_font_size"].contains(property) else { return nil }
		return (id, property)
	}

	private static func surfaceValue(for key: String, in settings: ItsySettings) -> String? {
		guard let (id, property) = surfaceKey(key) else { return nil }
		let surface = settings.ui.surface(id)
		let value: Double?
		switch property {
		case "width": value = surface.width
		case "height": value = surface.height
		case "row_height": value = surface.rowHeight
		case "input_font_size": value = surface.inputFontSize
		case "item_font_size": value = surface.itemFontSize
		default: value = nil
		}
		return value.map(number) ?? "Default"
	}

	private static func resetSurfaceValue(for key: String, in settings: inout ItsySettings) -> Bool {
		guard let (id, property) = surfaceKey(key) else { return false }
		var surface = settings.ui.surface(id)
		switch property {
		case "width": surface.width = nil
		case "height": surface.height = nil
		case "row_height": surface.rowHeight = nil
		case "input_font_size": surface.inputFontSize = nil
		case "item_font_size": surface.itemFontSize = nil
		default: return false
		}
		settings.ui.surfaces[id] = surface
		return true
	}

	private static func bool(_ value: Bool) -> String {
		value ? "true" : "false"
	}

	private static func number(_ value: Double) -> String {
		let rounded = (value * 100).rounded() / 100
		return String(rounded)
	}

	private static func tomlAssignment(key: String, literal: String) -> String {
		if key.hasPrefix("theme.") {
			return "[theme]\n\(key.dropFirst("theme.".count)) = \(literal)"
		}
		let components = key.split(separator: ".")
		guard components.count > 1 else {
			return "\(key) = \(literal)"
		}
		let section = components.dropLast().joined(separator: ".")
		return "[\(section)]\n\(components.last!) = \(literal)"
	}

	private static func escape(_ value: String) -> String {
		value
			.replacingOccurrences(of: "\\", with: "\\\\")
			.replacingOccurrences(of: "\"", with: "\\\"")
			.replacingOccurrences(of: "\n", with: "\\n")
			.replacingOccurrences(of: "\t", with: "\\t")
	}
}
