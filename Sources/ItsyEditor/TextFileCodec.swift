import Foundation

public enum TextFileEncoding: String, Equatable, Sendable {
	case utf8
	case utf8BOM
	case utf16LittleEndian
	case utf16BigEndian
}

public enum TextFileNewlineStyle: String, Equatable, Sendable {
	case none
	case lf
	case crlf
	case cr
	case mixed
}

public enum TextFileNewlinePolicy: Equatable, Sendable {
	case preserve(TextFileNewlineStyle)
	case lf
	case crlf
	case cr

	var resolvedStyle: TextFileNewlineStyle {
		switch self {
		case let .preserve(style): style == .mixed || style == .none ? .lf : style
		case .lf: .lf
		case .crlf: .crlf
		case .cr: .cr
		}
	}
}

public struct TextFileSavePolicy: Equatable, Sendable {
	public var encoding: TextFileEncoding
	public var newline: TextFileNewlinePolicy

	public init(encoding: TextFileEncoding = .utf8, newline: TextFileNewlinePolicy = .preserve(.lf)) {
		self.encoding = encoding
		self.newline = newline
	}
}

public struct DecodedTextFile: Equatable, Sendable {
	public let text: String
	public let newlineStyle: TextFileNewlineStyle
	public let savePolicy: TextFileSavePolicy
	public let requiresEncodingChoice: Bool
}

public enum TextFileCodecError: Error, Equatable {
	case invalidTextBytes
	case cannotEncode(TextFileEncoding)
}

public enum TextFileCodec {
	public static func mappedUTF8SavePolicy(at url: URL) throws -> TextFileSavePolicy? {
		let data = try Data(contentsOf: url, options: .mappedIfSafe)
		return data.withUnsafeBytes { rawBuffer in
			let bytes = rawBuffer.bindMemory(to: UInt8.self)
			let bomLength = bytes.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0
			guard !bytes.contains(0), isValidUTF8(bytes, startingAt: bomLength) else {
				return nil
			}
			return TextFileSavePolicy(
				encoding: bomLength == 3 ? .utf8BOM : .utf8,
				newline: .preserve(newlineStyle(in: bytes, startingAt: bomLength))
			)
		}
	}

	public static func decode(_ data: Data) throws -> DecodedTextFile {
		let bytes = [UInt8](data)
		let decoded: (text: String, encoding: TextFileEncoding, ambiguous: Bool)
		if bytes.starts(with: [0xEF, 0xBB, 0xBF]), let text = String(data: data.dropFirst(3), encoding: .utf8) {
			decoded = (text, .utf8BOM, false)
		} else if bytes.starts(with: [0xFF, 0xFE]), let text = String(data: data.dropFirst(2), encoding: .utf16LittleEndian) {
			decoded = (text, .utf16LittleEndian, false)
		} else if bytes.starts(with: [0xFE, 0xFF]), let text = String(data: data.dropFirst(2), encoding: .utf16BigEndian) {
			decoded = (text, .utf16BigEndian, false)
		} else if data.contains(0), let decodedUTF16 = decodeUTF16WithoutBOM(data) {
			decoded = (decodedUTF16.text, decodedUTF16.encoding, true)
		} else if let text = String(data: data, encoding: .utf8) {
			decoded = (text, .utf8, false)
		} else if let decodedUTF16 = decodeUTF16WithoutBOM(data) {
			decoded = (decodedUTF16.text, decodedUTF16.encoding, true)
		} else {
			throw TextFileCodecError.invalidTextBytes
		}
		return decodedFile(text: decoded.text, encoding: decoded.encoding, requiresEncodingChoice: decoded.ambiguous)
	}

	public static func decode(_ data: Data, using encoding: TextFileEncoding) throws -> DecodedTextFile {
		let text: String?
		switch encoding {
		case .utf8:
			text = String(data: data, encoding: .utf8)
		case .utf8BOM:
			text = String(data: data.dropFirst(data.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0), encoding: .utf8)
		case .utf16LittleEndian:
			text = String(data: data.dropFirst(data.starts(with: [0xFF, 0xFE]) ? 2 : 0), encoding: .utf16LittleEndian)
		case .utf16BigEndian:
			text = String(data: data.dropFirst(data.starts(with: [0xFE, 0xFF]) ? 2 : 0), encoding: .utf16BigEndian)
		}
		guard let text else {
			throw TextFileCodecError.invalidTextBytes
		}
		return decodedFile(text: text, encoding: encoding, requiresEncodingChoice: false)
	}

	public static func encode(_ text: String, policy: TextFileSavePolicy) throws -> Data {
		let normalized = normalizeNewlines(in: text, style: policy.newline.resolvedStyle)
		switch policy.encoding {
		case .utf8:
			return Data(normalized.utf8)
		case .utf8BOM:
			return Data([0xEF, 0xBB, 0xBF]) + Data(normalized.utf8)
		case .utf16LittleEndian:
			guard let data = normalized.data(using: .utf16LittleEndian) else {
				throw TextFileCodecError.cannotEncode(.utf16LittleEndian)
			}
			return Data([0xFF, 0xFE]) + data
		case .utf16BigEndian:
			guard let data = normalized.data(using: .utf16BigEndian) else {
				throw TextFileCodecError.cannotEncode(.utf16BigEndian)
			}
			return Data([0xFE, 0xFF]) + data
		}
	}

