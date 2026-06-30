import Foundation
import XCTest
import ollyIPC
import ollyLayouts

final class UnixDomainSocketServerTests: XCTestCase {
    func testServerHandlesNewlineDelimitedJSONOverUnixSocket() throws {
        let socketPath = temporarySocketPath()
        let server = UnixDomainSocketServer(socketPath: IPCSocketPath(socketPath.rawValue)) { line in
            let request = try JSONLineCodec.decodeLine(TestRequest.self, from: line)
            let response = TestResponse(text: request.text.uppercased())
            return try JSONEncoder().encode(response)
        }
        defer {
            server.stop()
            try? FileManager.default.removeItem(at: socketPath.directoryURL)
        }

        try server.start()

        let client = UnixDomainSocketClient(socketPath: socketPath)
        let responseLine = try client.sendLine(try JSONEncoder().encode(TestRequest(text: "ping")))
        let response = try JSONLineCodec.decodeLine(TestResponse.self, from: responseLine)

        XCTAssertEqual(response, TestResponse(text: "PING"))
    }

    func testServerRejectsNonSocketPathOccupant() throws {
        let socketPath = temporarySocketPath()
        try FileManager.default.createDirectory(
            at: socketPath.directoryURL,
            withIntermediateDirectories: true
        )
        FileManager.default.createFile(atPath: socketPath.rawValue, contents: Data())
        defer {
            try? FileManager.default.removeItem(at: socketPath.directoryURL)
        }

        let server = UnixDomainSocketServer(socketPath: socketPath) { _ in nil }

        XCTAssertThrowsError(try server.start()) { error in
            XCTAssertEqual(error as? IPCSocketError, .socketPathOccupied(socketPath.rawValue))
        }
    }

    func testLineStreamReadsMultipleServerLines() throws {
        let socketPath = temporarySocketPath()
        let ack = try JSONEncoder().encode(
            IPCResponseEnvelope.ok(result: .subscribed(IPCSubscriptionInfo(eventKinds: [.engine])))
        )
        let event = try JSONEncoder().encode(
            IPCEventEnvelope(
                event: .engine(
                    .arranged(
                        EngineArrangedEvent(
                            displayID: 1,
                            engineID: FloatingLayoutEngine.engineID,
                            placementCount: 2,
                            appliedPlacementCount: 1
                        )
                    )
                )
            )
        )
        let server = UnixDomainSocketServer(socketPath: socketPath) { _ in
            var response = ack
            response.append(JSONLineCodec.lineFeed)
            response.append(event)
            return response
        }
        defer {
            server.stop()
            try? FileManager.default.removeItem(at: socketPath.directoryURL)
        }

        try server.start()

        let stream = try UnixDomainSocketClient(socketPath: socketPath).openLineStream()
        defer {
            stream.close()
        }
        try stream.sendLine(try JSONEncoder().encode(IPCRequestEnvelope(command: .subscribeEvents(.init()))))

        XCTAssertEqual(try JSONDecoder().decode(IPCResponseEnvelope.self, from: try stream.readLine()).status, .success)
        XCTAssertEqual(
            try JSONDecoder().decode(IPCEventEnvelope.self, from: try stream.readLine()).version,
            OllyIPC.protocolVersion
        )
    }

    private func temporarySocketPath() -> IPCSocketPath {
        let id = String(UUID().uuidString.prefix(8))
        let url = URL(fileURLWithPath: "/tmp", isDirectory: true)
            .appendingPathComponent("olly-\(id)", isDirectory: true)
            .appendingPathComponent("olly.sock")
        return IPCSocketPath(url.path)
    }
}

private struct TestRequest: Codable {
    let text: String
}

private struct TestResponse: Codable, Equatable {
    let text: String
}
