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
}
