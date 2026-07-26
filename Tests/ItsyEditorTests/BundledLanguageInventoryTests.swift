import ItsyEditor
import Testing

@Test func bundledLanguageInventoryIsSelfConsistent() {
	#expect(BundledLanguageInventory.validationErrors().isEmpty)
	for language in BundledLanguageInventory.languages {
		switch language.support {
		case .supported:
			#expect(language.server != nil)
		case .unsupported(.noBundledServer):
			#expect(language.server == nil)
		}
	}
}

@Test func bundledLanguageInventoryRejectsDuplicateGrammarMappings() {
	let duplicate = BundledLanguageInventory.languages[0]
	let errors = BundledLanguageInventory.validationErrors(for: BundledLanguageInventory.languages + [duplicate])
	#expect(errors.contains(.duplicateGrammarID(duplicate.grammarID)))
	#expect(errors.contains(.duplicateFileExtension("bash")))
}
