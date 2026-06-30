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

	public init(range: LSPRange, severity: LSPDiagnosticSeverity? = nil, code: LSPDiagnosticCode? = nil, source: String? = nil, message: String) {
		self.range = range
		self.severity = severity
		self.code = code
		self.source = source
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
