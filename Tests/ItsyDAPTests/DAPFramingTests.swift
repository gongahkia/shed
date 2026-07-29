import Foundation
import ItsyDAP
import Testing

@Test func dapFramerExtractsCompletePayload() throws {
	let payload = Data(#"{"seq":1,"type":"event","event":"initialized"}"#.utf8)
	var framer = DAPMessageFramer()

	let messages = try framer.append(DAPMessageFramer.frame(payload: payload))

	#expect(messages == [payload])
}

@Test func dapFramerBuffersPartialPayload() throws {
	let payload = Data(#"{"seq":1,"type":"event","event":"initialized"}"#.utf8)
	let frame = DAPMessageFramer.frame(payload: payload)
	let split = frame.index(frame.startIndex, offsetBy: 12)
	var framer = DAPMessageFramer()

	#expect(try framer.append(frame[..<split]).isEmpty)
	#expect(try framer.append(frame[split...]) == [payload])
}

@Test func dapFramerExtractsMultiplePayloads() throws {
	let first = Data(#"{"seq":1,"type":"request","command":"threads"}"#.utf8)
	let second = Data(#"{"seq":2,"type":"event","event":"terminated"}"#.utf8)
	var input = DAPMessageFramer.frame(payload: first)
	input.append(DAPMessageFramer.frame(payload: second))
	var framer = DAPMessageFramer()

	let messages = try framer.append(input)

	#expect(messages == [first, second])
}

@Test func dapFramerRejectsMissingContentLength() {
	var framer = DAPMessageFramer()

	#expect(throws: DAPFramingError.self) {
		_ = try framer.append(Data("Content-Type: application/json\r\n\r\n{}".utf8))
	}
}

@Test func dapFramerFrameUsesUTF8ByteCount() throws {
	let payload = Data(#"{"output":"å"}"#.utf8)
	let frame = DAPMessageFramer.frame(payload: payload)
	let text = try #require(String(data: frame, encoding: .utf8))

	#expect(text.hasPrefix("Content-Length: 15\r\n\r\n"))
}
