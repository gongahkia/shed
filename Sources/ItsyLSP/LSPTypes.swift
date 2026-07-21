import Foundation

public enum LSPMethod {
	public static let initialize = "initialize"
	public static let initialized = "initialized"
	public static let shutdown = "shutdown"
	public static let exit = "exit"
	public static let textDocumentDidOpen = "textDocument/didOpen"
	public static let textDocumentDidChange = "textDocument/didChange"
	public static let textDocumentDidSave = "textDocument/didSave"
	public static let textDocumentDidClose = "textDocument/didClose"
	public static let textDocumentPublishDiagnostics = "textDocument/publishDiagnostics"
	public static let textDocumentCompletion = "textDocument/completion"
	public static let completionItemResolve = "completionItem/resolve"
	public static let textDocumentHover = "textDocument/hover"
	public static let textDocumentSignatureHelp = "textDocument/signatureHelp"
	public static let textDocumentDefinition = "textDocument/definition"
	public static let textDocumentDocumentSymbol = "textDocument/documentSymbol"
	public static let textDocumentReferences = "textDocument/references"
	public static let textDocumentPrepareRename = "textDocument/prepareRename"
	public static let textDocumentRename = "textDocument/rename"
	public static let textDocumentCodeAction = "textDocument/codeAction"
	public static let codeActionResolve = "codeAction/resolve"
	public static let textDocumentFormatting = "textDocument/formatting"
	public static let textDocumentRangeFormatting = "textDocument/rangeFormatting"
	public static let textDocumentSemanticTokensFull = "textDocument/semanticTokens/full"
	public static let textDocumentSemanticTokensFullDelta = "textDocument/semanticTokens/full/delta"
	public static let textDocumentSemanticTokensRange = "textDocument/semanticTokens/range"
	public static let textDocumentInlayHint = "textDocument/inlayHint"
	public static let inlayHintResolve = "inlayHint/resolve"
	public static let textDocumentFoldingRange = "textDocument/foldingRange"
	public static let textDocumentDocumentHighlight = "textDocument/documentHighlight"
	public static let textDocumentPrepareCallHierarchy = "textDocument/prepareCallHierarchy"
	public static let callHierarchyIncomingCalls = "callHierarchy/incomingCalls"
	public static let callHierarchyOutgoingCalls = "callHierarchy/outgoingCalls"
	public static let textDocumentPrepareTypeHierarchy = "textDocument/prepareTypeHierarchy"
	public static let typeHierarchySupertypes = "typeHierarchy/supertypes"
	public static let typeHierarchySubtypes = "typeHierarchy/subtypes"
	public static let workspaceExecuteCommand = "workspace/executeCommand"
	public static let workspaceDidChangeWatchedFiles = "workspace/didChangeWatchedFiles"
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

public struct LSPDidSaveTextDocumentParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var text: String?

	public init(textDocument: LSPTextDocumentIdentifier, text: String? = nil) {
		self.textDocument = textDocument
		self.text = text
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

public struct LSPDocumentSymbolParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier

	public init(textDocument: LSPTextDocumentIdentifier) {
		self.textDocument = textDocument
	}
}

public struct LSPSemanticTokensParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier

	public init(textDocument: LSPTextDocumentIdentifier) {
		self.textDocument = textDocument
	}
}

public struct LSPSemanticTokensDeltaParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var previousResultId: String

	public init(textDocument: LSPTextDocumentIdentifier, previousResultId: String) {
		self.textDocument = textDocument
		self.previousResultId = previousResultId
	}
}

public struct LSPSemanticTokensRangeParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var range: LSPRange

	public init(textDocument: LSPTextDocumentIdentifier, range: LSPRange) {
		self.textDocument = textDocument
		self.range = range
	}
}

public struct LSPInlayHintParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var range: LSPRange

	public init(textDocument: LSPTextDocumentIdentifier, range: LSPRange) {
		self.textDocument = textDocument
		self.range = range
	}
}

public struct LSPFoldingRangeParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier

	public init(textDocument: LSPTextDocumentIdentifier) {
		self.textDocument = textDocument
	}
}

public typealias LSPDocumentHighlightParams = LSPTextDocumentPositionParams
public typealias LSPCallHierarchyPrepareParams = LSPTextDocumentPositionParams
public typealias LSPTypeHierarchyPrepareParams = LSPTextDocumentPositionParams

