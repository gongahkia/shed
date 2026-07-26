@testable import ItsyApp
import Testing

@Test func formattingCapabilitiesRejectOnlyKnownDisabledOperations() {
	let documentOnly = LSPFormattingCapabilities(document: true, range: false)
	#expect(documentOnly.requestError(for: .document) == nil)
	#expect(documentOnly.requestError(for: .range) == .rangeFormattingDisabled)

	let disabled = LSPFormattingCapabilities(document: false, range: false)
	#expect(disabled.requestError(for: .document) == .documentFormattingDisabled)
	#expect(disabled.requestError(for: .range) == .rangeFormattingDisabled)
	#expect(disabled.requestError(for: .document)?.errorDescription == "The language server does not support document formatting.")
}

@Test func codeActionCapabilitiesRequireProviderButAllowIndependentResolveSupport() {
	let disabled = LSPCodeActionCapabilities(provider: false, resolve: true)
	#expect(disabled.requestError == .providerDisabled)
	#expect(disabled.requestError?.errorDescription == "The language server does not support code actions.")
	#expect(disabled.resolve)

	let enabled = LSPCodeActionCapabilities(provider: true, resolve: false)
	#expect(enabled.requestError == nil)
	#expect(!enabled.resolve)
}
