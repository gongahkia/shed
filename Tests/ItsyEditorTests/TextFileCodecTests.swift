import Foundation
import ItsyEditor
import Testing

@Test func textFileCodecDetectsUTF8BOMUTF16AndNewlineStyles() throws {
	let utf8 = try TextFileCodec.decode(Data([0xEF, 0xBB, 0xBF]) + Data("alpha\r\nbeta\r\n".utf8))
	#expect(utf8.text == "alpha\r\nbeta\r\n")
	#expect(utf8.savePolicy.encoding == .utf8BOM)
	#expect(utf8.newlineStyle == .crlf)
	#expect(!utf8.requiresEncodingChoice)

	let utf16Body = try #require("alpha\n".data(using: .utf16LittleEndian))
	let utf16Data = Data([0xFF, 0xFE]) + utf16Body
	let utf16 = try TextFileCodec.decode(utf16Data)
	#expect(utf16.text == "alpha\n")
	#expect(utf16.savePolicy.encoding == .utf16LittleEndian)
	#expect(utf16.newlineStyle == .lf)
	#expect(!utf16.requiresEncodingChoice)
	#expect(TextFileCodec.newlineStyle(in: "one\ntwo\r\nthree\r") == .mixed)
}

@Test func textFileCodecRejectsInvalidBytesAndPreservesSelectedSavePolicy() throws {
	#expect(throws: TextFileCodecError.invalidTextBytes) {
		try TextFileCodec.decode(Data([0xFF, 0x00, 0x61]))
	}
	let policy = TextFileSavePolicy(encoding: .utf16BigEndian, newline: .crlf)
	let encoded = try TextFileCodec.encode("one\ntwo\rthree", policy: policy)
	let decoded = try TextFileCodec.decode(encoded)
	#expect(decoded.text == "one\r\ntwo\r\nthree")
	#expect(decoded.savePolicy.encoding == .utf16BigEndian)
	#expect(decoded.newlineStyle == .crlf)
}

@Test func textFileCodecFlagsUTF16WithoutBOMForChoice() throws {
	let data = try #require("alpha".data(using: .utf16LittleEndian))
	let decoded = try TextFileCodec.decode(data)
	#expect(decoded.text == "alpha")
	#expect(decoded.requiresEncodingChoice)
	#expect(decoded.savePolicy.encoding == .utf16LittleEndian)
	let chosen = try TextFileCodec.decode(data, using: .utf16LittleEndian)
	#expect(chosen.text == "alpha")
	#expect(!chosen.requiresEncodingChoice)
}

@Test func textFileCodecInspectsMappedUTF8WithoutDecodingTheFile() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	defer { try? FileManager.default.removeItem(at: directory) }
	let url = directory.appendingPathComponent("mapped.txt")
	try (Data([0xEF, 0xBB, 0xBF]) + Data("alpha\r\nbeta\r\n".utf8)).write(to: url)
	#expect(try TextFileCodec.mappedUTF8SavePolicy(at: url) == TextFileSavePolicy(encoding: .utf8BOM, newline: .preserve(.crlf)))
	try Data([0xFF, 0x00, 0x61]).write(to: url)
	#expect(try TextFileCodec.mappedUTF8SavePolicy(at: url) == nil)
}
