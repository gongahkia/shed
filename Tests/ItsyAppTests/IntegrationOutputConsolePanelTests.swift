import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@Test @MainActor func integrationOutputConsolePanelRendersTimestampScopeAndErrorLink() {
	let scope = IntegrationOutputScope(service: .dap, identifier: "debugpy:/tmp/itsy")
	let snapshot = IntegrationOutputConsolePanelSnapshot(
		entries: [IntegrationOutputEntry(
			timestamp: Date(timeIntervalSince1970: 0),
			scope: scope,
			kind: .standardError,
			text: "adapter failed",
			errorReference: "dap://debugpy/tmp/itsy"
		)],
		scopes: [scope]
	)
	let text = IntegrationOutputConsolePanel.outputText(snapshot, scope: scope, query: "failed")
	#expect(text.contains("1970-01-01T00:00:00Z"))
	#expect(text.contains("[dap:debugpy:/tmp/itsy] [stderr]"))
	#expect(text.contains("error=dap://debugpy/tmp/itsy"))
	#expect(text.contains("adapter failed"))
	#expect(IntegrationOutputConsolePanel.outputText(snapshot, query: "missing") == "No integration output")
}
