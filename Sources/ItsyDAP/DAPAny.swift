import Foundation

public enum DAPAny: Codable, Equatable, Sendable {
	case null
	case bool(Bool)
	case int(Int)
	case double(Double)
	case string(String)
	case array([DAPAny])
	case object([String: DAPAny])

	public init<Value: Encodable>(encoding value: Value, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) throws {
		let data = try encoder.encode(value)
		self = try decoder.decode(DAPAny.self, from: data)
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
		} else if let value = try? container.decode([DAPAny].self) {
			self = .array(value)
		} else {
			self = .object(try container.decode([String: DAPAny].self))
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
