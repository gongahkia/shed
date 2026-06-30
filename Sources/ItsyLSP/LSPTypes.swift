import Foundation

public enum LSPMethod {
	public static let initialize = "initialize"
	public static let initialized = "initialized"
	public static let shutdown = "shutdown"
	public static let exit = "exit"
	public static let textDocumentDidOpen = "textDocument/didOpen"
	public static let textDocumentDidChange = "textDocument/didChange"
	public static let textDocumentDidClose = "textDocument/didClose"
	public static let textDocumentPublishDiagnostics = "textDocument/publishDiagnostics"
	public static let textDocumentCompletion = "textDocument/completion"
	public static let completionItemResolve = "completionItem/resolve"
	public static let textDocumentHover = "textDocument/hover"
	public static let textDocumentDefinition = "textDocument/definition"
	public static let textDocumentReferences = "textDocument/references"
	public static let textDocumentRename = "textDocument/rename"
	public static let textDocumentCodeAction = "textDocument/codeAction"
	public static let textDocumentFormatting = "textDocument/formatting"
}

public struct LSPPosition: Codable, Equatable, Sendable {
	public var line: Int
	public var character: Int

	public init(line: Int, character: Int) {
		self.line = line
		self.character = character
	}
}

public struct LSPRange: Codable, Equatable, Sendable {
	public var start: LSPPosition
	public var end: LSPPosition

	public init(start: LSPPosition, end: LSPPosition) {
		self.start = start
		self.end = end
	}
}

public struct LSPTextDocumentIdentifier: Codable, Equatable, Sendable {
	public var uri: String

	public init(uri: String) {
		self.uri = uri
	}
}

public struct LSPVersionedTextDocumentIdentifier: Codable, Equatable, Sendable {
	public var uri: String
	public var version: Int?

	public init(uri: String, version: Int?) {
		self.uri = uri
		self.version = version
	}
}

public struct LSPTextDocumentItem: Codable, Equatable, Sendable {
	public var uri: String
	public var languageId: String
	public var version: Int
	public var text: String

	public init(uri: String, languageId: String, version: Int, text: String) {
		self.uri = uri
		self.languageId = languageId
		self.version = version
		self.text = text
	}
}

public struct LSPDidOpenTextDocumentParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentItem

	public init(textDocument: LSPTextDocumentItem) {
		self.textDocument = textDocument
	}
}

public struct LSPTextDocumentContentChangeEvent: Codable, Equatable, Sendable {
	public var range: LSPRange?
	public var rangeLength: Int?
	public var text: String

	public init(range: LSPRange? = nil, rangeLength: Int? = nil, text: String) {
		self.range = range
		self.rangeLength = rangeLength
		self.text = text
	}
}

public struct LSPDidChangeTextDocumentParams: Codable, Equatable, Sendable {
	public var textDocument: LSPVersionedTextDocumentIdentifier
	public var contentChanges: [LSPTextDocumentContentChangeEvent]

	public init(textDocument: LSPVersionedTextDocumentIdentifier, contentChanges: [LSPTextDocumentContentChangeEvent]) {
		self.textDocument = textDocument
		self.contentChanges = contentChanges
	}
}

public struct LSPDidCloseTextDocumentParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier

	public init(textDocument: LSPTextDocumentIdentifier) {
		self.textDocument = textDocument
	}
}

public enum LSPCompletionTriggerKind: Int, Codable, Equatable, Sendable {
	case invoked = 1
	case triggerCharacter = 2
	case triggerForIncompleteCompletions = 3
}

public struct LSPCompletionContext: Codable, Equatable, Sendable {
	public var triggerKind: LSPCompletionTriggerKind
	public var triggerCharacter: String?

	public init(triggerKind: LSPCompletionTriggerKind, triggerCharacter: String? = nil) {
		self.triggerKind = triggerKind
		self.triggerCharacter = triggerCharacter
	}
}

public struct LSPCompletionParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition
	public var context: LSPCompletionContext?

	public init(
		textDocument: LSPTextDocumentIdentifier,
		position: LSPPosition,
		context: LSPCompletionContext? = nil
	) {
		self.textDocument = textDocument
		self.position = position
		self.context = context
	}
}

public struct LSPTextDocumentPositionParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition

	public init(textDocument: LSPTextDocumentIdentifier, position: LSPPosition) {
		self.textDocument = textDocument
		self.position = position
	}
}

public typealias LSPHoverParams = LSPTextDocumentPositionParams

public enum LSPInsertTextFormat: Int, Codable, Equatable, Sendable {
	case plainText = 1
	case snippet = 2
}

