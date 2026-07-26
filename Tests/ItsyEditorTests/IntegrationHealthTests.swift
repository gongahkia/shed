import Foundation
import ItsyEditor
import Testing

@Test func integrationHealthStoreRetainsLifecycleErrorRemediationAndLogReference() async {
	let store = IntegrationHealthStore()
	let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
	await store.report(
		service: .lsp,
		identifier: "swift",
		lifecycle: .running,
		state: .degraded,
		lastError: "server exited 9",
		remediation: "Restart the language server",
		detailLogReference: "lsp://swift/session.log",
		updatedAt: timestamp
	)
	let record = await store.record(for: IntegrationHealthKey(service: .lsp, identifier: "swift"))
	#expect(record == IntegrationHealthRecord(
		key: IntegrationHealthKey(service: .lsp, identifier: "swift"),
		lifecycle: .running,
		state: .degraded,
		lastError: "server exited 9",
		remediation: "Restart the language server",
		detailLogReference: "lsp://swift/session.log",
		updatedAt: timestamp
	))
}

@Test func integrationHealthStoreCoversEveryExternalService() async {
	let store = IntegrationHealthStore()
	for service in IntegrationService.allCases {
		await store.report(service: service, lifecycle: .starting, state: .retrying)
	}
	#expect(await store.allRecords().map(\.key.service) == IntegrationService.allCases.sorted { $0.rawValue < $1.rawValue })
}
