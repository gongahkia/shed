import Foundation
import ItsyLSP

public protocol LSPNotificationSink: Sendable {
	func send(method: String, params: LSPAny) async throws
}

public struct LSPDocumentSyncMessage: Equatable, Sendable {
	public let url: URL
	public let method: String
	public let version: Int?

	public init(url: URL, method: String, version: Int?) {
		self.url = url
		self.method = method
		self.version = version
	}
}

public actor LSPDocumentSyncCoordinator {
	public static let defaultDebounceMillis = 100

	private struct DocumentState {
		var languageID: String
		var version: Int
		var pendingContent: String?
		var pendingTask: Task<Void, Never>?
	}

	private let sink: LSPNotificationSink
	private let debounceNanos: UInt64
	private var states: [URL: DocumentState] = [:]
	private var sent: [LSPDocumentSyncMessage] = []

	public init(sink: LSPNotificationSink, debounceMillis: Int = LSPDocumentSyncCoordinator.defaultDebounceMillis) {
		self.sink = sink
		debounceNanos = UInt64(max(0, debounceMillis)) * 1_000_000
	}

	public var recordedMessages: [LSPDocumentSyncMessage] {
		sent
	}

	public func currentVersion(for url: URL) -> Int? {
		states[url]?.version
	}

	public func didOpen(url: URL, languageID: String, content: String) async throws {
		states[url] = DocumentState(languageID: languageID, version: 1, pendingContent: nil, pendingTask: nil)
		let params = LSPDidOpenTextDocumentParams(
			textDocument: LSPTextDocumentItem(
				uri: Self.uri(for: url),
				languageId: languageID,
				version: 1,
				text: content
			)
		)
		try await sink.send(method: LSPMethod.textDocumentDidOpen, params: try LSPAny(encoding: params))
		sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidOpen, version: 1))
	}

	public func didChange(url: URL, content: String) async {
		guard states[url] != nil else {
			return
		}
		states[url]?.pendingTask?.cancel()
		states[url]?.pendingContent = content
		if debounceNanos == 0 {
			await flush(url: url)
			return
		}
		let task = Task { [weak self, debounceNanos] in
			try? await Task.sleep(nanoseconds: debounceNanos)
			guard !Task.isCancelled else {
				return
			}
			await self?.flush(url: url)
		}
		states[url]?.pendingTask = task
	}

	public func flushPendingChange(for url: URL) async {
		await flush(url: url)
	}

	public func didClose(url: URL) async throws {
		states[url]?.pendingTask?.cancel()
		states.removeValue(forKey: url)
		let params = LSPDidCloseTextDocumentParams(
			textDocument: LSPTextDocumentIdentifier(uri: Self.uri(for: url))
		)
		try await sink.send(method: LSPMethod.textDocumentDidClose, params: try LSPAny(encoding: params))
		sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidClose, version: nil))
	}

	private func flush(url: URL) async {
		guard var state = states[url], let content = state.pendingContent else {
			return
		}
		state.version += 1
		state.pendingContent = nil
		state.pendingTask = nil
		states[url] = state
		let params = LSPDidChangeTextDocumentParams(
			textDocument: LSPVersionedTextDocumentIdentifier(uri: Self.uri(for: url), version: state.version),
			contentChanges: [LSPTextDocumentContentChangeEvent(text: content)]
		)
		do {
			try await sink.send(method: LSPMethod.textDocumentDidChange, params: try LSPAny(encoding: params))
			sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidChange, version: state.version))
		} catch {
			// drop the in-flight notification; next didChange will resend with a fresh version
		}
	}

	private static func uri(for url: URL) -> String {
		url.standardizedFileURL.absoluteString
	}
}