public struct LSPCompletionItem: Codable, Equatable, Sendable {
	public var label: String
	public var detail: String?
	public var documentation: LSPAny?
	public var sortText: String?
	public var filterText: String?
	public var insertText: String?
	public var insertTextFormat: LSPInsertTextFormat?
	public var textEdit: LSPTextEdit?
	public var data: LSPAny?

	public init(
		label: String,
		detail: String? = nil,
		documentation: LSPAny? = nil,
		sortText: String? = nil,
		filterText: String? = nil,
		insertText: String? = nil,
		insertTextFormat: LSPInsertTextFormat? = nil,
		textEdit: LSPTextEdit? = nil,
		data: LSPAny? = nil
	) {
		self.label = label
		self.detail = detail
		self.documentation = documentation
		self.sortText = sortText
		self.filterText = filterText
		self.insertText = insertText
		self.insertTextFormat = insertTextFormat
		self.textEdit = textEdit
		self.data = data
	}

	public init(
		resolveResult result: LSPAny?,
		encoder: JSONEncoder = JSONEncoder(),
		decoder: JSONDecoder = JSONDecoder()
	) throws {
		let data = try encoder.encode(result ?? .null)
		self = try decoder.decode(LSPCompletionItem.self, from: data)
	}

	public func mergingResolvedFields(from resolved: LSPCompletionItem) -> LSPCompletionItem {
		var item = resolved
		if let data {
			item.data = data
		}
		return item
	}
}

public struct LSPCompletionList: Codable, Equatable, Sendable {
	public var isIncomplete: Bool
	public var items: [LSPCompletionItem]

	public init(isIncomplete: Bool, items: [LSPCompletionItem]) {
		self.isIncomplete = isIncomplete
		self.items = items
	}
}

public enum LSPMarkupKind: String, Codable, Equatable, Sendable {
	case plaintext
	case markdown
}

public struct LSPMarkupContent: Codable, Equatable, Sendable {
	public var kind: LSPMarkupKind
	public var value: String

	public init(kind: LSPMarkupKind, value: String) {
		self.kind = kind
		self.value = value
	}
}

public enum LSPMarkedString: Equatable, Sendable {
	case string(String)
	case languageString(language: String, value: String)

	private enum CodingKeys: String, CodingKey {
		case language
		case value
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(String.self) {
			self = .string(value)
			return
		}
		let object = try decoder.container(keyedBy: CodingKeys.self)
		self = .languageString(
			language: try object.decode(String.self, forKey: .language),
			value: try object.decode(String.self, forKey: .value)
		)
	}

	public func encode(to encoder: Encoder) throws {
		switch self {
		case let .string(value):
			var container = encoder.singleValueContainer()
			try container.encode(value)
		case let .languageString(language, value):
			var container = encoder.container(keyedBy: CodingKeys.self)
			try container.encode(language, forKey: .language)
			try container.encode(value, forKey: .value)
		}
	}
}

extension LSPMarkedString: Codable {}

public enum LSPHoverContents: Equatable, Sendable {
	case markup(LSPMarkupContent)
	case markedStrings([LSPMarkedString])

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let markup = try? container.decode(LSPMarkupContent.self) {
			self = .markup(markup)
			return
		}
		if let items = try? container.decode([LSPMarkedString].self) {
			self = .markedStrings(items)
			return
		}
		self = .markedStrings([try container.decode(LSPMarkedString.self)])
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .markup(markup):
			try container.encode(markup)
		case let .markedStrings(items):
			if items.count == 1, let item = items.first {
				try container.encode(item)
			} else {
				try container.encode(items)
			}
		}
	}
}

extension LSPHoverContents: Codable {}

public struct LSPHover: Codable, Equatable, Sendable {
	public var contents: LSPHoverContents
	public var range: LSPRange?

	public init(contents: LSPHoverContents, range: LSPRange? = nil) {
		self.contents = contents
		self.range = range
	}
}

public enum LSPHoverResult: Equatable, Sendable {
	case hover(LSPHover)
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let hover = try? decoder.decode(LSPHover.self, from: data) {
			self = .hover(hover)
			return
		}
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var hover: LSPHover? {
		guard case let .hover(value) = self else {
			return nil
		}
		return value
	}
}

public enum LSPCompletionResult: Equatable, Sendable {
	case list(LSPCompletionList)
	case items([LSPCompletionItem])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let list = try? decoder.decode(LSPCompletionList.self, from: data) {
			self = .list(list)
			return
		}
		if let items = try? decoder.decode([LSPCompletionItem].self, from: data) {
			self = items.isEmpty ? .none : .items(items)
			return
		}
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var items: [LSPCompletionItem] {
		switch self {
		case let .list(list):
			return list.items
		case let .items(items):
			return items
		case .none:
			return []
		}
	}

