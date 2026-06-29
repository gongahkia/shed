import Foundation

public struct DAPRequestMessage: Codable, Equatable, Sendable {
	public var seq: Int
	public var command: String
	public var arguments: DAPAny?

	public init(seq: Int, command: String, arguments: DAPAny? = nil) {
		self.seq = seq
		self.command = command
		self.arguments = arguments
	}
}

public struct DAPEventMessage: Codable, Equatable, Sendable {
	public var seq: Int
	public var event: String
	public var body: DAPAny?

	public init(seq: Int, event: String, body: DAPAny? = nil) {
		self.seq = seq
		self.event = event
		self.body = body
	}
}

public struct DAPResponseMessage: Codable, Equatable, Sendable {
	public var seq: Int
	public var requestSeq: Int
	public var success: Bool
	public var command: String
	public var message: String?
	public var body: DAPAny?

	public init(seq: Int, requestSeq: Int, success: Bool, command: String, message: String? = nil, body: DAPAny? = nil) {
		self.seq = seq
		self.requestSeq = requestSeq
		self.success = success
		self.command = command
		self.message = message
		self.body = body
	}

	private enum CodingKeys: String, CodingKey {
		case seq
		case requestSeq = "request_seq"
		case success
		case command
		case message
		case body
	}
}

public enum DAPMessage: Codable, Equatable, Sendable {
	case request(DAPRequestMessage)
	case event(DAPEventMessage)
	case response(DAPResponseMessage)

	private enum CodingKeys: String, CodingKey {
		case seq
		case type
		case command
		case arguments
		case event
		case body
		case requestSeq = "request_seq"
		case success
		case message
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		switch try container.decode(String.self, forKey: .type) {
		case "request":
			self = .request(DAPRequestMessage(
				seq: try container.decode(Int.self, forKey: .seq),
				command: try container.decode(String.self, forKey: .command),
				arguments: try Self.optionalValue(in: container, forKey: .arguments)
			))
		case "event":
			self = .event(DAPEventMessage(
				seq: try container.decode(Int.self, forKey: .seq),
				event: try container.decode(String.self, forKey: .event),
				body: try Self.optionalValue(in: container, forKey: .body)
			))
		case "response":
			self = .response(DAPResponseMessage(
				seq: try container.decode(Int.self, forKey: .seq),
				requestSeq: try container.decode(Int.self, forKey: .requestSeq),
				success: try container.decode(Bool.self, forKey: .success),
				command: try container.decode(String.self, forKey: .command),
				message: try container.decodeIfPresent(String.self, forKey: .message),
				body: try Self.optionalValue(in: container, forKey: .body)
			))
		default:
			throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "expected request, response, or event")
		}
	}

	public func encode(to encoder: Encoder) throws {
		var container = encoder.container(keyedBy: CodingKeys.self)
		switch self {
		case let .request(message):
			try container.encode(message.seq, forKey: .seq)
			try container.encode("request", forKey: .type)
			try container.encode(message.command, forKey: .command)
			try Self.encodeOptional(message.arguments, in: &container, forKey: .arguments)
		case let .event(message):
			try container.encode(message.seq, forKey: .seq)
			try container.encode("event", forKey: .type)
			try container.encode(message.event, forKey: .event)
			try Self.encodeOptional(message.body, in: &container, forKey: .body)
		case let .response(message):
			try container.encode(message.seq, forKey: .seq)
			try container.encode("response", forKey: .type)
			try container.encode(message.requestSeq, forKey: .requestSeq)
			try container.encode(message.success, forKey: .success)
			try container.encode(message.command, forKey: .command)
			try container.encodeIfPresent(message.message, forKey: .message)
			try Self.encodeOptional(message.body, in: &container, forKey: .body)
		}
	}

	private static func optionalValue(in container: KeyedDecodingContainer<CodingKeys>, forKey key: CodingKeys) throws -> DAPAny? {
		guard container.contains(key) else {
			return nil
		}
		return try container.decode(DAPAny.self, forKey: key)
	}

	private static func encodeOptional(_ value: DAPAny?, in container: inout KeyedEncodingContainer<CodingKeys>, forKey key: CodingKeys) throws {
		if let value {
			try container.encode(value, forKey: key)
		}
	}
}
