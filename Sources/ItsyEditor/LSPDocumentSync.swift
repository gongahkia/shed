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
		var synchronizedContent: String
		var pendingContent: String?
		var pendingTask: Task<Void, Never>?
		var isFlushing = false
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
		let params = LSPDidOpenTextDocumentParams(
			textDocument: LSPTextDocumentItem(
				uri: Self.uri(for: url),
				languageId: languageID,
				version: 1,
				text: content
			)
		)
		try await sink.send(method: LSPMethod.textDocumentDidOpen, params: try LSPAny(encoding: params))
		states[url] = DocumentState(
			languageID: languageID,
			version: 1,
			synchronizedContent: content,
			pendingContent: nil,
			pendingTask: nil
		)
		sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidOpen, version: 1))
	}

	public func didChange(url: URL, content: String) async {
		guard var state = states[url] else {
			return
		}
		if content == state.synchronizedContent, !state.isFlushing {
			state.pendingTask?.cancel()
			state.pendingContent = nil
			state.pendingTask = nil
			states[url] = state
			return
		}
		state.pendingTask?.cancel()
		state.pendingContent = content
		if debounceNanos == 0 {
			states[url] = state
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
		state.pendingTask = task
		states[url] = state
	}

	public func didSave(url: URL, text: String? = nil) async throws {
		guard states[url] != nil else {
			return
		}
		await flush(url: url)
		let params = LSPDidSaveTextDocumentParams(
			textDocument: LSPTextDocumentIdentifier(uri: Self.uri(for: url)),
			text: text
		)
		try await sink.send(method: LSPMethod.textDocumentDidSave, params: try LSPAny(encoding: params))
		sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidSave, version: states[url]?.version))
	}

	public func flushPendingChange(for url: URL) async {
		while states[url]?.isFlushing == true {
			try? await Task.sleep(nanoseconds: 1_000_000)
		}
		await flush(url: url)
	}

	public func didClose(url: URL) async throws {
		await flushPendingChange(for: url)
		guard let state = states[url] else {
			return
		}
		state.pendingTask?.cancel()
		states.removeValue(forKey: url)
		let params = LSPDidCloseTextDocumentParams(
			textDocument: LSPTextDocumentIdentifier(uri: Self.uri(for: url))
		)
		try await sink.send(method: LSPMethod.textDocumentDidClose, params: try LSPAny(encoding: params))
		sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidClose, version: nil))
	}

	private func flush(url: URL) async {
		guard var state = states[url], !state.isFlushing, let content = state.pendingContent else {
			return
		}
		guard let change = incrementalChange(from: state.synchronizedContent, to: content) else {
			state.pendingContent = nil
			state.pendingTask = nil
			states[url] = state
			return
		}
		state.isFlushing = true
		state.pendingContent = nil
		state.pendingTask = nil
		states[url] = state
		let version = state.version + 1
		let params = LSPDidChangeTextDocumentParams(
			textDocument: LSPVersionedTextDocumentIdentifier(uri: Self.uri(for: url), version: version),
			contentChanges: [change]
		)
		do {
			try await sink.send(method: LSPMethod.textDocumentDidChange, params: try LSPAny(encoding: params))
			guard var latest = states[url] else {
				return
			}
			latest.version = version
			latest.synchronizedContent = content
			latest.isFlushing = false
			states[url] = latest
			sent.append(LSPDocumentSyncMessage(url: url, method: LSPMethod.textDocumentDidChange, version: version))
			if latest.pendingContent != nil {
				await flush(url: url)
			}
		} catch {
			guard var latest = states[url] else {
				return
			}
			latest.isFlushing = false
			if latest.pendingContent == nil {
				latest.pendingContent = content
			}
			states[url] = latest
		}
	}

	private func incrementalChange(from old: String, to new: String) -> LSPTextDocumentContentChangeEvent? {
		guard old != new else {
			return nil
		}
		let oldScalars = Array(old.unicodeScalars)
		let newScalars = Array(new.unicodeScalars)
		var prefix = 0
		while prefix < oldScalars.count, prefix < newScalars.count, oldScalars[prefix] == newScalars[prefix] {
			prefix += 1
		}
		var suffix = 0
		while suffix < oldScalars.count - prefix,
		      suffix < newScalars.count - prefix,
		      oldScalars[oldScalars.count - suffix - 1] == newScalars[newScalars.count - suffix - 1]
		{
			suffix += 1
		}
		let oldPrefix = String(String.UnicodeScalarView(oldScalars[..<prefix]))
		let oldEnd = oldScalars.count - suffix
		let oldReplacement = String(String.UnicodeScalarView(oldScalars[prefix ..< oldEnd]))
		let newEnd = newScalars.count - suffix
		let newReplacement = String(String.UnicodeScalarView(newScalars[prefix ..< newEnd]))
		let range = LSPRange(
			start: LSPTextEditApply.utf16Position(forUTF8Offset: oldPrefix.utf8.count, in: old),
			end: LSPTextEditApply.utf16Position(forUTF8Offset: oldPrefix.utf8.count + oldReplacement.utf8.count, in: old)
		)
		return LSPTextDocumentContentChangeEvent(
			range: range,
			rangeLength: oldReplacement.utf16.count,
			text: newReplacement
		)
	}

	private static func uri(for url: URL) -> String {
		url.standardizedFileURL.absoluteString
	}
}