	public var isIncomplete: Bool {
		guard case let .list(list) = self else {
			return false
		}
		return list.isIncomplete
	}
}

private struct LSPNull: Codable, Equatable {
	init() {}

	init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		guard container.decodeNil() else {
			throw DecodingError.typeMismatch(
				LSPNull.self,
				DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "Expected null")
			)
		}
	}
}

public struct LSPCompletionOptions: Codable, Equatable, Sendable {
	public var triggerCharacters: [String]?
	public var resolveProvider: Bool?

	public init(triggerCharacters: [String]? = nil, resolveProvider: Bool? = nil) {
		self.triggerCharacters = triggerCharacters
		self.resolveProvider = resolveProvider
	}
}

public struct LSPServerCapabilities: Codable, Equatable, Sendable {
	public var completionProvider: LSPCompletionOptions?

	public init(completionProvider: LSPCompletionOptions? = nil) {
		self.completionProvider = completionProvider
	}
}

public struct LSPInitializeResult: Codable, Equatable, Sendable {
	public var capabilities: LSPServerCapabilities

	public init(capabilities: LSPServerCapabilities) {
		self.capabilities = capabilities
	}

	public init(result: LSPAny, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result)
		self = try decoder.decode(LSPInitializeResult.self, from: data)
	}
}

public enum LSPSymbolKind: Int, Codable, Equatable, Sendable {
	case file = 1
	case module = 2
	case namespace = 3
	case package = 4
	case `class` = 5
	case method = 6
	case property = 7
	case field = 8
	case constructor = 9
	case `enum` = 10
	case interface = 11
	case function = 12
	case variable = 13
	case constant = 14
	case string = 15
	case number = 16
	case boolean = 17
	case array = 18
	case object = 19
	case key = 20
	case null = 21
	case enumMember = 22
	case `struct` = 23
	case event = 24
	case `operator` = 25
	case typeParameter = 26
}

public struct LSPDocumentSymbol: Codable, Equatable, Sendable {
	public var name: String
	public var detail: String?
	public var kind: LSPSymbolKind
	public var range: LSPRange
	public var selectionRange: LSPRange
	public var children: [LSPDocumentSymbol]?

	public init(
		name: String,
		detail: String? = nil,
		kind: LSPSymbolKind,
		range: LSPRange,
		selectionRange: LSPRange,
		children: [LSPDocumentSymbol]? = nil
	) {
		self.name = name
		self.detail = detail
		self.kind = kind
		self.range = range
		self.selectionRange = selectionRange
		self.children = children
	}
}

public struct LSPSymbolInformation: Codable, Equatable, Sendable {
	public var name: String
	public var kind: LSPSymbolKind
	public var location: LSPLocation
	public var containerName: String?

	public init(name: String, kind: LSPSymbolKind, location: LSPLocation, containerName: String? = nil) {
		self.name = name
		self.kind = kind
		self.location = location
		self.containerName = containerName
	}
}

public struct LSPCommand: Codable, Equatable, Sendable {
	public var title: String
	public var command: String
	public var arguments: [LSPAny]?

	public init(title: String, command: String, arguments: [LSPAny]? = nil) {
		self.title = title
		self.command = command
		self.arguments = arguments
	}
}

public enum LSPCodeActionKind: String, Codable, Equatable, Sendable {
	case empty = ""
	case quickFix = "quickfix"
	case refactor = "refactor"
	case refactorExtract = "refactor.extract"
	case refactorInline = "refactor.inline"
	case refactorRewrite = "refactor.rewrite"
	case source = "source"
	case sourceOrganizeImports = "source.organizeImports"
	case sourceFixAll = "source.fixAll"
}

public struct LSPCodeAction: Codable, Equatable, Sendable {
	public var title: String
	public var kind: LSPCodeActionKind?
	public var diagnostics: [LSPDiagnostic]?
	public var isPreferred: Bool?
	public var edit: LSPWorkspaceEdit?
	public var command: LSPCommand?

	public init(
		title: String,
		kind: LSPCodeActionKind? = nil,
		diagnostics: [LSPDiagnostic]? = nil,
		isPreferred: Bool? = nil,
		edit: LSPWorkspaceEdit? = nil,
		command: LSPCommand? = nil
	) {
		self.title = title
		self.kind = kind
		self.diagnostics = diagnostics
		self.isPreferred = isPreferred
		self.edit = edit
		self.command = command
	}
}

