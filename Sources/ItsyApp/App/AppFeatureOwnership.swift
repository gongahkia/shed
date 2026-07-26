import Foundation

enum AppFeatureOwnership {
	enum Kind: String, Sendable {
		case compositionRoot
		case feature
		case crossCutting
	}

	struct Boundary: Equatable, Sendable {
		let sourceRoot: String
		let kind: Kind
		let owner: String
	}

	static let boundaries: [Boundary] = [
		.init(sourceRoot: "App", kind: .compositionRoot, owner: "application lifecycle and composition"),
		.init(sourceRoot: "Banners", kind: .crossCutting, owner: "transient application notices"),
		.init(sourceRoot: "Bench", kind: .crossCutting, owner: "application benchmark instrumentation"),
		.init(sourceRoot: "CodeActions", kind: .feature, owner: "LSP code actions"),
		.init(sourceRoot: "Completion", kind: .feature, owner: "completion and snippets"),
		.init(sourceRoot: "Debugger", kind: .feature, owner: "debugger presentation"),
		.init(sourceRoot: "Documents", kind: .feature, owner: "document lifecycle"),
		.init(sourceRoot: "Extensions", kind: .feature, owner: "extension presentation"),
		.init(sourceRoot: "FileTree", kind: .feature, owner: "workspace file tree"),
		.init(sourceRoot: "Find", kind: .feature, owner: "in-document find"),
		.init(sourceRoot: "Git", kind: .feature, owner: "local Git workflows"),
		.init(sourceRoot: "GitHub", kind: .feature, owner: "GitHub CLI workflows"),
		.init(sourceRoot: "Hover", kind: .feature, owner: "LSP hover"),
		.init(sourceRoot: "LSP", kind: .feature, owner: "LSP presentation state"),
		.init(sourceRoot: "Menu", kind: .crossCutting, owner: "main menu presentation"),
		.init(sourceRoot: "Outline", kind: .feature, owner: "document outline"),
		.init(sourceRoot: "Palette", kind: .feature, owner: "command and navigation palette"),
		.init(sourceRoot: "Problems", kind: .feature, owner: "workspace problem presentation"),
		.init(sourceRoot: "ProjectFind", kind: .feature, owner: "workspace search"),
		.init(sourceRoot: "References", kind: .feature, owner: "LSP references"),
		.init(sourceRoot: "Rename", kind: .feature, owner: "LSP rename"),
		.init(sourceRoot: "Settings", kind: .feature, owner: "settings presentation"),
		.init(sourceRoot: "Signature", kind: .feature, owner: "LSP signature help"),
		.init(sourceRoot: "Status", kind: .crossCutting, owner: "integration status"),
		.init(sourceRoot: "Tasks", kind: .feature, owner: "workspace tasks"),
		.init(sourceRoot: "Terminal", kind: .feature, owner: "terminal presentation"),
		.init(sourceRoot: "Theme", kind: .crossCutting, owner: "application appearance"),
		.init(sourceRoot: "Undo", kind: .feature, owner: "undo history presentation"),
		.init(sourceRoot: "Windows", kind: .compositionRoot, owner: "editor window and pane composition"),
	]

	static func boundary(for sourceRoot: String) -> Boundary? {
		boundaries.first { $0.sourceRoot == sourceRoot }
	}
}
