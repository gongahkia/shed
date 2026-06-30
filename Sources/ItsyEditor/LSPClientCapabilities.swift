import Foundation
import ItsyLSP

public struct ItsyClientCapabilities: Codable, Equatable, Sendable {
	public struct TextDocument: Codable, Equatable, Sendable {
		public struct DocumentSymbol: Codable, Equatable, Sendable {
			public var hierarchicalDocumentSymbolSupport: Bool

			public init(hierarchicalDocumentSymbolSupport: Bool = true) {
				self.hierarchicalDocumentSymbolSupport = hierarchicalDocumentSymbolSupport
			}
		}

		public struct Completion: Codable, Equatable, Sendable {
			public struct ResolveSupport: Codable, Equatable, Sendable {
				public var properties: [String]

				public init(properties: [String] = ["documentation", "detail", "additionalTextEdits"]) {
					self.properties = properties
				}
			}

			public struct CompletionItem: Codable, Equatable, Sendable {
				public var snippetSupport: Bool
				public var labelDetailsSupport: Bool
				public var resolveSupport: ResolveSupport

				public init(
					snippetSupport: Bool = true,
					labelDetailsSupport: Bool = true,
					resolveSupport: ResolveSupport = ResolveSupport()
				) {
					self.snippetSupport = snippetSupport
					self.labelDetailsSupport = labelDetailsSupport
					self.resolveSupport = resolveSupport
				}
			}

			public var completionItem: CompletionItem

			public init(completionItem: CompletionItem = CompletionItem()) {
				self.completionItem = completionItem
			}
		}

		public struct PublishDiagnostics: Codable, Equatable, Sendable {
			public var relatedInformation: Bool

			public init(relatedInformation: Bool = true) {
				self.relatedInformation = relatedInformation
			}
		}

		public struct Hover: Codable, Equatable, Sendable {
			public var contentFormat: [String]

			public init(contentFormat: [String] = ["markdown", "plaintext"]) {
				self.contentFormat = contentFormat
			}
		}

		public struct Definition: Codable, Equatable, Sendable {
			public var linkSupport: Bool

			public init(linkSupport: Bool = true) {
				self.linkSupport = linkSupport
			}
		}

		public struct Rename: Codable, Equatable, Sendable {
			public var prepareSupport: Bool

			public init(prepareSupport: Bool = true) {
				self.prepareSupport = prepareSupport
			}
		}

		public var documentSymbol: DocumentSymbol
		public var completion: Completion
		public var publishDiagnostics: PublishDiagnostics
		public var hover: Hover
		public var definition: Definition
		public var rename: Rename

		public init(
			documentSymbol: DocumentSymbol = DocumentSymbol(),
			completion: Completion = Completion(),
			publishDiagnostics: PublishDiagnostics = PublishDiagnostics(),
			hover: Hover = Hover(),
			definition: Definition = Definition(),
			rename: Rename = Rename()
		) {
			self.documentSymbol = documentSymbol
			self.completion = completion
			self.publishDiagnostics = publishDiagnostics
			self.hover = hover
			self.definition = definition
			self.rename = rename
		}
	}

	public struct Workspace: Codable, Equatable, Sendable {
		public struct WorkspaceEdit: Codable, Equatable, Sendable {
			public var documentChanges: Bool

			public init(documentChanges: Bool = true) {
				self.documentChanges = documentChanges
			}
		}

		public var applyEdit: Bool
		public var workspaceEdit: WorkspaceEdit
		public var configuration: Bool

		public init(
			applyEdit: Bool = true,
			workspaceEdit: WorkspaceEdit = WorkspaceEdit(),
			configuration: Bool = true
		) {
			self.applyEdit = applyEdit
			self.workspaceEdit = workspaceEdit
			self.configuration = configuration
		}
	}

	public var textDocument: TextDocument
	public var workspace: Workspace

	public init(textDocument: TextDocument = TextDocument(), workspace: Workspace = Workspace()) {
		self.textDocument = textDocument
		self.workspace = workspace
	}

	public func toLSPAny() throws -> LSPAny {
		try LSPAny(encoding: self)
	}
}

public extension LSPInitializeParams {
	static func itsy(processID: Int? = Int(ProcessInfo.processInfo.processIdentifier), workspaceRoot: URL?) throws -> LSPInitializeParams {
		let rootURI = workspaceRoot.map { $0.standardizedFileURL.absoluteString }
		let capabilities = try ItsyClientCapabilities().toLSPAny()
		return LSPInitializeParams(processId: processID, rootUri: rootURI, capabilities: capabilities)
	}
}