public struct LSPCodeActionContext: Codable, Equatable, Sendable {
	public var diagnostics: [LSPDiagnostic]
	public var only: [LSPCodeActionKind]?

	public init(diagnostics: [LSPDiagnostic], only: [LSPCodeActionKind]? = nil) {
		self.diagnostics = diagnostics
		self.only = only
	}
}

public struct LSPCodeActionParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var range: LSPRange
	public var context: LSPCodeActionContext

	public init(textDocument: LSPTextDocumentIdentifier, range: LSPRange, context: LSPCodeActionContext) {
		self.textDocument = textDocument
		self.range = range
		self.context = context
	}
}

public enum LSPCodeActionResponse: Equatable, Sendable {
	case actions([LSPCodeAction])
	case commands([LSPCommand])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let actions = try? decoder.decode([LSPCodeAction].self, from: data), !actions.isEmpty {
			self = .actions(actions)
			return
		}
		if let commands = try? decoder.decode([LSPCommand].self, from: data), !commands.isEmpty {
			self = .commands(commands)
			return
		}
		self = .none
	}

	public func filteredQuickFixes() -> [LSPCodeAction] {
		guard case let .actions(list) = self else {
			return []
		}
		return list.filter { $0.kind == .quickFix || $0.kind == nil }
	}
}

public struct LSPPrepareRenameParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition

	public init(textDocument: LSPTextDocumentIdentifier, position: LSPPosition) {
		self.textDocument = textDocument
		self.position = position
	}
}

public struct LSPRenameParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition
	public var newName: String

	public init(textDocument: LSPTextDocumentIdentifier, position: LSPPosition, newName: String) {
		self.textDocument = textDocument
		self.position = position
		self.newName = newName
	}
}

public struct LSPTextDocumentEdit: Codable, Equatable, Sendable {
	public var textDocument: LSPVersionedTextDocumentIdentifier
	public var edits: [LSPTextEdit]

	public init(textDocument: LSPVersionedTextDocumentIdentifier, edits: [LSPTextEdit]) {
		self.textDocument = textDocument
		self.edits = edits
	}
}

public struct LSPWorkspaceEdit: Codable, Equatable, Sendable {
	public var changes: [String: [LSPTextEdit]]?
	public var documentChanges: [LSPTextDocumentEdit]?

	public init(changes: [String: [LSPTextEdit]]? = nil, documentChanges: [LSPTextDocumentEdit]? = nil) {
		self.changes = changes
		self.documentChanges = documentChanges
	}
}

public struct LSPLocation: Codable, Equatable, Sendable {
	public var uri: String
	public var range: LSPRange

	public init(uri: String, range: LSPRange) {
		self.uri = uri
		self.range = range
	}
}

public struct LSPLocationLink: Codable, Equatable, Sendable {
	public var originSelectionRange: LSPRange?
	public var targetUri: String
	public var targetRange: LSPRange
	public var targetSelectionRange: LSPRange

	public init(originSelectionRange: LSPRange? = nil, targetUri: String, targetRange: LSPRange, targetSelectionRange: LSPRange) {
		self.originSelectionRange = originSelectionRange
		self.targetUri = targetUri
		self.targetRange = targetRange
		self.targetSelectionRange = targetSelectionRange
	}
}

public enum LSPDefinitionResult: Equatable, Sendable {
	case single(LSPLocation)
	case multiple([LSPLocation])
	case linked([LSPLocationLink])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let location = try? decoder.decode(LSPLocation.self, from: data) {
			self = .single(location)
			return
		}
		if let array = try? decoder.decode([LSPLocation].self, from: data), !array.isEmpty {
			self = .multiple(array)
			return
		}
		if let links = try? decoder.decode([LSPLocationLink].self, from: data), !links.isEmpty {
			self = .linked(links)
			return
		}
		self = .none
	}

	public var locations: [LSPLocation] {
		switch self {
		case let .single(location):
			return [location]
		case let .multiple(locations):
			return locations
		case let .linked(links):
			return links.map { LSPLocation(uri: $0.targetUri, range: $0.targetSelectionRange) }
		case .none:
			return []
		}
	}
}

public struct LSPTextEdit: Codable, Equatable, Sendable {
	public var range: LSPRange
	public var newText: String

	public init(range: LSPRange, newText: String) {
		self.range = range
		self.newText = newText
	}
}

public struct LSPFormattingOptions: Codable, Equatable, Sendable {
	public var tabSize: Int
	public var insertSpaces: Bool

