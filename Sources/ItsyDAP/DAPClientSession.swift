import Foundation

public enum DAPClientState: Equatable, Sendable {
	case idle
	case initializing
	case configuring
	case running
	case stopped
	case disconnecting
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
	case transportTerminated(Int32?)
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
	private var cancelledRequestSequences = Set<Int>()
	private var ignoredResponseSequences = Set<Int>()
	private var eventContinuations: [UUID: DAPEventSubscription] = [:]
	private var terminationStatus: Int32?

	public init(transport: any DAPClientTransport) {
		self.transport = transport
	}

	public func sendRequest(command: String, arguments: DAPAny? = nil) async throws -> DAPResponse {
		guard state != .terminated, state != .disconnecting else {
			throw DAPClientError.transportTerminated(terminationStatus)
		}
		return try await sendRequestUnchecked(command: command, arguments: arguments)
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
		try requireState([.initializing, .configuring])
		return try await sendRequestUnchecked(command: DAPCommand.launch, arguments: arguments)
	}

	@discardableResult
	public func attach(arguments: DAPAny? = nil) async throws -> DAPResponse {
		try requireState([.initializing, .configuring])
		return try await sendRequestUnchecked(command: DAPCommand.attach, arguments: arguments)
	}

	@discardableResult
	public func setBreakpoints(_ arguments: DAPSetBreakpointsArguments) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.setBreakpoints, arguments: try DAPAny(encoding: arguments))
	}

	@discardableResult
	public func setExceptionBreakpoints(_ arguments: DAPSetExceptionBreakpointsArguments) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.setExceptionBreakpoints, arguments: try DAPAny(encoding: arguments))
	}

	@discardableResult
	public func configurationDone(arguments: DAPAny? = nil) async throws -> DAPResponse {
		try requireState([.configuring])
		return try await sendRequestUnchecked(command: DAPCommand.configurationDone, arguments: arguments)
	}

	public func allowConfigurationWithoutInitializedEvent() throws {
		try requireState([.initializing, .configuring])
		if state == .initializing {
			state = .configuring
		}
	}

	@discardableResult
	public func disconnect(arguments: DAPDisconnectArguments = DAPDisconnectArguments()) async throws -> DAPResponse {
		guard state != .terminated else {
			throw DAPClientError.transportTerminated(terminationStatus)
		}
		try requireState([.initializing, .configuring, .running, .stopped])
		state = .disconnecting
		do {
			return try await sendRequestUnchecked(command: DAPCommand.disconnect, arguments: try DAPAny(encoding: arguments))
		} catch {
			if state == .disconnecting {
				transitionToTerminated(status: nil)
			}
			throw error
		}
	}

	public func transportDidTerminate(status: Int32?) {
		transitionToTerminated(status: status)
	}

	private func sendRequestUnchecked(command: String, arguments: DAPAny? = nil) async throws -> DAPResponse {
		try Task.checkCancellation()
		let seq = nextSeq
		nextSeq += 1
		let message = DAPMessage.request(DAPRequestMessage(seq: seq, command: command, arguments: arguments))
		let frame = try DAPMessageFramer.frame(message: message, encoder: encoder)
		return try await withTaskCancellationHandler(operation: {
			try await withCheckedThrowingContinuation { continuation in
				guard state != .terminated else {
					continuation.resume(throwing: DAPClientError.transportTerminated(terminationStatus))
					return
				}
				guard cancelledRequestSequences.remove(seq) == nil else {
					ignoredResponseSequences.insert(seq)
					continuation.resume(throwing: CancellationError())
					return
				}
				pending[seq] = continuation
				do {
					try transport.write(frame)
				} catch {
					pending.removeValue(forKey: seq)
					continuation.resume(throwing: error)
				}
			}
		}, onCancel: {
			Task { await self.cancelPendingRequest(sequence: seq) }
		})
	}

	public func on(event name: String? = nil) -> AsyncStream<DAPEventMessage> {
		let id = UUID()
		return AsyncStream { continuation in
			eventContinuations[id] = DAPEventSubscription(name: name, continuation: continuation)
			continuation.onTermination = { [weak self] _ in
				Task { [weak self] in
					await self?.removeEventContinuation(id)
				}
			}
		}
	}

	public func receive(_ data: Data) throws -> [DAPClientEvent] {
		guard state != .terminated else {
			return []
		}
		let payloads = try framer.append(data)
		var events: [DAPClientEvent] = []
		for payload in payloads {
			guard state != .terminated else {
				break
			}
			let message = try decoder.decode(DAPMessage.self, from: payload)
			switch message {
			case let .request(request):
				events.append(.request(request))
			case let .event(event):
				if event.event == DAPEvent.exited || event.event == DAPEvent.terminated {
					emit(event)
					apply(event: event)
				} else {
					apply(event: event)
					emit(event)
				}
				events.append(.event(event))
			case let .response(response):
				try route(response)
			}
		}
		return events
	}

	private func route(_ response: DAPResponseMessage) throws {
		guard let continuation = pending.removeValue(forKey: response.requestSeq) else {
			if ignoredResponseSequences.remove(response.requestSeq) != nil {
				return
			}
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
		case DAPEvent.continued where state != .terminated:
			state = .running
		case DAPEvent.exited, DAPEvent.terminated:
			transitionToTerminated(status: nil)
		default:
			break
		}
	}

	private func apply(response: DAPResponseMessage) {
		switch response.command {
		case DAPCommand.configurationDone where state == .configuring:
			state = .running
		case DAPCommand.disconnect:
			transitionToTerminated(status: nil)
		default:
			break
		}
	}

	private func cancelPendingRequest(sequence: Int) {
		if let continuation = pending.removeValue(forKey: sequence) {
			ignoredResponseSequences.insert(sequence)
			continuation.resume(throwing: CancellationError())
		} else {
			cancelledRequestSequences.insert(sequence)
		}
	}

	private func transitionToTerminated(status: Int32?) {
		guard state != .terminated else {
			return
		}
		terminationStatus = status
		state = .terminated
		let pendingRequests = pending
		pending.removeAll()
		ignoredResponseSequences.formUnion(pendingRequests.keys)
		for continuation in pendingRequests.values {
			continuation.resume(throwing: DAPClientError.transportTerminated(status))
		}
		for subscription in eventContinuations.values {
			subscription.continuation.onTermination = nil
			subscription.continuation.finish()
		}
		eventContinuations.removeAll()
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
