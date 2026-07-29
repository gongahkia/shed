import Foundation

public enum LSPFramingError: Error, Equatable {
	case invalidHeader(String)
	case missingContentLength
	case invalidContentLength(String)
}

public struct LSPMessageFramer {
	private static let separator = Data([13, 10, 13, 10])
	private var buffer = Data()

	public init() {}

	public mutating func append(_ data: Data) throws -> [Data] {
		buffer.append(data)
		var payloads: [Data] = []
		while let separatorRange = buffer.range(of: Self.separator) {
			let headerData = buffer[..<separatorRange.lowerBound]
			let contentLength = try Self.contentLength(from: headerData)
			let bodyStart = separatorRange.upperBound
			let bodyEnd = bodyStart + contentLength
			guard buffer.count >= bodyEnd else {
				break
			}
			payloads.append(Data(buffer[bodyStart ..< bodyEnd]))
			buffer.removeSubrange(buffer.startIndex ..< bodyEnd)
		}
		return payloads
	}

	public static func frame(payload: Data) -> Data {
		var data = Data("Content-Length: \(payload.count)\r\n\r\n".utf8)
		data.append(payload)
		return data
	}

	public static func frame(message: JSONRPCMessage, encoder: JSONEncoder = JSONEncoder()) throws -> Data {
		try frame(payload: encoder.encode(message))
	}

	private static func contentLength(from headerData: Data) throws -> Int {
		guard let headerText = String(data: headerData, encoding: .ascii) else {
			throw LSPFramingError.invalidHeader("headers must be ASCII")
		}
		var length: Int?
		for line in headerText.components(separatedBy: "\r\n") where !line.isEmpty {
			let parts = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
			guard parts.count == 2 else {
				throw LSPFramingError.invalidHeader(line)
			}
			let name = parts[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
			let value = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
			if name == "content-length" {
				guard let parsed = Int(value), parsed >= 0 else {
					throw LSPFramingError.invalidContentLength(value)
				}
				length = parsed
			}
		}
		guard let length else {
			throw LSPFramingError.missingContentLength
		}
		return length
	}
}