public enum LSPSignatureHelpTriggerKind: Int, Codable, Equatable, Sendable {
	case invoked = 1
	case triggerCharacter = 2
	case contentChange = 3
}

public struct LSPSignatureHelpContext: Codable, Equatable, Sendable {
	public var triggerKind: LSPSignatureHelpTriggerKind
	public var triggerCharacter: String?
	public var isRetrigger: Bool
	public var activeSignatureHelp: LSPSignatureHelp?

	public init(
		triggerKind: LSPSignatureHelpTriggerKind,
		triggerCharacter: String? = nil,
		isRetrigger: Bool = false,
		activeSignatureHelp: LSPSignatureHelp? = nil
	) {
		self.triggerKind = triggerKind
		self.triggerCharacter = triggerCharacter
		self.isRetrigger = isRetrigger
		self.activeSignatureHelp = activeSignatureHelp
	}
}

public struct LSPSignatureHelpParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition
	public var context: LSPSignatureHelpContext?

	public init(textDocument: LSPTextDocumentIdentifier, position: LSPPosition, context: LSPSignatureHelpContext? = nil) {
		self.textDocument = textDocument
		self.position = position
		self.context = context
	}
}

public enum LSPParameterLabel: Equatable, Sendable {
	case string(String)
	case offsets(start: Int, end: Int)

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(String.self) {
			self = .string(value)
			return
		}
		let offsets = try container.decode([Int].self)
		guard offsets.count == 2 else {
			throw DecodingError.dataCorruptedError(in: container, debugDescription: "parameter label offsets require two integers")
		}
		self = .offsets(start: offsets[0], end: offsets[1])
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .string(value):
			try container.encode(value)
		case let .offsets(start, end):
			try container.encode([start, end])
		}
	}
}

extension LSPParameterLabel: Codable {}

public struct LSPParameterInformation: Codable, Equatable, Sendable {
	public var label: LSPParameterLabel
	public var documentation: LSPAny?

	public init(label: LSPParameterLabel, documentation: LSPAny? = nil) {
		self.label = label
		self.documentation = documentation
	}
}

public struct LSPSignatureInformation: Codable, Equatable, Sendable {
	public var label: String
	public var documentation: LSPAny?
	public var parameters: [LSPParameterInformation]?
	public var activeParameter: Int?

	public init(
		label: String,
		documentation: LSPAny? = nil,
		parameters: [LSPParameterInformation]? = nil,
		activeParameter: Int? = nil
	) {
		self.label = label
		self.documentation = documentation
		self.parameters = parameters
		self.activeParameter = activeParameter
	}
}

public struct LSPSignatureHelp: Codable, Equatable, Sendable {
	public var signatures: [LSPSignatureInformation]
	public var activeSignature: Int?
	public var activeParameter: Int?

	public init(signatures: [LSPSignatureInformation], activeSignature: Int? = nil, activeParameter: Int? = nil) {
		self.signatures = signatures
		self.activeSignature = activeSignature
		self.activeParameter = activeParameter
	}
}

public struct LSPReferenceContext: Codable, Equatable, Sendable {
	public var includeDeclaration: Bool

	public init(includeDeclaration: Bool) {
		self.includeDeclaration = includeDeclaration
	}
}

public struct LSPReferenceParams: Codable, Equatable, Sendable {
	public var textDocument: LSPTextDocumentIdentifier
	public var position: LSPPosition
	public var context: LSPReferenceContext

	public init(textDocument: LSPTextDocumentIdentifier, position: LSPPosition, context: LSPReferenceContext) {
		self.textDocument = textDocument
		self.position = position
		self.context = context
	}
}

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

