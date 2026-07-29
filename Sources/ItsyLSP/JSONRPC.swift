import Foundation

public enum LSPAny: Codable, Equatable, Sendable {
	case null
	case bool(Bool)
	case int(Int)
	case double(Double)
	case string(String)
	case array([LSPAny])
	case object([String: LSPAny])

	public init<Value: Encodable>(encoding value: Value, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(value)
		self = try decoder.decode(LSPAny.self, from: data)
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if container.decodeNil() {
			self = .null
		} else if let value = try? container.decode(Bool.self) {
			self = .bool(value)
		} else if let value = try? container.decode(Int.self) {
			self = .int(value)
		} else if let value = try? container.decode(Double.self) {
			self = .double(value)
		} else if let value = try? container.decode(String.self) {
			self = .string(value)
		} else if let value = try? container.decode([LSPAny].self) {
			self = .array(value)
		} else {
			self = .object(try container.decode([String: LSPAny].self))
		}
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case .null:
			try container.encodeNil()
		case let .bool(value):
			try container.encode(value)
		case let .int(value):
			try container.encode(value)
		case let .double(value):
			try container.encode(value)
		case let .string(value):
			try container.encode(value)
		case let .array(value):
			try container.encode(value)
		case let .object(value):
			try container.encode(value)
		}
	}
}

public enum JSONRPCID: Codable, Equatable, Hashable, Sendable {
	case string(String)
	case int(Int)
	case double(Double)
	case null

	public init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if container.decodeNil() {
			self = .null
		} else if let value = try? container.decode(Int.self) {
			self = .int(value)
		} else if let value = try? container.decode(Double.self) {
			self = .double(value)
		} else {
			self = .string(try container.decode(String.self))
		}
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.singleValueContainer()
		switch self {
		case let .string(value):
			try container.encode(value)
		case let .int(value):
			try container.encode(value)
		case let .double(value):
			try container.encode(value)
		case .null:
			try container.encodeNil()
		}
	}
}

public struct JSONRPCError: Codable, Equatable, Sendable {
	public var code: Int
	public var message: String
	public var data: LSPAny?

	public init(code: Int, message: String, data: LSPAny? = nil) {
		self.code = code
		self.message = message
		self.data = data
	}
}

public enum JSONRPCErrorCode {
	public static let parseError = -32700
	public static let invalidRequest = -32600
	public static let methodNotFound = -32601
	public static let invalidParams = -32602
	public static let internalError = -32603
}

public struct JSONRPCRequestMessage: Codable, Equatable, Sendable {
	public var id: JSONRPCID
	public var method: String
	public var params: LSPAny?

	public init(id: JSONRPCID, method: String, params: LSPAny? = nil) {
		self.id = id
		self.method = method
		self.params = params
	}
}

public struct JSONRPCNotificationMessage: Codable, Equatable, Sendable {
	public var method: String
	public var params: LSPAny?

	public init(method: String, params: LSPAny? = nil) {
		self.method = method
		self.params = params
	}
}

public struct JSONRPCResponseMessage: Codable, Equatable, Sendable {
	public var id: JSONRPCID
	public var result: LSPAny?
	public var error: JSONRPCError?

	public init(id: JSONRPCID, result: LSPAny) {
		self.id = id
		self.result = result
		error = nil
	}

	public init(id: JSONRPCID, error: JSONRPCError) {
		self.id = id
		result = nil
		self.error = error
	}
}

public enum JSONRPCMessage: Codable, Equatable, Sendable {
	case request(JSONRPCRequestMessage)
	case notification(JSONRPCNotificationMessage)
	case response(JSONRPCResponseMessage)

	private enum CodingKeys: String, CodingKey {
		case jsonrpc
		case id
		case method
		case params
		case result
		case error
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		let version = try container.decode(String.self, forKey: .jsonrpc)
		guard version == "2.0" else {
			throw DecodingError.dataCorruptedError(forKey: .jsonrpc, in: container, debugDescription: "expected JSON-RPC 2.0")
		}
		if let method = try? container.decode(String.self, forKey: .method) {
			let params = try Self.optionalValue(in: container, forKey: .params)
			if container.contains(.id) {
				self = .request(JSONRPCRequestMessage(id: try container.decode(JSONRPCID.self, forKey: .id), method: method, params: params))
			} else {
				self = .notification(JSONRPCNotificationMessage(method: method, params: params))
			}
			return
		}
		guard container.contains(.id) else {
			throw DecodingError.keyNotFound(CodingKeys.id, DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "response id is required"))
		}
		if container.contains(.error) {
			self = .response(JSONRPCResponseMessage(id: try container.decode(JSONRPCID.self, forKey: .id), error: try container.decode(JSONRPCError.self, forKey: .error)))
			return
		}
		if container.contains(.result) {
			self = .response(JSONRPCResponseMessage(id: try container.decode(JSONRPCID.self, forKey: .id), result: try container.decode(LSPAny.self, forKey: .result)))
			return
		}
		throw DecodingError.dataCorrupted(DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "expected request, notification, or response"))
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.container(keyedBy: CodingKeys.self)
		try container.encode("2.0", forKey: .jsonrpc)
		switch self {
		case let .request(message):
			try container.encode(message.id, forKey: .id)
			try container.encode(message.method, forKey: .method)
			try Self.encodeOptional(message.params, in: &container, forKey: .params)
		case let .notification(message):
			try container.encode(message.method, forKey: .method)
			try Self.encodeOptional(message.params, in: &container, forKey: .params)
		case let .response(message):
			try container.encode(message.id, forKey: .id)
			if let error = message.error {
				try container.encode(error, forKey: .error)
			} else {
				try container.encode(message.result ?? .null, forKey: .result)
			}
		}
	}

	private static func optionalValue(in container: KeyedDecodingContainer<CodingKeys>, forKey key: CodingKeys) throws -> LSPAny? {
		guard container.contains(key) else {
			return nil
		}
		return try container.decode(LSPAny.self, forKey: key)
	}

	private static func encodeOptional(_ value: LSPAny?, in container: inout KeyedEncodingContainer<CodingKeys>, forKey key: CodingKeys) throws {
		if let value {
			try container.encode(value, forKey: key)
		}
	}
}
