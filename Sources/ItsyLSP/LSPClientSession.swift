import Foundation

public enum LSPClientState: Equatable, Sendable {
	case idle
	case initializing
	case running
	case shuttingDown
	case exited
}

public enum LSPClientEvent: Equatable, Sendable {
	case request(JSONRPCRequestMessage)
	case notification(JSONRPCNotificationMessage)
}

public enum LSPClientError: Error, Equatable, Sendable {
	case invalidState(expected: [LSPClientState], actual: LSPClientState)
	case unexpectedResponseID(JSONRPCID)
	case responseError(JSONRPCError)
}

public protocol LSPClientTransport: Sendable {
	func write(_ data: Data) throws
}

public actor LSPClientSession {
	public private(set) var state: LSPClientState = .idle

	private let transport: any LSPClientTransport
	private var decoder = JSONDecoder()
	private var framer = LSPMessageFramer()
	private var nextRequestID = 1
	private var pending: [JSONRPCID: CheckedContinuation<JSONRPCResponseMessage, Error>] = [:]

	public init(transport: any LSPClientTransport) {
		self.transport = transport
	}

	@discardableResult
	public func initialize(_ params: LSPInitializeParams) async throws -> LSPAny {
		try requireState([.idle])
		state = .initializing
		do {
			let response = try await sendRequestUnchecked(method: LSPMethod.initialize, params: try LSPAny(encoding: params))
			state = .running
			try writeNotificationUnchecked(method: LSPMethod.initialized, params: .object([:]))
			return response.result ?? .null
		} catch {
			state = .idle
			throw error
		}
	}

	@discardableResult
	public func sendRequest(method: String, params: LSPAny? = nil) async throws -> JSONRPCResponseMessage {
		try requireState([.running])
		return try await sendRequestUnchecked(method: method, params: params)
	}

	public func workspaceSymbol(query: String) async throws -> LSPWorkspaceSymbolResult {
		let response = try await sendRequest(
			method: LSPMethod.workspaceSymbol,
			params: try LSPAny(encoding: LSPWorkspaceSymbolParams(query: query))
		)
		return try LSPWorkspaceSymbolResult(result: response.result)
	}

	public func sendNotification(method: String, params: LSPAny? = nil) throws {
		try requireState([.running])
		try writeNotificationUnchecked(method: method, params: params)
	}

	public func respond(to id: JSONRPCID, result: LSPAny) throws {
		try requireState([.running, .initializing])
		let message = JSONRPCMessage.response(JSONRPCResponseMessage(id: id, result: result))
		try transport.write(LSPMessageFramer.frame(message: message))
	}

	public func respond(to id: JSONRPCID, error: JSONRPCError) throws {
		try requireState([.running, .initializing])
		let message = JSONRPCMessage.response(JSONRPCResponseMessage(id: id, error: error))
		try transport.write(LSPMessageFramer.frame(message: message))
	}

	public func shutdown() async throws {
		try requireState([.running])
		state = .shuttingDown
		do {
			_ = try await sendRequestUnchecked(method: LSPMethod.shutdown)
			try writeNotificationUnchecked(method: LSPMethod.exit)
			state = .exited
		} catch {
			state = .running
			throw error
		}
	}

	public func receive(_ data: Data) throws -> [LSPClientEvent] {
		let payloads = try framer.append(data)
		var events: [LSPClientEvent] = []
		for payload in payloads {
			let message = try decoder.decode(JSONRPCMessage.self, from: payload)
			switch message {
			case let .request(request):
				events.append(.request(request))
			case let .notification(notification):
				events.append(.notification(notification))
			case let .response(response):
				try route(response)
			}
		}
		return events
	}

	private func sendRequestUnchecked(method: String, params: LSPAny? = nil) async throws -> JSONRPCResponseMessage {
		let id = JSONRPCID.int(nextRequestID)
		nextRequestID += 1
		let message = JSONRPCMessage.request(JSONRPCRequestMessage(id: id, method: method, params: params))
		let frame = try LSPMessageFramer.frame(message: message)
		return try await withCheckedThrowingContinuation { continuation in
			pending[id] = continuation
			do {
				try transport.write(frame)
			} catch {
				pending.removeValue(forKey: id)
				continuation.resume(throwing: error)
			}
		}
	}

	private func writeNotificationUnchecked(method: String, params: LSPAny? = nil) throws {
		try transport.write(LSPMessageFramer.frame(message: .notification(JSONRPCNotificationMessage(method: method, params: params))))
	}

	private func route(_ response: JSONRPCResponseMessage) throws {
		guard let continuation = pending.removeValue(forKey: response.id) else {
			throw LSPClientError.unexpectedResponseID(response.id)
		}
		if let error = response.error {
			continuation.resume(throwing: LSPClientError.responseError(error))
		} else {
			continuation.resume(returning: response)
		}
	}

	private func requireState(_ expected: [LSPClientState]) throws {
		guard !expected.contains(state) else {
			return
		}
		throw LSPClientError.invalidState(expected: expected, actual: state)
	}
}
