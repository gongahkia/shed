import Foundation
import ItsyLSP
import Testing

@Test func framerExtractsCompletePayload() throws {
	let payload = Data(#"{"jsonrpc":"2.0","method":"initialized"}"#.utf8)
	var framer = LSPMessageFramer()

	let messages = try framer.append(LSPMessageFramer.frame(payload: payload))

	#expect(messages == [payload])
}

@Test func framerBuffersPartialPayload() throws {
	let payload = Data(#"{"jsonrpc":"2.0","method":"initialized"}"#.utf8)
	let frame = LSPMessageFramer.frame(payload: payload)
	let split = frame.index(frame.startIndex, offsetBy: 12)
	var framer = LSPMessageFramer()

	#expect(try framer.append(frame[..<split]).isEmpty)
	#expect(try framer.append(frame[split...]) == [payload])
}

@Test func framerExtractsMultiplePayloads() throws {
	let first = Data(#"{"jsonrpc":"2.0","id":1,"result":null}"#.utf8)
	let second = Data(#"{"jsonrpc":"2.0","method":"initialized"}"#.utf8)
	var input = LSPMessageFramer.frame(payload: first)
	input.append(LSPMessageFramer.frame(payload: second))
	var framer = LSPMessageFramer()

	let messages = try framer.append(input)

	#expect(messages == [first, second])
}

@Test func framerRejectsMissingContentLength() {
	var framer = LSPMessageFramer()

	#expect(throws: LSPFramingError.self) {
		_ = try framer.append(Data("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}".utf8))
	}
}

@Test func framerFrameUsesUTF8ByteCount() throws {
	let payload = Data(#"{"text":"å"}"#.utf8)
	let frame = LSPMessageFramer.frame(payload: payload)
	let text = try #require(String(data: frame, encoding: .utf8))

	#expect(text.hasPrefix("Content-Length: 13\r\n\r\n"))
}