	public static func newlineStyle(in text: String) -> TextFileNewlineStyle {
		var lf = 0
		var crlf = 0
		var cr = 0
		let scalars = Array(text.unicodeScalars.map(\.value))
		var index = 0
		while index < scalars.count {
			guard scalars[index] == 13 else {
				if scalars[index] == 10 {
					lf += 1
				}
				index += 1
				continue
			}
			let next = index + 1
			if next < scalars.count, scalars[next] == 10 {
				crlf += 1
				index += 2
			} else {
				cr += 1
				index += 1
			}
		}
		let kinds = [lf, crlf, cr].filter { $0 > 0 }.count
		return switch kinds {
		case 0: .none
		case 2...: .mixed
		default:
			lf > 0 ? .lf : crlf > 0 ? .crlf : .cr
		}
	}

	private static func decodeUTF16WithoutBOM(_ data: Data) -> (text: String, encoding: TextFileEncoding)? {
		guard data.count.isMultiple(of: 2) else {
			return nil
		}
		let candidates: [(String, TextFileEncoding)] = [
			(String(data: data, encoding: .utf16LittleEndian), .utf16LittleEndian),
			(String(data: data, encoding: .utf16BigEndian), .utf16BigEndian),
		].compactMap { text, encoding in
			text.map { ($0, encoding) }
		}
		return candidates.min { lhs, rhs in invalidScalarCount(in: lhs.0) < invalidScalarCount(in: rhs.0) }
	}

	private static func decodedFile(text: String, encoding: TextFileEncoding, requiresEncodingChoice: Bool) -> DecodedTextFile {
		let newlineStyle = newlineStyle(in: text)
		return DecodedTextFile(
			text: text,
			newlineStyle: newlineStyle,
			savePolicy: TextFileSavePolicy(encoding: encoding, newline: .preserve(newlineStyle)),
			requiresEncodingChoice: requiresEncodingChoice
		)
	}

	private static func newlineStyle(in bytes: UnsafeBufferPointer<UInt8>, startingAt start: Int) -> TextFileNewlineStyle {
		var lf = 0
		var crlf = 0
		var cr = 0
		var index = start
		while index < bytes.count {
			guard bytes[index] == 13 else {
				if bytes[index] == 10 {
					lf += 1
				}
				index += 1
				continue
			}
			if index + 1 < bytes.count, bytes[index + 1] == 10 {
				crlf += 1
				index += 2
			} else {
				cr += 1
				index += 1
			}
		}
		let kinds = [lf, crlf, cr].filter { $0 > 0 }.count
		return switch kinds {
		case 0: .none
		case 2...: .mixed
		default: lf > 0 ? .lf : crlf > 0 ? .crlf : .cr
		}
	}

	private static func isValidUTF8(_ bytes: UnsafeBufferPointer<UInt8>, startingAt start: Int) -> Bool {
		func continuation(_ index: Int) -> Bool {
			index < bytes.count && (bytes[index] & 0xC0) == 0x80
		}
		var index = start
		while index < bytes.count {
			switch bytes[index] {
			case 0x00 ... 0x7F:
				index += 1
			case 0xC2 ... 0xDF:
				guard continuation(index + 1) else { return false }
				index += 2
			case 0xE0:
				guard index + 2 < bytes.count, (0xA0 ... 0xBF).contains(bytes[index + 1]), continuation(index + 2) else { return false }
				index += 3
			case 0xE1 ... 0xEC, 0xEE ... 0xEF:
				guard continuation(index + 1), continuation(index + 2) else { return false }
				index += 3
			case 0xED:
				guard index + 2 < bytes.count, (0x80 ... 0x9F).contains(bytes[index + 1]), continuation(index + 2) else { return false }
				index += 3
			case 0xF0:
				guard index + 3 < bytes.count, (0x90 ... 0xBF).contains(bytes[index + 1]), continuation(index + 2), continuation(index + 3) else { return false }
				index += 4
			case 0xF1 ... 0xF3:
				guard continuation(index + 1), continuation(index + 2), continuation(index + 3) else { return false }
				index += 4
			case 0xF4:
				guard index + 3 < bytes.count, (0x80 ... 0x8F).contains(bytes[index + 1]), continuation(index + 2), continuation(index + 3) else { return false }
				index += 4
			default:
				return false
			}
		}
		return true
	}

	private static func invalidScalarCount(in text: String) -> Int {
		text.unicodeScalars.reduce(into: 0) { count, scalar in
			if scalar.value == 0 || scalar.properties.generalCategory == .control, scalar != "\n", scalar != "\r", scalar != "\t" {
				count += 1
			}
		}
	}

	private static func normalizeNewlines(in text: String, style: TextFileNewlineStyle) -> String {
		let lf = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
		return switch style {
		case .crlf: lf.replacingOccurrences(of: "\n", with: "\r\n")
		case .cr: lf.replacingOccurrences(of: "\n", with: "\r")
		case .lf, .none, .mixed: lf
		}
	}
}
