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

@Test @MainActor func lspStatusPanelSurfacesCrashLoopErrorWhenNoOutputExists() {
	let snapshot = LSPStatusPanelSnapshot(
		key: LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/itsy-lsp")),
		status: "disabled",
		health: .degraded,
		server: "sourcekit-lsp",
		pid: nil,
		startDate: nil,
		lastError: "restart limit exceeded; use Restart to retry",
		output: []
	)
	#expect(LSPStatusPanel.detailsText(snapshot).contains("Lifecycle: disabled"))
	#expect(LSPStatusPanel.outputText(snapshot) == "restart limit exceeded; use Restart to retry")
}

@Test @MainActor func integrationHealthPanelRendersEveryHealthStateWithLocalDetails() {
	for state in IntegrationHealthState.allCases {
		let snapshot = IntegrationHealthPanelSnapshot(records: [IntegrationHealthRecord(
			key: IntegrationHealthKey(service: .dap, identifier: "debugpy"),
			lifecycle: .running,
			state: state,
			lastError: "adapter exited",
			remediation: "Restart debugging",
			detailLogReference: "dap://debugpy/session.log"
		)])
		let text = IntegrationHealthPanel.text(snapshot)
		#expect(text.contains("State: \(state.rawValue)"))
		#expect(text.contains("Last error: adapter exited"))
		#expect(text.contains("Remediation: Restart debugging"))
		#expect(text.contains("Log: dap://debugpy/session.log"))
	}
}
