import Foundation

public enum JSONLineCodec {
    public static let lineFeed: UInt8 = 0x0A
    public static let carriageReturn: UInt8 = 0x0D

    public static func encodeLine<T: Encodable>(
        _ value: T,
        encoder: JSONEncoder = JSONEncoder()
    ) throws -> Data {
        var data = try encoder.encode(value)
        data.append(lineFeed)
        return data
    }

    public static func decodeLine<T: Decodable>(
        _ type: T.Type,
        from line: Data,
        decoder: JSONDecoder = JSONDecoder()
    ) throws -> T {
        var payload = line
        if payload.last == lineFeed {
            payload.removeLast()
        }
        if payload.last == carriageReturn {
            payload.removeLast()
        }
        return try decoder.decode(type, from: payload)
    }

    public static func popLine(from buffer: inout Data) -> Data? {
        guard let newlineIndex = buffer.firstIndex(of: lineFeed) else {
            return nil
        }

        var line = buffer.subdata(in: buffer.startIndex..<newlineIndex)
        buffer.removeSubrange(buffer.startIndex...newlineIndex)
        if line.last == carriageReturn {
            line.removeLast()
        }
        return line
    }

    public static func appendLineDelimiter(to data: Data) -> Data {
        guard data.last != lineFeed else {
            return data
        }

        var line = data
        line.append(lineFeed)
        return line
    }
}
