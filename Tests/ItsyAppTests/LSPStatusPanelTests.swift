import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@Test @MainActor func lspStatusPanelRendersEveryHealthStateAndTimestampedOutput() {
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-lsp"))
	for health in [LSPHealthState.starting, .ready, .degraded, .crashed, .unavailable] {
		let snapshot = LSPStatusPanelSnapshot(
			key: key,
			status: health.rawValue,
			health: health,
			server: "/usr/bin/sourcekit-lsp --stdio",
			pid: nil,
			startDate: nil,
			lastError: "",
			output: [LSPSessionOutput(timestamp: Date(timeIntervalSince1970: 0), kind: .protocolOutput, text: "invalid response")]
		)
		#expect(LSPStatusPanel.detailsText(snapshot).contains("Health: \(health.rawValue)"))
		#expect(LSPStatusPanel.outputText(snapshot).contains("[protocol] invalid response"))
	}
}
