import Foundation

public enum DAPClientState: Equatable, Sendable {
	case idle
	case initializing
	case configuring
	case running
	case stopped
	case terminated
}

public enum DAPClientEvent: Equatable, Sendable {
	case request(DAPRequestMessage)
	case event(DAPEventMessage)
}

public enum DAPClientError: Error, Equatable, Sendable {
	case invalidState(expected: [DAPClientState], actual: DAPClientState)
	case unexpectedResponseSeq(Int)
	case responseFailure(DAPResponseMessage)
}

public protocol DAPClientTransport: Sendable {
	func write(_ data: Data) throws
}

public typealias DAPResponse = DAPResponseMessage

extension DAPProcessTransport: DAPClientTransport {}

public actor DAPClientSession {
	public private(set) var state: DAPClientState = .idle

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
		try await sendRequestUnchecked(command: command, arguments: arguments)
	}

	@discardableResult
	public func initialize(clientCapabilities: DAPInitializeRequestArguments) async throws -> DAPResponse {
		try requireState([.idle])
		state = .initializing
		do {
			return try await sendRequestUnchecked(command: DAPCommand.initialize, arguments: try DAPAny(encoding: clientCapabilities))
		} catch {
			state = .idle
			throw error
		}
	}

	@discardableResult
	public func launch(arguments: DAPAny? = nil) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.launch, arguments: arguments)
	}

	@discardableResult
	public func attach(arguments: DAPAny? = nil) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.attach, arguments: arguments)
	}

	@discardableResult
	public func setBreakpoints(_ arguments: DAPSetBreakpointsArguments) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.setBreakpoints, arguments: try DAPAny(encoding: arguments))
	}

	@discardableResult
	public func configurationDone(arguments: DAPAny? = nil) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.configurationDone, arguments: arguments)
	}

	private func sendRequestUnchecked(command: String, arguments: DAPAny? = nil) async throws -> DAPResponse {
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
				apply(event: event)
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
			apply(response: response)
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

	private func apply(event: DAPEventMessage) {
		switch event.event {
		case DAPEvent.initialized where state == .initializing:
			state = .configuring
		case DAPEvent.stopped where state != .terminated:
			state = .stopped
		case DAPEvent.exited, DAPEvent.terminated:
			state = .terminated
		default:
			break
		}
	}

	private func apply(response: DAPResponseMessage) {
		switch response.command {
		case DAPCommand.configurationDone where state == .configuring:
			state = .running
		default:
			break
		}
	}

	private func requireState(_ expected: [DAPClientState]) throws {
		guard !expected.contains(state) else {
			return
		}
		throw DAPClientError.invalidState(expected: expected, actual: state)
	}
}

private struct DAPEventSubscription {
	var name: String?
	var continuation: AsyncStream<DAPEventMessage>.Continuation
}