	public init(tabSize: Int = 4, insertSpaces: Bool = false) {
		self.tabSize = tabSize
		self.insertSpaces = insertSpaces
	}
}

public struct LSPDocumentFormattingParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var options: LSPFormattingOptions

	public init(textDocument: LSPTextDocumentIdentifier, options: LSPFormattingOptions) {
		self.textDocument = textDocument
		self.options = options
	}
}

public struct LSPDocumentRangeFormattingParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var range: LSPRange
	public var options: LSPFormattingOptions

	public init(textDocument: LSPTextDocumentIdentifier, range: LSPRange, options: LSPFormattingOptions) {
		self.textDocument = textDocument
		self.range = range
		self.options = options
	}
}

public struct LSPConfigurationItem: Codable, Equatable, Sendable {
	public var scopeUri: String?
	public var section: String?

	public init(scopeUri: String? = nil, section: String? = nil) {
		self.scopeUri = scopeUri
		self.section = section
	}
}

public struct LSPConfigurationParams: Codable, Equatable, Sendable {
	public var items: [LSPConfigurationItem]

	public init(items: [LSPConfigurationItem]) {
		self.items = items
	}
}

public extension LSPMethod {
	static let workspaceConfiguration = "workspace/configuration"
	static let workspaceApplyEdit = "workspace/applyEdit"
}

public enum LSPDiagnosticSeverity: Int, Codable, Equatable, Sendable {
	case error = 1
	case warning = 2
	case information = 3
	case hint = 4
}

public enum LSPDiagnosticCode: Codable, Equatable, Sendable {
	case int(Int)
	case string(String)

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(Int.self) {
			self = .int(value)
		} else {
			self = .string(try container.decode(String.self))
		}
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .int(value):
			try container.encode(value)
		case let .string(value):
			try container.encode(value)
		}
	}
}

public struct LSPDiagnostic: Codable, Equatable, Sendable {
	public var range: LSPRange
	public var severity: LSPDiagnosticSeverity?
	public var code: LSPDiagnosticCode?
	public var source: String?
	public var message: String
	public var relatedInformation: [LSPDiagnosticRelatedInformation]?

	public init(
		range: LSPRange,
		severity: LSPDiagnosticSeverity? = nil,
		code: LSPDiagnosticCode? = nil,
		source: String? = nil,
		message: String,
		relatedInformation: [LSPDiagnosticRelatedInformation]? = nil
	) {
		self.range = range
		self.severity = severity
		self.code = code
		self.source = source
		self.message = message
		self.relatedInformation = relatedInformation
	}
}

public struct LSPDiagnosticRelatedInformation: Codable, Equatable, Sendable {
	public var location: LSPLocation
	public var message: String

	public init(location: LSPLocation, message: String) {
		self.location = location
		self.message = message
	}
}

public struct LSPPublishDiagnosticsParams: Codable, Equatable, Sendable {
	public var uri: String
	public var version: Int?
	public var diagnostics: [LSPDiagnostic]

	public init(uri: String, version: Int? = nil, diagnostics: [LSPDiagnostic]) {
		self.uri = uri
		self.version = version
		self.diagnostics = diagnostics
	}
}

public struct LSPInitializeParams: Codable, Equatable, Sendable {
	public var processId: Int?
	public var rootUri: String?
	public var capabilities: LSPAny

	public init(processId: Int?, rootUri: String?, capabilities: LSPAny = .object([:])) {
		self.processId = processId
		self.rootUri = rootUri
		self.capabilities = capabilities
	}

	private enum CodingKeys: String, CodingKey {
		case processId
		case rootUri
		case capabilities
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		if container.contains(.processId), try !container.decodeNil(forKey: .processId) {
			processId = try container.decode(Int.self, forKey: .processId)
		} else {
			processId = nil
		}
		if container.contains(.rootUri), try !container.decodeNil(forKey: .rootUri) {
			rootUri = try container.decode(String.self, forKey: .rootUri)
		} else {
			rootUri = nil
		}
		capabilities = try container.decodeIfPresent(LSPAny.self, forKey: .capabilities) ?? .object([:])
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.container(keyedBy: CodingKeys.self)
		if let processId {
			try container.encode(processId, forKey: .processId)
		} else {
			try container.encodeNil(forKey: .processId)
		}
		if let rootUri {
			try container.encode(rootUri, forKey: .rootUri)
		} else {
			try container.encodeNil(forKey: .rootUri)
		}
		try container.encode(capabilities, forKey: .capabilities)
	}
}
