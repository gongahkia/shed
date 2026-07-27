import Foundation
import ItsyWorkbenchLayout

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

		public var id: String {
			key
		}

		public init(
			key: String,
			title: String,
			description: String,
			reloadBehavior: ReloadBehavior = .hotReload,
			isLanguageTemplate: Bool = false,
			isResettable: Bool = true
		) {
			self.key = key
			self.title = title
			self.description = description
			self.reloadBehavior = reloadBehavior
			self.isLanguageTemplate = isLanguageTemplate
			self.isResettable = isResettable
		}
	}

	public static let entries: [Entry] = [
		.init(
			key: "schema_version",
			title: "Schema Version",
			description: "Version used to validate settings.json.",
			isResettable: false
		),
		.init(key: "editor.font", title: "Editor Font", description: "Font family for editor text."),
		.init(key: "editor.font_size", title: "Editor Font Size", description: "Point size for editor text."),
		.init(key: "editor.font_rendering", title: "Font Rendering", description: "Glyph atlas rendering mode."),
		.init(key: "editor.line_numbers", title: "Line Numbers", description: "Shows line numbers in the editor gutter."),
		.init(
			key: "editor.line_number_mode",
			title: "Line Number Mode",
			description: "Off, absolute, or relative line numbers."
		),
		.init(key: "editor.tab_width", title: "Tab Width", description: "Columns inserted or displayed for a tab."),
		.init(
			key: "editor.use_spaces",
			title: "Indent Using Spaces",
			description: "Uses spaces instead of tab characters for indentation."
		),
		.init(key: "editor.detect_indentation", title: "Detect Indentation", description: "Detects indentation from the current document."),
		.init(key: "editor.auto_pairs", title: "Auto Pairs", description: "Inserts matching brackets and quotes."),
		.init(key: "editor.smart_indent", title: "Smart Indent", description: "Carries indentation into a new line."),
		.init(
			key: "editor.multiple_selections",
			title: "Multiple Cursors",
			description: "Enables multiple selections and cursors."
		),
		.init(key: "editor.keymap", title: "Keymap", description: "Plain, Vim, or Emacs editing commands."),
		.init(
			key: "editor.cursor_style",
			title: "Cursor Style",
			description: "Automatic uses a block cursor for Vim and Emacs; choose block or bar to override it."
		),
		.init(
			key: "editor.tab_groups",
			title: "Tab Groups",
			description: "Shares tabs across a window or keeps them per pane."
		),
		.init(key: "editor.wrap", title: "Wrap Mode", description: "No wrapping, soft wrapping, or hard wrapping."),
		.init(key: "editor.wrap_column", title: "Wrap Column", description: "Column used for hard wrapping."),
		.init(
			key: "editor.experimental.storage",
			title: "Editor Storage",
			description: "Text storage implementation for newly opened documents.",
			reloadBehavior: .restartRequired
		),
		.init(key: "theme.id", title: "Theme", description: "Color theme identifier."),
		.init(key: "theme.git.gutter.added", title: "Git Added Color", description: "Gutter color for added lines."),
		.init(key: "theme.git.gutter.modified", title: "Git Modified Color", description: "Gutter color for modified lines."),
		.init(key: "theme.git.gutter.removed", title: "Git Removed Color", description: "Gutter color for removed lines."),
		.init(
			key: "syntax.preload_grammars",
			title: "Syntax Grammar Preload",
			description: "Controls when syntax grammars are loaded."
		),
		.init(
			key: "terminal.font",
			title: "Terminal Font",
			description: "Terminal font family; unset inherits the editor font."
		),
		.init(key: "terminal.font_size", title: "Terminal Font Size", description: "Point size for terminal text."),
		.init(
			key: "terminal.scrollback_lines",
			title: "Terminal Scrollback",
			description: "Maximum retained terminal lines."
		),
		.init(
			key: "terminal.presentation",
			title: "Terminal Presentation",
			description: "Shows the terminal at the bottom of the editor or in a separate window."
		),
		.init(
			key: "git.presentation",
			title: "Git Presentation",
			description: "Shows Git Changes in the right sidebar or in a separate window."
		),
		.init(
			key: "git.auto_ignore_itsy",
			title: "Automatically Ignore .itsy",
			description: "Adds .itsy/ to the containing Git repository's .gitignore when a workspace opens."
		),
		.init(
			key: "debugger.presentation",
			title: "Debugger Presentation",
			description: "Shows the debugger Call Stack in the right sidebar or in a separate window."
		),
		.init(key: "find.uses_regex", title: "Find Uses Regex", description: "Uses regular expressions by default."),
		.init(key: "find.case_sensitive", title: "Find Case Sensitive", description: "Matches case by default."),
		.init(key: "find.whole_word", title: "Find Whole Word", description: "Matches complete words by default."),
		.init(
			key: "recovery.journal_enabled",
			title: "Recovery Journal",
			description: "Keeps local crash-recovery journals."
		),
		.init(
			key: "updates.automatically_check",
			title: "Automatically Check for Updates",
			description: "Checks for stable Itsy releases in the background."
		),
		.init(
			key: "lsp.catalog_automatically_check",
			title: "Automatically Check Language Server Catalog",
			description: "Checks the signed language-server catalog without downloading updates."
		),
		.init(key: "workbench.profile", title: "Workbench Profile", description: "Workbench, Focus, or Review layout profile."),
		.init(key: "workbench.file_tree", title: "File Tree Visibility", description: "Automatic, visible, or hidden in the active workbench profile."),
		.init(key: "workbench.terminal", title: "Terminal Visibility", description: "Automatic, visible, or hidden in the active workbench profile."),
		.init(key: "workbench.git", title: "Git Visibility", description: "Automatic, visible, or hidden in the active workbench profile."),
		.init(key: "layout.sidebar_visible", title: "Show Sidebar", description: "Shows the workspace sidebar."),
		.init(
			key: "layout.sidebar_position",
			title: "Sidebar Position",
			description: "Places the sidebar at the leading or trailing edge."
		),
		.init(
			key: "layout.sidebar_width",
			title: "Sidebar Width",
			description: "Sidebar width in points before interface scale."
		),
		.init(key: "layout.tab_bar_visible", title: "Show Tab Bar", description: "Shows document and pane tab bars."),
		.init(key: "layout.status_bar_visible", title: "Show Status Bar", description: "Shows editor status information."),
		.init(
			key: "layout.interface_scale",
			title: "Interface Scale",
			description: "Scales supported editor-shell dimensions."
		),
		.init(key: "ui.font_scale", title: "UI Font Scale", description: "Scales first-party panel typography."),
		.init(key: "ui.density", title: "UI Density", description: "Compact, regular, or comfortable panel spacing."),
		.init(
			key: "ui.corner_radius",
			title: "UI Corner Radius",
			description: "Corner radius for configurable first-party panels."
		),
		.init(
			key: "ui.border_width",
			title: "UI Border Width",
			description: "Border width for configurable first-party panels."
		),
		.init(key: "ui.padding", title: "UI Padding", description: "Base padding for configurable first-party panels."),
		.init(
			key: "ui.notification_position",
			title: "Notification Position",
			description: "Places in-app notifications at the bottom-right or top-right of the editor."
		),
		.init(
			key: "lsp.<language>.mode",
			title: "Language Server Mode",
			description: "Auto, system-only, managed-only, or disabled for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.font",
			title: "Language Font Override",
			description: "Overrides the editor font for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.font_size",
			title: "Language Font Size Override",
			description: "Overrides editor font size for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.font_rendering",
			title: "Language Font Rendering Override",
			description: "Overrides glyph rendering for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.line_numbers",
			title: "Language Line Numbers Override",
			description: "Overrides line-number visibility for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.tab_width",
			title: "Language Tab Width Override",
			description: "Overrides tab width for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.use_spaces",
			title: "Language Indent Override",
			description: "Overrides spaces-versus-tabs for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.detect_indentation",
			title: "Language Indentation Detection Override",
			description: "Overrides indentation detection for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.auto_pairs",
			title: "Language Auto Pairs Override",
			description: "Overrides automatic pairs for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.smart_indent",
			title: "Language Smart Indent Override",
			description: "Overrides smart indentation for one language.",
			isLanguageTemplate: true
		),
		.init(
			key: "editor.language.<language>.multiple_selections",
			title: "Language Multiple Cursors Override",
			description: "Overrides multiple cursors for one language.",
			isLanguageTemplate: true
		),
	] + surfaceEntries

	private static let surfaceEntries: [Entry] = ItsySettings.UISettings.knownSurfaceIDs.flatMap { id in
		[
			.init(key: "ui.surface.\(id).width", title: "\(id) Width", description: "Default width for the \(id) panel."),
			.init(key: "ui.surface.\(id).height", title: "\(id) Height", description: "Default height for the \(id) panel."),
			.init(
				key: "ui.surface.\(id).row_height",
				title: "\(id) Row Height",
				description: "Row density for the \(id) panel."
			),
			.init(
				key: "ui.surface.\(id).input_font_size",
				title: "\(id) Input Font Size",
				description: "Input typography for the \(id) panel."
			),
			.init(
				key: "ui.surface.\(id).item_font_size",
				title: "\(id) Item Font Size",
				description: "Item typography for the \(id) panel."
			),
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
		if let value = surfaceValue(for: key, in: settings) {
			return value
		}
		return switch key {
		case "schema_version": String(ItsySettingsSchema.currentVersion)
		case "editor.font": settings.editor.font
		case "editor.font_size": number(settings.editor.fontSize)
		case "editor.font_rendering": settings.editor.fontRendering.rawValue
		case "editor.line_numbers": bool(settings.editor.lineNumbers)
		case "editor.line_number_mode": settings.editor.lineNumberMode.rawValue
		case "editor.tab_width": String(settings.editor.tabWidth)
		case "editor.use_spaces": bool(settings.editor.useSpaces)
		case "editor.detect_indentation": bool(settings.editor.detectIndentation)
		case "editor.auto_pairs": bool(settings.editor.autoPairs)
		case "editor.smart_indent": bool(settings.editor.smartIndent)
		case "editor.multiple_selections": bool(settings.editor.multipleSelections)
		case "editor.keymap": settings.editor.keymap.rawValue
		case "editor.cursor_style": settings.editor.cursorStyle.rawValue
		case "editor.tab_groups": settings.editor.tabGroups.rawValue
		case "editor.wrap": settings.editor.wrap.rawValue
		case "editor.wrap_column": String(settings.editor.wrapColumn)
		case "editor.experimental.storage": settings.editor.experimental.storage.rawValue
		case "theme.id": settings.theme.id
		case "theme.git.gutter.added": settings.theme.gitGutter.added
		case "theme.git.gutter.modified": settings.theme.gitGutter.modified
		case "theme.git.gutter.removed": settings.theme.gitGutter.removed
		case "syntax.preload_grammars": settings.syntax.preloadGrammars.rawValue
		case "terminal.font": settings.terminal.font ?? "Inherited: \(settings.editor.font)"
		case "terminal.font_size": number(settings.terminal.fontSize)
		case "terminal.scrollback_lines": String(settings.terminal.scrollbackLines)
		case "terminal.presentation": settings.terminal.presentation.rawValue
		case "git.presentation": settings.git.presentation.rawValue
		case "git.auto_ignore_itsy": bool(settings.git.autoIgnoreItsy)
		case "debugger.presentation": settings.debugger.presentation.rawValue
		case "find.uses_regex": bool(settings.find.usesRegex)
		case "find.case_sensitive": bool(settings.find.isCaseSensitive)
		case "find.whole_word": bool(settings.find.matchesWholeWord)
		case "recovery.journal_enabled": bool(settings.recovery.journalEnabled)
		case "updates.automatically_check": bool(settings.updates.automaticallyCheck)
		case "lsp.catalog_automatically_check": bool(settings.lsp.catalogAutomaticallyCheck)
		case "workbench.profile": settings.workbench.profile.rawValue
		case "workbench.file_tree": settings.workbench.fileTree.rawValue
		case "workbench.terminal": settings.workbench.terminal.rawValue
		case "workbench.git": settings.workbench.git.rawValue
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
		case "ui.notification_position": settings.ui.notificationPosition.rawValue
		default: "Language override template"
		}
	}

	@discardableResult public static func reset(_ key: String, in settings: inout ItsySettings) -> Bool {
		if resetSurfaceValue(for: key, in: &settings) {
			return true
		}
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
		case "editor.detect_indentation": settings.editor.detectIndentation = defaults.editor.detectIndentation
		case "editor.auto_pairs": settings.editor.autoPairs = defaults.editor.autoPairs
		case "editor.smart_indent": settings.editor.smartIndent = defaults.editor.smartIndent
		case "editor.multiple_selections": settings.editor.multipleSelections = defaults.editor.multipleSelections
		case "editor.keymap": settings.editor.keymap = defaults.editor.keymap
		case "editor.cursor_style": settings.editor.cursorStyle = defaults.editor.cursorStyle
		case "editor.tab_groups": settings.editor.tabGroups = defaults.editor.tabGroups
		case "editor.wrap": settings.editor.wrap = defaults.editor.wrap
		case "editor.wrap_column": settings.editor.wrapColumn = defaults.editor.wrapColumn
		case "editor.experimental.storage": settings.editor.experimental.storage = defaults.editor.experimental.storage
		case "theme.id": settings.theme.id = defaults.theme.id
		case "theme.git.gutter.added": settings.theme.gitGutter.added = defaults.theme.gitGutter.added
		case "theme.git.gutter.modified": settings.theme.gitGutter.modified = defaults.theme.gitGutter.modified
		case "theme.git.gutter.removed": settings.theme.gitGutter.removed = defaults.theme.gitGutter.removed
		case "syntax.preload_grammars": settings.syntax.preloadGrammars = defaults.syntax.preloadGrammars
		case "terminal.font": settings.terminal.font = defaults.terminal.font
		case "terminal.font_size": settings.terminal.fontSize = defaults.terminal.fontSize
		case "terminal.scrollback_lines": settings.terminal.scrollbackLines = defaults.terminal.scrollbackLines
		case "terminal.presentation": settings.terminal.presentation = defaults.terminal.presentation
		case "git.presentation": settings.git.presentation = defaults.git.presentation
		case "git.auto_ignore_itsy": settings.git.autoIgnoreItsy = defaults.git.autoIgnoreItsy
		case "debugger.presentation": settings.debugger.presentation = defaults.debugger.presentation
		case "find.uses_regex": settings.find.usesRegex = defaults.find.usesRegex
		case "find.case_sensitive": settings.find.isCaseSensitive = defaults.find.isCaseSensitive
		case "find.whole_word": settings.find.matchesWholeWord = defaults.find.matchesWholeWord
		case "recovery.journal_enabled": settings.recovery.journalEnabled = defaults.recovery.journalEnabled
		case "updates.automatically_check": settings.updates.automaticallyCheck = defaults.updates.automaticallyCheck
		case "lsp.catalog_automatically_check": settings.lsp.catalogAutomaticallyCheck = defaults.lsp.catalogAutomaticallyCheck
		case "workbench.profile": settings.workbench.profile = defaults.workbench.profile
		case "workbench.file_tree": settings.workbench.fileTree = defaults.workbench.fileTree
		case "workbench.terminal": settings.workbench.terminal = defaults.workbench.terminal
		case "workbench.git": settings.workbench.git = defaults.workbench.git
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
		case "ui.notification_position": settings.ui.notificationPosition = defaults.ui.notificationPosition
		default: return false
		}
		return true
	}

	public static func update(value rawValue: String, for key: String, in settings: inout ItsySettings) -> String? {
		let isSurface = surfaceKey(key) != nil
		guard isSurface ||
			(entries.first(where: { $0.key == key }).map { $0.isResettable && !$0.isLanguageTemplate } == true)
		else {
			return "This catalog entry is read-only."
		}
		let raw = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !raw.isEmpty else {
			return "A value is required."
		}
		let value = unquote(raw)
		var updated = settings
		let boolean = boolValue(value)
		let integer = Int(value)
		let number = Double(value)
		switch key {
		case "editor.font": updated.editor.font = value
		case "editor.font_size": guard let number else { return "A number is required." }; updated.editor.fontSize = number
		case "editor.font_rendering": guard let mode = ItsySettings.FontRenderingMode(rawValue: value.lowercased()) else { return "Invalid font rendering mode." }; updated.editor.fontRendering = mode
		case "editor.line_numbers": guard let boolean else { return "A boolean is required." }; updated.editor.lineNumbers = boolean; updated.editor.lineNumberMode = boolean ? .absolute : .off
		case "editor.line_number_mode": guard let mode = ItsySettings.LineNumberMode(rawValue: value.lowercased()) else { return "Invalid line number mode." }; updated.editor.lineNumberMode = mode; updated.editor.lineNumbers = mode != .off
		case "editor.tab_width": guard let integer else { return "An integer is required." }; updated.editor.tabWidth = integer
		case "editor.use_spaces": guard let boolean else { return "A boolean is required." }; updated.editor.useSpaces = boolean
		case "editor.detect_indentation": guard let boolean else { return "A boolean is required." }; updated.editor.detectIndentation = boolean
		case "editor.auto_pairs": guard let boolean else { return "A boolean is required." }; updated.editor.autoPairs = boolean
		case "editor.smart_indent": guard let boolean else { return "A boolean is required." }; updated.editor.smartIndent = boolean
		case "editor.multiple_selections": guard let boolean else { return "A boolean is required." }; updated.editor.multipleSelections = boolean
		case "editor.keymap": guard let mode = ItsySettings.KeymapMode(rawValue: value.lowercased()) else { return "Invalid keymap." }; updated.editor.keymap = mode
		case "editor.cursor_style":
			if value.lowercased() == "immediate" { updated.editor.cursorStyle = .bar }
			else if let style = ItsySettings.CursorStyle(rawValue: value.lowercased()) { updated.editor.cursorStyle = style }
			else { return "Invalid cursor style." }
		case "editor.tab_groups": guard let scope = ItsySettings.TabGroupScope(rawValue: value.lowercased()) else { return "Invalid tab group scope." }; updated.editor.tabGroups = scope
		case "editor.wrap": guard let mode = ItsySettings.WrapMode(rawValue: value.lowercased()) else { return "Invalid wrap mode." }; updated.editor.wrap = mode
		case "editor.wrap_column": guard let integer else { return "An integer is required." }; updated.editor.wrapColumn = integer
		case "editor.experimental.storage": guard let storage = ItsySettings.EditorStorage(rawValue: value.lowercased()) else { return "Invalid editor storage." }; updated.editor.experimental.storage = storage
		case "theme.id": updated.theme.id = value
		case "theme.git.gutter.added": updated.theme.gitGutter.added = value
		case "theme.git.gutter.modified": updated.theme.gitGutter.modified = value
		case "theme.git.gutter.removed": updated.theme.gitGutter.removed = value
		case "syntax.preload_grammars": guard let mode = ItsySettings.SyntaxPreloadGrammars(rawValue: value.lowercased()) else { return "Invalid syntax preload mode." }; updated.syntax.preloadGrammars = mode
		case "terminal.font": updated.terminal.font = value
		case "terminal.font_size": guard let number else { return "A number is required." }; updated.terminal.fontSize = number
		case "terminal.scrollback_lines": guard let integer else { return "An integer is required." }; updated.terminal.scrollbackLines = integer
		case "terminal.presentation": guard let mode = ItsySettings.TerminalSettings.Presentation(rawValue: value.lowercased()) else { return "Invalid terminal presentation." }; updated.terminal.presentation = mode
		case "git.presentation": guard let mode = ItsySettings.GitSettings.Presentation(rawValue: value.lowercased()) else { return "Invalid Git presentation." }; updated.git.presentation = mode
		case "git.auto_ignore_itsy": guard let boolean else { return "A boolean is required." }; updated.git.autoIgnoreItsy = boolean
		case "debugger.presentation": guard let mode = ItsySettings.DebuggerSettings.Presentation(rawValue: value.lowercased()) else { return "Invalid debugger presentation." }; updated.debugger.presentation = mode
		case "find.uses_regex": guard let boolean else { return "A boolean is required." }; updated.find.usesRegex = boolean
		case "find.case_sensitive": guard let boolean else { return "A boolean is required." }; updated.find.isCaseSensitive = boolean
		case "find.whole_word": guard let boolean else { return "A boolean is required." }; updated.find.matchesWholeWord = boolean
		case "recovery.journal_enabled": guard let boolean else { return "A boolean is required." }; updated.recovery.journalEnabled = boolean
		case "updates.automatically_check": guard let boolean else { return "A boolean is required." }; updated.updates.automaticallyCheck = boolean
		case "lsp.catalog_automatically_check": guard let boolean else { return "A boolean is required." }; updated.lsp.catalogAutomaticallyCheck = boolean
		case "workbench.profile": guard let profile = WorkbenchProfile(rawValue: value.lowercased()) else { return "Invalid workbench profile." }; updated.workbench.profile = profile
		case "workbench.file_tree": guard let visibility = WorkbenchVisibility(rawValue: value.lowercased()) else { return "Invalid workbench visibility." }; updated.workbench.fileTree = visibility
		case "workbench.terminal": guard let visibility = WorkbenchVisibility(rawValue: value.lowercased()) else { return "Invalid workbench visibility." }; updated.workbench.terminal = visibility
		case "workbench.git": guard let visibility = WorkbenchVisibility(rawValue: value.lowercased()) else { return "Invalid workbench visibility." }; updated.workbench.git = visibility
		case "layout.sidebar_visible": guard let boolean else { return "A boolean is required." }; updated.layout.sidebarVisible = boolean
		case "layout.sidebar_position": guard let position = ItsySettings.SidebarPosition(rawValue: value.lowercased()) else { return "Invalid sidebar position." }; updated.layout.sidebarPosition = position
		case "layout.sidebar_width": guard let integer else { return "An integer is required." }; updated.layout.sidebarWidth = integer
		case "layout.tab_bar_visible": guard let boolean else { return "A boolean is required." }; updated.layout.tabBarVisible = boolean
		case "layout.status_bar_visible": guard let boolean else { return "A boolean is required." }; updated.layout.statusBarVisible = boolean
		case "layout.interface_scale": guard let number else { return "A number is required." }; updated.layout.interfaceScale = number
		case "ui.font_scale": guard let number else { return "A number is required." }; updated.ui.fontScale = number
		case "ui.density": guard let density = ItsySettings.UIDensity(rawValue: value.lowercased()) else { return "Invalid UI density." }; updated.ui.density = density
		case "ui.corner_radius": guard let number else { return "A number is required." }; updated.ui.cornerRadius = number
		case "ui.border_width": guard let number else { return "A number is required." }; updated.ui.borderWidth = number
		case "ui.padding": guard let number else { return "A number is required." }; updated.ui.padding = number
		case "ui.notification_position": guard let position = ItsySettings.UINotificationPosition(rawValue: value.lowercased()) else { return "Invalid notification position." }; updated.ui.notificationPosition = position
		default:
			guard let (id, property) = surfaceKey(key), let number else { return "Invalid setting value." }
			var surface = updated.ui.surface(id)
			switch property {
			case "width": surface.width = number
			case "height": surface.height = number
			case "row_height": surface.rowHeight = number
			case "input_font_size": surface.inputFontSize = number
			case "item_font_size": surface.itemFontSize = number
			default: return "Invalid setting value."
			}
			updated.ui.surfaces[id] = surface
		}
		guard updated == updated.normalized() else { return "Value is outside the supported range." }
		settings = updated
		return nil
	}

	private static func surfaceKey(_ key: String) -> (String, String)? {
		let prefix = "ui.surface."
		guard key.hasPrefix(prefix) else { return nil }
		let suffix = key.dropFirst(prefix.count)
		guard let separator = suffix.lastIndex(of: ".") else { return nil }
		let id = String(suffix[..<separator])
		let property = String(suffix[suffix.index(after: separator)...])
		guard ItsySettings.UISettings.knownSurfaceIDs.contains(id), [
			"width",
			"height",
			"row_height",
			"input_font_size",
			"item_font_size",
		].contains(property) else { return nil }
		return (id, property)
	}

	private static func surfaceValue(for key: String, in settings: ItsySettings) -> String? {
		guard let (id, property) = surfaceKey(key) else { return nil }
		let surface = settings.ui.surface(id)
		let value: Double? = switch property {
		case "width": surface.width
		case "height": surface.height
		case "row_height": surface.rowHeight
		case "input_font_size": surface.inputFontSize
		case "item_font_size": surface.itemFontSize
		default: nil
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

	private static func boolValue(_ value: String) -> Bool? {
		switch value.lowercased() {
		case "true": true
		case "false": false
		default: nil
		}
	}

	private static func unquote(_ value: String) -> String {
		guard value.count >= 2, value.first == "\"", value.last == "\"" else { return value }
		return String(value.dropFirst().dropLast())
	}
}
