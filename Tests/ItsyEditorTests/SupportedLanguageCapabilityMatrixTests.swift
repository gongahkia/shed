import ItsyEditor
import Testing

@Test func coreLanguageCapabilityMatrixMatchesManagedSupportContracts() {
	#expect(SupportedLanguageCapabilityMatrix.core == [
		.init(grammarID: "c", languageID: "c", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "clangd", debugAdapterID: "lldb-dap"),
		.init(grammarID: "cpp", languageID: "cpp", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "clangd", debugAdapterID: "lldb-dap"),
		.init(grammarID: "csharp", languageID: "csharp", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer], languageServerID: "omnisharp"),
		.init(grammarID: "javascript", languageID: "javascript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
		.init(grammarID: "python", languageID: "python", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "pyright", debugAdapterID: "debugpy"),
		.init(grammarID: "tsx", languageID: "typescript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
		.init(grammarID: "typescript", languageID: "typescript", capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter], languageServerID: "typescript-language-server", debugAdapterID: "vscode-js-debug"),
	])
	#expect(SupportedLanguageCapabilityMatrix.validationErrors().isEmpty)
}

@Test func coreLanguageCapabilityMatrixRejectsInvalidContracts() {
	let invalid = SupportedLanguageCapabilityRow(
		grammarID: "python",
		languageID: "python",
		capabilities: [.syntaxHighlighting, .localSymbols, .languageServer, .debugAdapter],
		languageServerID: "missing-server",
		debugAdapterID: "missing-adapter"
	)
	let errors = SupportedLanguageCapabilityMatrix.validationErrors(for: [invalid, invalid])
	#expect(errors.contains(.duplicateGrammarID("python")))
	#expect(errors.contains(.invalidLanguageServer(grammarID: "python", serverID: "missing-server")))
	#expect(errors.contains(.invalidDebugAdapter(grammarID: "python", adapterID: "missing-adapter")))
}
