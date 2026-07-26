import ItsyEditor
import ItsyLSP

struct LSPClientNotificationSink: LSPNotificationSink {
	let client: LSPProcessClient

	func send(method: String, params: LSPAny) async throws {
		try await client.sendNotification(method: method, params: params)
	}
}
