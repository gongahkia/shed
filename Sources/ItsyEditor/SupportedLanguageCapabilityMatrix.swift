import Foundation

public enum SupportedLanguageCapability: String, CaseIterable, Codable, Equatable, Hashable, Sendable {
	case syntaxHighlighting = "syntax_highlighting"
	case localSymbols = "local_symbols"
	case languageServer = "language_server"
	case debugAdapter = "debug_adapter"
}

public struct SupportedLanguageCapabilityRow: Equatable, Sendable {
	public var grammarID: String
	public var languageID: String
	public var capabilities: Set<SupportedLanguageCapability>
	public var languageServerID: String
	public var debugAdapterID: String?

	public init(
		grammarID: String,
		languageID: String,
		capabilities: Set<SupportedLanguageCapability>,
		languageServerID: String,
		debugAdapterID: String? = nil
	) {
		self.grammarID = grammarID
		self.languageID = languageID
		self.capabilities = capabilities
		self.languageServerID = languageServerID
		self.debugAdapterID = debugAdapterID
	}
}

public enum SupportedLanguageCapabilityMatrixValidationError: Error, Equatable, Sendable {
	case duplicateGrammarID(String)
	case missingInventoryLanguage(String)
	case mismatchedLanguageID(grammarID: String, expected: String, actual: String)
	case missingRequiredCapability(grammarID: String, capability: SupportedLanguageCapability)
	case invalidLanguageServer(grammarID: String, serverID: String)
	case invalidDebugAdapter(grammarID: String, adapterID: String)
	case uncoveredCoreLanguageID(String)
}

public enum SupportedLanguageCapabilityMatrix {
	public static let core: [SupportedLanguageCapabilityRow] = [
		.init(grammarID: "c", languageID: "c", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "clangd", debugAdapterID: "lldb-dap"),
		.init(grammarID: "cpp", languageID: "cpp", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "clangd", debugAdapterID: "lldb-dap"),
		.init(grammarID: "csharp", languageID: "csharp", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer], languageServerID: "omnisharp"),
		.init(grammarID: "javascript", languageID: "javascript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
		.init(grammarID: "python", languageID: "python", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "pyright", debugAdapterID: "debugpy"),
		.init(grammarID: "tsx", languageID: "typescript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
		.init(grammarID: "typescript", languageID: "typescript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
	]

	public static func validationErrors(
		for rows: [SupportedLanguageCapabilityRow] = core,
		inventory: [BundledLanguage] = BundledLanguageInventory.languages,
		catalog: ManagedSupportCatalog = .bundled
	) -> [SupportedLanguageCapabilityMatrixValidationError] {
		var errors: [SupportedLanguageCapabilityMatrixValidationError] = []
		var grammarIDs: Set<String> = []
		for row in rows {
			if !grammarIDs.insert(row.grammarID).inserted {
				errors.append(.duplicateGrammarID(row.grammarID))
			}
			guard let language = inventory.first(where: { $0.grammarID == row.grammarID }) else {
				errors.append(.missingInventoryLanguage(row.grammarID))
				continue
			}
			if language.languageID != row.languageID {
				errors.append(.mismatchedLanguageID(grammarID: row.grammarID, expected: language.languageID, actual: row.languageID))
			}
			for capability in [SupportedLanguageCapability.syntaxHighlighting, .localSymbols, .languageServer] where !row.capabilities.contains(capability) {
				errors.append(.missingRequiredCapability(grammarID: row.grammarID, capability: capability))
			}
			if row.debugAdapterID == nil, row.capabilities.contains(.debugAdapter) {
				errors.append(.missingRequiredCapability(grammarID: row.grammarID, capability: .debugAdapter))
			}
			if let component = catalog.component(id: row.languageServerID),
			   component.kind == .languageServer,
			   component.tier == .core,
			   component.languageIDs.contains(row.languageID)
			{} else {
				errors.append(.invalidLanguageServer(grammarID: row.grammarID, serverID: row.languageServerID))
			}
			if let debugAdapterID = row.debugAdapterID {
				if let component = catalog.component(id: debugAdapterID),
				   component.kind == .debugAdapter,
				   component.tier == .core,
				   component.languageIDs.contains(row.languageID)
				{} else {
					errors.append(.invalidDebugAdapter(grammarID: row.grammarID, adapterID: debugAdapterID))
				}
			}
		}
		let coveredLanguageIDs = Set(rows.map(\.languageID))
		for component in catalog.coreComponents where component.kind == .languageServer || component.kind == .debugAdapter {
			for languageID in component.languageIDs where !coveredLanguageIDs.contains(languageID) {
				errors.append(.uncoveredCoreLanguageID(languageID))
			}
		}
		return errors
	}
}