public enum LSPSignatureHelpResult: Equatable, Sendable {
	case help(LSPSignatureHelp)
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let help = try? decoder.decode(LSPSignatureHelp.self, from: data), !help.signatures.isEmpty {
			self = .help(help)
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var help: LSPSignatureHelp? {
		guard case let .help(value) = self else {
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

public struct LSPSignatureHelpOptions: Codable, Equatable, Sendable {
	public var triggerCharacters: [String]?
	public var retriggerCharacters: [String]?

	public init(triggerCharacters: [String]? = nil, retriggerCharacters: [String]? = nil) {
		self.triggerCharacters = triggerCharacters
		self.retriggerCharacters = retriggerCharacters
	}
}

public struct LSPCodeActionOptions: Codable, Equatable, Sendable {
	public var resolveProvider: Bool?

	public init(resolveProvider: Bool? = nil) {
		self.resolveProvider = resolveProvider
	}
}

public enum LSPCodeActionProviderCapability: Codable, Equatable, Sendable {
	case bool(Bool)
	case options(LSPCodeActionOptions)

	public var resolveProvider: Bool {
		switch self {
		case .bool:
			return false
		case let .options(options):
			return options.resolveProvider ?? false
		}
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(Bool.self) {
			self = .bool(value)
			return
		}
		self = .options(try container.decode(LSPCodeActionOptions.self))
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .bool(value):
			try container.encode(value)
		case let .options(options):
			try container.encode(options)
		}
	}
}

public enum LSPBooleanCapability: Codable, Equatable, Sendable {
	case bool(Bool)
	case options(LSPAny)

	public var isEnabled: Bool {
		switch self {
		case let .bool(value):
			return value
		case .options:
			return true
		}
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(Bool.self) {
			self = .bool(value)
			return
		}
		self = .options(try container.decode(LSPAny.self))
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .bool(value):
			try container.encode(value)
		case let .options(value):
			try container.encode(value)
		}
	}
}

public struct LSPSemanticTokensLegend: Codable, Equatable, Sendable {
	public var tokenTypes: [String]
	public var tokenModifiers: [String]

	public init(tokenTypes: [String], tokenModifiers: [String]) {
		self.tokenTypes = tokenTypes
		self.tokenModifiers = tokenModifiers
	}
}

public struct LSPSemanticTokensFullOptions: Codable, Equatable, Sendable {
	public var delta: Bool?

	public init(delta: Bool? = nil) {
		self.delta = delta
	}
}

public enum LSPSemanticTokensFullCapability: Codable, Equatable, Sendable {
	case bool(Bool)
	case options(LSPSemanticTokensFullOptions)

	public var isEnabled: Bool {
		switch self {
		case let .bool(value):
			return value
		case .options:
			return true
		}
	}

	public var supportsDelta: Bool {
		guard case let .options(options) = self else {
			return false
		}
		return options.delta == true
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(Bool.self) {
			self = .bool(value)
			return
		}
		self = .options(try container.decode(LSPSemanticTokensFullOptions.self))
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .bool(value):
			try container.encode(value)
		case let .options(options):
			try container.encode(options)
		}
	}
}

public struct LSPSemanticTokensOptions: Codable, Equatable, Sendable {
	public var legend: LSPSemanticTokensLegend
	public var range: LSPBooleanCapability?
	public var full: LSPSemanticTokensFullCapability?

	public init(
		legend: LSPSemanticTokensLegend,
		range: LSPBooleanCapability? = nil,
		full: LSPSemanticTokensFullCapability? = nil
	) {
		self.legend = legend
		self.range = range
		self.full = full
	}
}

public struct LSPServerCapabilities: Codable, Equatable, Sendable {
	public var completionProvider: LSPCompletionOptions?
	public var signatureHelpProvider: LSPSignatureHelpOptions?
	public var codeActionProvider: LSPCodeActionProviderCapability?
	public var semanticTokensProvider: LSPSemanticTokensOptions?
	public var inlayHintProvider: LSPBooleanCapability?
	public var foldingRangeProvider: LSPBooleanCapability?
	public var documentHighlightProvider: LSPBooleanCapability?
	public var callHierarchyProvider: LSPBooleanCapability?
	public var typeHierarchyProvider: LSPBooleanCapability?

