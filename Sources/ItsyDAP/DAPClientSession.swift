import Foundation

public enum DAPClientEvent: Equatable, Sendable {
	case request(DAPRequestMessage)
	case event(DAPEventMessage)
}

public enum DAPClientError: Error, Equatable, Sendable {
	case unexpectedResponseSeq(Int)
	case responseFailure(DAPResponseMessage)
}

public protocol DAPClientTransport: Sendable {
	func write(_ data: Data) throws
}

public typealias DAPResponse = DAPResponseMessage

extension DAPProcessTransport: DAPClientTransport {}

public actor DAPClientSession {
	private let transport: any DAPClientTransport
	private var decoder = JSONDecoder()
	private var encoder = JSONEncoder()
	private var framer = DAPMessageFramer()
	private var nextSeq = 1
	private var pending: [Int: CheckedContinuation<DAPResponseMessage, Error>] = [:]
	private var eventContinuations: [UUID: DAPEventSubscription] = [:]

	public init(transport: any DAPClientTransport) {
		self.transport = transport
	}

	deinit {
		for subscription in eventContinuations.values {
			subscription.continuation.finish()
		}
		for continuation in pending.values {
			continuation.resume(throwing: CancellationError())
		}
	}

	public func sendRequest(command: String, arguments: DAPAny? = nil) async throws -> DAPResponse {
		let seq = nextSeq
		nextSeq += 1
		let message = DAPMessage.request(DAPRequestMessage(seq: seq, command: command, arguments: arguments))
		let frame = try DAPMessageFramer.frame(message: message, encoder: encoder)
		return try await withCheckedThrowingContinuation { continuation in
			pending[seq] = continuation
			do {
				try transport.write(frame)
			} catch {
				pending.removeValue(forKey: seq)
				continuation.resume(throwing: error)
			}
		}
	}

	public func on(event name: String? = nil) -> AsyncStream<DAPEventMessage> {
		let id = UUID()
		return AsyncStream { continuation in
			eventContinuations[id] = DAPEventSubscription(name: name, continuation: continuation)
			continuation.onTermination = { [weak self] _ in
				Task {
					await self?.removeEventContinuation(id)
				}
			}
		}
	}

	public func receive(_ data: Data) throws -> [DAPClientEvent] {
		let payloads = try framer.append(data)
		var events: [DAPClientEvent] = []
		for payload in payloads {
			let message = try decoder.decode(DAPMessage.self, from: payload)
			switch message {
			case let .request(request):
				events.append(.request(request))
			case let .event(event):
				emit(event)
				events.append(.event(event))
			case let .response(response):
				try route(response)
			}
		}
		return events
	}

	private func route(_ response: DAPResponseMessage) throws {
		guard let continuation = pending.removeValue(forKey: response.requestSeq) else {
			throw DAPClientError.unexpectedResponseSeq(response.requestSeq)
		}
		if response.success {
			continuation.resume(returning: response)
		} else {
			continuation.resume(throwing: DAPClientError.responseFailure(response))
		}
	}

	private func emit(_ event: DAPEventMessage) {
		for subscription in eventContinuations.values where subscription.name == nil || subscription.name == event.event {
			subscription.continuation.yield(event)
		}
	}

	private func removeEventContinuation(_ id: UUID) {
		eventContinuations.removeValue(forKey: id)
	}
}

private struct DAPEventSubscription {
	var name: String?
	var continuation: AsyncStream<DAPEventMessage>.Continuation
}
