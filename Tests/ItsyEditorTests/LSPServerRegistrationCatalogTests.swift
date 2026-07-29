import ItsyEditor
import Testing

@Test func lspRegistrationCatalogRegistersEverySupportedLanguageOnce() throws {
	let registrations = LSPServerRegistrationCatalog.bundled
	let supportedLanguageIDs = Set(BundledLanguageInventory.languages.compactMap { language -> String? in
		guard case .supported = language.support else {
			return nil
		}
		return language.languageID
	})
	#expect(Set(registrations.map(\.languageID)) == supportedLanguageIDs)
	#expect(registrations.count == supportedLanguageIDs.count)
	let typescript = try #require(registrations.first { $0.languageID == "typescript" })
	#expect(typescript.grammarIDs == ["tsx", "typescript"])
	#expect(typescript.config == .init(languageId: "typescript", command: "typescript-language-server", args: ["--stdio"], rootPatterns: ["tsconfig.json", "package.json", ".git"]))
	#expect(LSPServerRegistry.bundledDefaults == registrations.map(\.config))
}

@Test func lspRegistrationCatalogRejectsConflictingAliasServers() throws {
	var conflicting = try #require(BundledLanguageInventory.languages.first { $0.grammarID == "tsx" })
	conflicting.server = BundledLanguageServer(
		id: "conflicting-typescript-server",
		command: "conflicting-language-server",
		args: ["--stdio"],
		rootPatterns: [".git"],
		executableProbe: "conflicting-language-server",
		installHint: "Install the conflicting language server."
	)
	#expect(throws: LSPServerRegistrationCatalogError.conflictingServerConfig(languageID: "typescript")) {
		_ = try LSPServerRegistrationCatalog.build(from: BundledLanguageInventory.languages.filter { $0.grammarID != "tsx" } + [conflicting])
	}
}