	public init(
		completionProvider: LSPCompletionOptions? = nil,
		signatureHelpProvider: LSPSignatureHelpOptions? = nil,
		codeActionProvider: LSPCodeActionProviderCapability? = nil,
		semanticTokensProvider: LSPSemanticTokensOptions? = nil,
		inlayHintProvider: LSPBooleanCapability? = nil,
		foldingRangeProvider: LSPBooleanCapability? = nil,
		documentHighlightProvider: LSPBooleanCapability? = nil,
		callHierarchyProvider: LSPBooleanCapability? = nil,
		typeHierarchyProvider: LSPBooleanCapability? = nil
	) {
		self.completionProvider = completionProvider
		self.signatureHelpProvider = signatureHelpProvider
		self.codeActionProvider = codeActionProvider
		self.semanticTokensProvider = semanticTokensProvider
		self.inlayHintProvider = inlayHintProvider
		self.foldingRangeProvider = foldingRangeProvider
		self.documentHighlightProvider = documentHighlightProvider
		self.callHierarchyProvider = callHierarchyProvider
		self.typeHierarchyProvider = typeHierarchyProvider
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

public enum LSPSymbolTag: Int, Codable, Equatable, Sendable {
	case deprecated = 1
}

public struct LSPWorkspaceSymbolParams: Codable, Equatable, Sendable {
	public var query: String

	public init(query: String) {
		self.query = query
	}
}

public enum LSPWorkspaceSymbolLocation: Codable, Equatable, Sendable {
	case location(LSPLocation)
	case uri(String)

	private enum CodingKeys: String, CodingKey {
		case uri
	}

	public var resolvedLocation: LSPLocation? {
		guard case let .location(location) = self else {
			return nil
		}
		return location
	}

	public var uri: String {
		switch self {
		case let .location(location):
			return location.uri
		case let .uri(uri):
			return uri
		}
	}

	public init(from decoder: Decoder) throws {
		if let location = try? LSPLocation(from: decoder) {
			self = .location(location)
			return
		}
		let container = try decoder.container(keyedBy: CodingKeys.self)
		self = .uri(try container.decode(String.self, forKey: .uri))
	}

	public func encode(to encoder: Encoder) throws {
		switch self {
		case let .location(location):
			try location.encode(to: encoder)
		case let .uri(uri):
			var container = encoder.container(keyedBy: CodingKeys.self)
			try container.encode(uri, forKey: .uri)
		}
	}
}

public struct LSPWorkspaceSymbol: Codable, Equatable, Sendable {
	public var name: String
	public var kind: LSPSymbolKind
	public var tags: [LSPSymbolTag]?
	public var containerName: String?
	public var location: LSPWorkspaceSymbolLocation
	public var data: LSPAny?

	public init(
		name: String,
		kind: LSPSymbolKind,
		tags: [LSPSymbolTag]? = nil,
		containerName: String? = nil,
		location: LSPWorkspaceSymbolLocation,
		data: LSPAny? = nil
	) {
		self.name = name
		self.kind = kind
		self.tags = tags
		self.containerName = containerName
		self.location = location
		self.data = data
	}
}

public enum LSPWorkspaceSymbolResult: Equatable, Sendable {
	case symbolInformation([LSPSymbolInformation])
	case workspaceSymbols([LSPWorkspaceSymbol])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let workspaceSymbols = try? decoder.decode([LSPWorkspaceSymbol].self, from: data) {
			self = .workspaceSymbols(workspaceSymbols)
			return
		}
		if let symbolInformation = try? decoder.decode([LSPSymbolInformation].self, from: data) {
			self = .symbolInformation(symbolInformation)
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var workspaceSymbols: [LSPWorkspaceSymbol] {
		switch self {
		case let .symbolInformation(symbolInformation):
			return symbolInformation.map {
				LSPWorkspaceSymbol(
					name: $0.name,
					kind: $0.kind,
					containerName: $0.containerName,
					location: .location($0.location)
				)
			}
		case let .workspaceSymbols(workspaceSymbols):
			return workspaceSymbols
		case .none:
			return []
		}
	}
}

public enum LSPDocumentSymbolResult: Equatable, Sendable {
	case documentSymbols([LSPDocumentSymbol])
	case symbolInformation([LSPSymbolInformation])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let documentSymbols = try? decoder.decode([LSPDocumentSymbol].self, from: data) {
			self = .documentSymbols(documentSymbols)
			return
		}
		if let symbolInformation = try? decoder.decode([LSPSymbolInformation].self, from: data) {
			self = .symbolInformation(symbolInformation)
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var documentSymbols: [LSPDocumentSymbol] {
		guard case let .documentSymbols(documentSymbols) = self else {
			return []
		}
		return documentSymbols
	}

	public var symbolInformation: [LSPSymbolInformation] {
		guard case let .symbolInformation(symbolInformation) = self else {
			return []
		}
		return symbolInformation
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
	public var disabled: LSPCodeActionDisabled?
	public var edit: LSPWorkspaceEdit?
	public var command: LSPCommand?
	public var data: LSPAny?

	public init(
		title: String,
		kind: LSPCodeActionKind? = nil,
		diagnostics: [LSPDiagnostic]? = nil,
		isPreferred: Bool? = nil,
		disabled: LSPCodeActionDisabled? = nil,
		edit: LSPWorkspaceEdit? = nil,
		command: LSPCommand? = nil,
		data: LSPAny? = nil
	) {
		self.title = title
		self.kind = kind
		self.diagnostics = diagnostics
		self.isPreferred = isPreferred
		self.disabled = disabled
		self.edit = edit
		self.command = command
		self.data = data
	}
}

public struct LSPCodeActionDisabled: Codable, Equatable, Sendable {
	public var reason: String

	public init(reason: String) {
		self.reason = reason
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

public enum LSPCodeActionEntry: Codable, Equatable, Sendable {
	case action(LSPCodeAction)
	case command(LSPCommand)

	public var title: String {
		switch self {
		case let .action(action):
			return action.title
		case let .command(command):
			return command.title
		}
	}

	public init(from decoder: Decoder) throws {
		if let action = try? LSPCodeAction(from: decoder) {
			self = .action(action)
			return
		}
		self = .command(try LSPCommand(from: decoder))
	}

	public func encode(to encoder: Encoder) throws {
		switch self {
		case let .action(action):
			try action.encode(to: encoder)
		case let .command(command):
			try command.encode(to: encoder)
		}
	}
}

public enum LSPCodeActionResponse: Equatable, Sendable {
	case actions([LSPCodeAction])
	case commands([LSPCommand])
	case mixed([LSPCodeActionEntry])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let entries = try? decoder.decode([LSPCodeActionEntry].self, from: data), !entries.isEmpty {
			let actions = entries.compactMap { entry -> LSPCodeAction? in
				if case let .action(action) = entry {
					return action
				}
				return nil
			}
			let commands = entries.compactMap { entry -> LSPCommand? in
				if case let .command(command) = entry {
					return command
				}
				return nil
			}
			if actions.count == entries.count {
				self = .actions(actions)
			} else if commands.count == entries.count {
				self = .commands(commands)
			} else {
				self = .mixed(entries)
			}
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var entries: [LSPCodeActionEntry] {
		switch self {
		case let .actions(actions):
			return actions.map(LSPCodeActionEntry.action)
		case let .commands(commands):
			return commands.map(LSPCodeActionEntry.command)
		case let .mixed(entries):
			return entries
		case .none:
			return []
		}
	}

	public func filteredQuickFixes() -> [LSPCodeAction] {
		entries.compactMap { entry -> LSPCodeAction? in
			guard case let .action(action) = entry, action.kind == .quickFix || action.kind == nil else {
				return nil
			}
			return action
		}
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

public enum LSPPrepareRenameResult: Equatable, Sendable {
	case range(LSPRange)
	case placeholder(range: LSPRange, placeholder: String)
	case defaultBehavior(Bool)
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		if let range = try? decoder.decode(LSPRange.self, from: data) {
			self = .range(range)
			return
		}
		if let value = try? decoder.decode(LSPPrepareRenamePlaceholder.self, from: data) {
			self = .placeholder(range: value.range, placeholder: value.placeholder)
			return
		}
		if let value = try? decoder.decode(LSPPrepareRenameDefaultBehavior.self, from: data) {
			self = .defaultBehavior(value.defaultBehavior)
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var range: LSPRange? {
		switch self {
		case let .range(range), let .placeholder(range, _):
			return range
		case .defaultBehavior, .none:
			return nil
		}
	}

	public var placeholder: String? {
		guard case let .placeholder(_, placeholder) = self else {
			return nil
		}
		return placeholder
	}
}

private struct LSPPrepareRenamePlaceholder: Codable, Equatable, Sendable {
	var range: LSPRange
	var placeholder: String
}

private struct LSPPrepareRenameDefaultBehavior: Codable, Equatable, Sendable {
	var defaultBehavior: Bool
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

public enum LSPWorkspaceEditResult: Equatable, Sendable {
	case edit(LSPWorkspaceEdit)
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		self = .edit(try decoder.decode(LSPWorkspaceEdit.self, from: data))
	}

	public var edit: LSPWorkspaceEdit? {
		guard case let .edit(edit) = self else {
			return nil
		}
		return edit
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

public enum LSPReferencesResult: Equatable, Sendable {
	case locations([LSPLocation])
	case none

	public init(decoding data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
		if let locations = try? decoder.decode([LSPLocation].self, from: data), !locations.isEmpty {
			self = .locations(locations)
			return
		}
		self = .none
	}

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		try self.init(decoding: data, decoder: decoder)
	}

	public var locations: [LSPLocation] {
		guard case let .locations(locations) = self else {
			return []
		}
		return locations
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

public struct LSPSemanticTokens: Codable, Equatable, Sendable {
	public var resultId: String?
	public var data: [Int]

	public init(resultId: String? = nil, data: [Int]) {
		self.resultId = resultId
		self.data = data
	}
}

public struct LSPSemanticTokensEdit: Codable, Equatable, Sendable {
	public var start: Int
	public var deleteCount: Int
	public var data: [Int]?

	public init(start: Int, deleteCount: Int, data: [Int]? = nil) {
		self.start = start
		self.deleteCount = deleteCount
		self.data = data
	}
}

public struct LSPSemanticTokensDelta: Codable, Equatable, Sendable {
	public var resultId: String?
	public var edits: [LSPSemanticTokensEdit]

	public init(resultId: String? = nil, edits: [LSPSemanticTokensEdit]) {
		self.resultId = resultId
		self.edits = edits
	}
}

public enum LSPSemanticTokensResult: Equatable, Sendable {
	case tokens(LSPSemanticTokens)
	case delta(LSPSemanticTokensDelta)
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		if let tokens = try? decoder.decode(LSPSemanticTokens.self, from: data) {
			self = .tokens(tokens)
			return
		}
		if let delta = try? decoder.decode(LSPSemanticTokensDelta.self, from: data) {
			self = .delta(delta)
			return
		}
		self = .none
	}

	public var tokens: LSPSemanticTokens? {
		guard case let .tokens(tokens) = self else {
			return nil
		}
		return tokens
	}
}

public enum LSPInlayHintKind: Int, Codable, Equatable, Sendable {
	case type = 1
	case parameter = 2
}

public struct LSPInlayHintLabelPart: Codable, Equatable, Sendable {
	public var value: String
	public var tooltip: LSPAny?
	public var location: LSPLocation?
	public var command: LSPCommand?

	public init(value: String, tooltip: LSPAny? = nil, location: LSPLocation? = nil, command: LSPCommand? = nil) {
		self.value = value
		self.tooltip = tooltip
		self.location = location
		self.command = command
	}
}

public enum LSPInlayHintLabel: Codable, Equatable, Sendable {
	case string(String)
	case parts([LSPInlayHintLabelPart])

	public var text: String {
		switch self {
		case let .string(value):
			return value
		case let .parts(parts):
			return parts.map(\.value).joined()
		}
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if let value = try? container.decode(String.self) {
			self = .string(value)
			return
		}
		self = .parts(try container.decode([LSPInlayHintLabelPart].self))
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .string(value):
			try container.encode(value)
		case let .parts(parts):
			try container.encode(parts)
		}
	}
}

public struct LSPInlayHint: Codable, Equatable, Sendable {
	public var position: LSPPosition
	public var label: LSPInlayHintLabel
	public var kind: LSPInlayHintKind?
	public var textEdits: [LSPTextEdit]?
	public var tooltip: LSPAny?
	public var paddingLeft: Bool?
	public var paddingRight: Bool?
	public var data: LSPAny?

	public init(
		position: LSPPosition,
		label: LSPInlayHintLabel,
		kind: LSPInlayHintKind? = nil,
		textEdits: [LSPTextEdit]? = nil,
		tooltip: LSPAny? = nil,
		paddingLeft: Bool? = nil,
		paddingRight: Bool? = nil,
		data: LSPAny? = nil
	) {
		self.position = position
		self.label = label
		self.kind = kind
		self.textEdits = textEdits
		self.tooltip = tooltip
		self.paddingLeft = paddingLeft
		self.paddingRight = paddingRight
		self.data = data
	}
}

public enum LSPInlayHintResult: Equatable, Sendable {
	case hints([LSPInlayHint])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let hints = (try? decoder.decode([LSPInlayHint].self, from: data)) ?? []
		self = hints.isEmpty ? .none : .hints(hints)
	}

	public var hints: [LSPInlayHint] {
		guard case let .hints(hints) = self else {
			return []
		}
		return hints
	}
}

public struct LSPFoldingRange: Codable, Equatable, Sendable {
	public var startLine: Int
	public var startCharacter: Int?
	public var endLine: Int
	public var endCharacter: Int?
	public var kind: String?
	public var collapsedText: String?

	public init(
		startLine: Int,
		startCharacter: Int? = nil,
		endLine: Int,
		endCharacter: Int? = nil,
		kind: String? = nil,
		collapsedText: String? = nil
	) {
		self.startLine = startLine
		self.startCharacter = startCharacter
		self.endLine = endLine
		self.endCharacter = endCharacter
		self.kind = kind
		self.collapsedText = collapsedText
	}
}

public enum LSPFoldingRangeResult: Equatable, Sendable {
	case ranges([LSPFoldingRange])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let ranges = (try? decoder.decode([LSPFoldingRange].self, from: data)) ?? []
		self = ranges.isEmpty ? .none : .ranges(ranges)
	}

	public var ranges: [LSPFoldingRange] {
		guard case let .ranges(ranges) = self else {
			return []
		}
		return ranges
	}
}

public enum LSPDocumentHighlightKind: Int, Codable, Equatable, Sendable {
	case text = 1
	case read = 2
	case write = 3
}

public struct LSPDocumentHighlight: Codable, Equatable, Sendable {
	public var range: LSPRange
	public var kind: LSPDocumentHighlightKind?

	public init(range: LSPRange, kind: LSPDocumentHighlightKind? = nil) {
		self.range = range
		self.kind = kind
	}
}

public enum LSPDocumentHighlightResult: Equatable, Sendable {
	case highlights([LSPDocumentHighlight])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let highlights = (try? decoder.decode([LSPDocumentHighlight].self, from: data)) ?? []
		self = highlights.isEmpty ? .none : .highlights(highlights)
	}

	public var highlights: [LSPDocumentHighlight] {
		guard case let .highlights(highlights) = self else {
			return []
		}
		return highlights
	}
}

public struct LSPCallHierarchyItem: Codable, Equatable, Sendable {
	public var name: String
	public var kind: LSPSymbolKind
	public var tags: [LSPSymbolTag]?
	public var detail: String?
	public var uri: String
	public var range: LSPRange
	public var selectionRange: LSPRange
	public var data: LSPAny?

	public init(
		name: String,
		kind: LSPSymbolKind,
		tags: [LSPSymbolTag]? = nil,
		detail: String? = nil,
		uri: String,
		range: LSPRange,
		selectionRange: LSPRange,
		data: LSPAny? = nil
	) {
		self.name = name
		self.kind = kind
		self.tags = tags
		self.detail = detail
		self.uri = uri
		self.range = range
		self.selectionRange = selectionRange
		self.data = data
	}
}

public struct LSPCallHierarchyIncomingCall: Codable, Equatable, Sendable {
	public var from: LSPCallHierarchyItem
	public var fromRanges: [LSPRange]

	public init(from: LSPCallHierarchyItem, fromRanges: [LSPRange]) {
		self.from = from
		self.fromRanges = fromRanges
	}
}

public struct LSPCallHierarchyOutgoingCall: Codable, Equatable, Sendable {
	public var to: LSPCallHierarchyItem
	public var fromRanges: [LSPRange]

	public init(to: LSPCallHierarchyItem, fromRanges: [LSPRange]) {
		self.to = to
		self.fromRanges = fromRanges
	}
}

public struct LSPCallHierarchyCallsParams: Codable, Equatable, Sendable {
	public var item: LSPCallHierarchyItem

	public init(item: LSPCallHierarchyItem) {
		self.item = item
	}
}

public enum LSPCallHierarchyPrepareResult: Equatable, Sendable {
	case items([LSPCallHierarchyItem])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let items = (try? decoder.decode([LSPCallHierarchyItem].self, from: data)) ?? []
		self = items.isEmpty ? .none : .items(items)
	}

	public var items: [LSPCallHierarchyItem] {
		guard case let .items(items) = self else {
			return []
		}
		return items
	}
}

public enum LSPCallHierarchyIncomingResult: Equatable, Sendable {
	case calls([LSPCallHierarchyIncomingCall])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let calls = (try? decoder.decode([LSPCallHierarchyIncomingCall].self, from: data)) ?? []
		self = calls.isEmpty ? .none : .calls(calls)
	}

	public var calls: [LSPCallHierarchyIncomingCall] {
		guard case let .calls(calls) = self else {
			return []
		}
		return calls
	}
}

public enum LSPCallHierarchyOutgoingResult: Equatable, Sendable {
	case calls([LSPCallHierarchyOutgoingCall])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let calls = (try? decoder.decode([LSPCallHierarchyOutgoingCall].self, from: data)) ?? []
		self = calls.isEmpty ? .none : .calls(calls)
	}

	public var calls: [LSPCallHierarchyOutgoingCall] {
		guard case let .calls(calls) = self else {
			return []
		}
		return calls
	}
}

public typealias LSPTypeHierarchyItem = LSPCallHierarchyItem

public struct LSPTypeHierarchyParams: Codable, Equatable, Sendable {
	public var item: LSPTypeHierarchyItem

	public init(item: LSPTypeHierarchyItem) {
		self.item = item
	}
}

public enum LSPTypeHierarchyResult: Equatable, Sendable {
	case items([LSPTypeHierarchyItem])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let items = (try? decoder.decode([LSPTypeHierarchyItem].self, from: data)) ?? []
		self = items.isEmpty ? .none : .items(items)
	}

	public var items: [LSPTypeHierarchyItem] {
		guard case let .items(items) = self else {
			return []
		}
		return items
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

public enum LSPTextEditResult: Equatable, Sendable {
	case edits([LSPTextEdit])
	case none

	public init(result: LSPAny?, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(result ?? .null)
		if (try? decoder.decode(LSPNull.self, from: data)) != nil {
			self = .none
			return
		}
		let edits = (try? decoder.decode([LSPTextEdit].self, from: data)) ?? []
		self = edits.isEmpty ? .none : .edits(edits)
	}

	public var edits: [LSPTextEdit] {
		guard case let .edits(edits) = self else {
			return []
		}
		return edits
	}
}

public struct LSPExecuteCommandParams: Codable, Equatable, Sendable {
	public var command: String
	public var arguments: [LSPAny]?

	public init(command: String, arguments: [LSPAny]? = nil) {
		self.command = command
		self.arguments = arguments
	}
}

public struct LSPApplyWorkspaceEditParams: Codable, Equatable, Sendable {
	public var label: String?
	public var edit: LSPWorkspaceEdit

	public init(label: String? = nil, edit: LSPWorkspaceEdit) {
		self.label = label
		self.edit = edit
	}
}

public struct LSPApplyWorkspaceEditResponse: Codable, Equatable, Sendable {
	public var applied: Bool
	public var failureReason: String?
	public var failedChange: Int?

	public init(applied: Bool, failureReason: String? = nil, failedChange: Int? = nil) {
		self.applied = applied
		self.failureReason = failureReason
		self.failedChange = failedChange
	}
}

public enum LSPFileChangeType: Int, Codable, Equatable, Sendable {
	case created = 1
	case changed = 2
	case deleted = 3
}

public struct LSPFileEvent: Codable, Equatable, Sendable {
	public var uri: String
	public var type: LSPFileChangeType

	public init(uri: String, type: LSPFileChangeType) {
		self.uri = uri
		self.type = type
	}
}

public struct LSPDidChangeWatchedFilesParams: Codable, Equatable, Sendable {
	public var changes: [LSPFileEvent]

	public init(changes: [LSPFileEvent]) {
		self.changes = changes
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
	static let workspaceSymbol = "workspace/symbol"
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
	public var initializationOptions: LSPAny?

	public init(processId: Int?, rootUri: String?, capabilities: LSPAny = .object([:]), initializationOptions: LSPAny? = nil) {
		self.processId = processId
		self.rootUri = rootUri
		self.capabilities = capabilities
		self.initializationOptions = initializationOptions
	}

	private enum CodingKeys: String, CodingKey {
		case processId
		case rootUri
		case capabilities
		case initializationOptions
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
		initializationOptions = try container.decodeIfPresent(LSPAny.self, forKey: .initializationOptions)
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
		try container.encodeIfPresent(initializationOptions, forKey: .initializationOptions)
	}
}
