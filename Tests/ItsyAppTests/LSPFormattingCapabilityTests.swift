@testable import ItsyApp
import Testing

@Test func formattingCapabilitiesRejectOnlyKnownDisabledOperations() {
	let documentOnly = LSPFormattingCapabilities(document: true, range: false)
	#expect(documentOnly.requestError(for: .document) == nil)
	#expect(documentOnly.requestError(for: .range) == .rangeFormattingDisabled)

	let disabled = LSPFormattingCapabilities(document: false, range: false)
	#expect(disabled.requestError(for: .document) == .documentFormattingDisabled)
	#expect(disabled.requestError(for: .range) == .rangeFormattingDisabled)
}
