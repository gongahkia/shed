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

		public struct CodeAction: Codable, Equatable, Sendable {
			public struct ResolveSupport: Codable, Equatable, Sendable {
				public var properties: [String]

				public init(properties: [String] = ["edit"]) {
					self.properties = properties
				}
			}

			public struct CodeActionLiteralSupport: Codable, Equatable, Sendable {
				public struct CodeActionKind: Codable, Equatable, Sendable {
					public var valueSet: [String]

					public init(valueSet: [String] = ["", "quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite", "source", "source.organizeImports", "source.fixAll"]) {
						self.valueSet = valueSet
					}
				}

				public var codeActionKind: CodeActionKind

				public init(codeActionKind: CodeActionKind = CodeActionKind()) {
					self.codeActionKind = codeActionKind
				}
			}

			public var dataSupport: Bool
			public var isPreferredSupport: Bool
			public var disabledSupport: Bool
			public var resolveSupport: ResolveSupport
			public var codeActionLiteralSupport: CodeActionLiteralSupport

			public init(
				dataSupport: Bool = true,
				isPreferredSupport: Bool = true,
				disabledSupport: Bool = true,
				resolveSupport: ResolveSupport = ResolveSupport(),
				codeActionLiteralSupport: CodeActionLiteralSupport = CodeActionLiteralSupport()
			) {
				self.dataSupport = dataSupport
				self.isPreferredSupport = isPreferredSupport
				self.disabledSupport = disabledSupport
				self.resolveSupport = resolveSupport
				self.codeActionLiteralSupport = codeActionLiteralSupport
			}
		}

		public struct SemanticTokens: Codable, Equatable, Sendable {
			public struct Requests: Codable, Equatable, Sendable {
				public struct Full: Codable, Equatable, Sendable {
					public var delta: Bool

					public init(delta: Bool = true) {
						self.delta = delta
					}
				}

				public var range: Bool
				public var full: Full

				public init(range: Bool = true, full: Full = Full()) {
					self.range = range
					self.full = full
				}
			}

			public var dynamicRegistration: Bool
			public var requests: Requests
			public var tokenTypes: [String]
			public var tokenModifiers: [String]
			public var formats: [String]
			public var overlappingTokenSupport: Bool
			public var multilineTokenSupport: Bool
			public var serverCancelSupport: Bool
			public var augmentsSyntaxTokens: Bool

			public init(
				dynamicRegistration: Bool = false,
				requests: Requests = Requests(),
				tokenTypes: [String] = [
					"namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter",
					"variable", "property", "enumMember", "event", "function", "method", "macro", "keyword",
					"modifier", "comment", "string", "number", "regexp", "operator", "decorator",
				],
				tokenModifiers: [String] = [
					"declaration", "definition", "readonly", "static", "deprecated", "abstract",
					"async", "modification", "documentation", "defaultLibrary",
				],
				formats: [String] = ["relative"],
				overlappingTokenSupport: Bool = false,
				multilineTokenSupport: Bool = false,
				serverCancelSupport: Bool = true,
				augmentsSyntaxTokens: Bool = true
			) {
				self.dynamicRegistration = dynamicRegistration
				self.requests = requests
				self.tokenTypes = tokenTypes
				self.tokenModifiers = tokenModifiers
				self.formats = formats
				self.overlappingTokenSupport = overlappingTokenSupport
				self.multilineTokenSupport = multilineTokenSupport
				self.serverCancelSupport = serverCancelSupport
				self.augmentsSyntaxTokens = augmentsSyntaxTokens
			}
		}

		public struct InlayHint: Codable, Equatable, Sendable {
			public struct ResolveSupport: Codable, Equatable, Sendable {
				public var properties: [String]

				public init(properties: [String] = ["tooltip", "textEdits", "label.tooltip", "label.location", "label.command"]) {
					self.properties = properties
				}
			}

			public var dynamicRegistration: Bool
			public var resolveSupport: ResolveSupport

			public init(dynamicRegistration: Bool = false, resolveSupport: ResolveSupport = ResolveSupport()) {
				self.dynamicRegistration = dynamicRegistration
				self.resolveSupport = resolveSupport
			}
		}

		public struct FoldingRange: Codable, Equatable, Sendable {
			public struct FoldingRangeKind: Codable, Equatable, Sendable {
				public var valueSet: [String]

				public init(valueSet: [String] = ["comment", "imports", "region"]) {
					self.valueSet = valueSet
				}
			}

			public struct FoldingRangeOptions: Codable, Equatable, Sendable {
				public var collapsedText: Bool

				public init(collapsedText: Bool = false) {
					self.collapsedText = collapsedText
				}
			}

			public var dynamicRegistration: Bool
			public var lineFoldingOnly: Bool
			public var foldingRangeKind: FoldingRangeKind
			public var foldingRange: FoldingRangeOptions

			public init(
				dynamicRegistration: Bool = false,
				lineFoldingOnly: Bool = true,
				foldingRangeKind: FoldingRangeKind = FoldingRangeKind(),
				foldingRange: FoldingRangeOptions = FoldingRangeOptions()
			) {
				self.dynamicRegistration = dynamicRegistration
				self.lineFoldingOnly = lineFoldingOnly
				self.foldingRangeKind = foldingRangeKind
				self.foldingRange = foldingRange
			}
		}

		public struct DocumentHighlight: Codable, Equatable, Sendable {
			public var dynamicRegistration: Bool

			public init(dynamicRegistration: Bool = false) {
				self.dynamicRegistration = dynamicRegistration
			}
		}

		public struct Hierarchy: Codable, Equatable, Sendable {
			public var dynamicRegistration: Bool

			public init(dynamicRegistration: Bool = false) {
				self.dynamicRegistration = dynamicRegistration
			}
		}

		public var documentSymbol: DocumentSymbol
		public var completion: Completion
		public var publishDiagnostics: PublishDiagnostics
		public var hover: Hover
		public var definition: Definition
		public var rename: Rename
		public var codeAction: CodeAction
		public var semanticTokens: SemanticTokens
		public var inlayHint: InlayHint
		public var foldingRange: FoldingRange
		public var documentHighlight: DocumentHighlight
		public var callHierarchy: Hierarchy
		public var typeHierarchy: Hierarchy

		public init(
			documentSymbol: DocumentSymbol = DocumentSymbol(),
			completion: Completion = Completion(),
			publishDiagnostics: PublishDiagnostics = PublishDiagnostics(),
			hover: Hover = Hover(),
			definition: Definition = Definition(),
			rename: Rename = Rename(),
			codeAction: CodeAction = CodeAction(),
			semanticTokens: SemanticTokens = SemanticTokens(),
			inlayHint: InlayHint = InlayHint(),
			foldingRange: FoldingRange = FoldingRange(),
			documentHighlight: DocumentHighlight = DocumentHighlight(),
			callHierarchy: Hierarchy = Hierarchy(),
			typeHierarchy: Hierarchy = Hierarchy()
		) {
			self.documentSymbol = documentSymbol
			self.completion = completion
			self.publishDiagnostics = publishDiagnostics
			self.hover = hover
			self.definition = definition
			self.rename = rename
			self.codeAction = codeAction
			self.semanticTokens = semanticTokens
			self.inlayHint = inlayHint
			self.foldingRange = foldingRange
			self.documentHighlight = documentHighlight
			self.callHierarchy = callHierarchy
			self.typeHierarchy = typeHierarchy
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
