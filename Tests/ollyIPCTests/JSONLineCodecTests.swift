import Foundation
import XCTest
import ollyIPC

final class JSONLineCodecTests: XCTestCase {
    func testEncodeDecodeJSONLine() throws {
        let message = TestMessage(text: "hello")
        let line = try JSONLineCodec.encodeLine(message)

        XCTAssertEqual(line.last, JSONLineCodec.lineFeed)
        XCTAssertEqual(try JSONLineCodec.decodeLine(TestMessage.self, from: line), message)
    }

    func testPopLineKeepsPartialDataBuffered() {
        var buffer = Data("{\"text\":\"one\"}\n{\"text\":\"two\"}".utf8)

        XCTAssertEqual(JSONLineCodec.popLine(from: &buffer), Data("{\"text\":\"one\"}".utf8))
        XCTAssertEqual(buffer, Data("{\"text\":\"two\"}".utf8))
        XCTAssertNil(JSONLineCodec.popLine(from: &buffer))
    }
}

private struct TestMessage: Codable, Equatable {
    let text: String
}
