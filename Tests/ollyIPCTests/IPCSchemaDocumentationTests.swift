import Foundation
import XCTest
import ollyIPC

final class IPCSchemaDocumentationTests: XCTestCase {
    func testDocumentedSchemaMatchesIPCConstants() throws {
        let schema = try documentedSchema()
        let definitions = try XCTUnwrap(schema["$defs"] as? [String: Any])
        let version = try XCTUnwrap(definitions["protocolVersion"] as? [String: Any])
        let commandName = try XCTUnwrap(definitions["commandName"] as? [String: Any])
        let eventKind = try XCTUnwrap(definitions["eventKind"] as? [String: Any])
        let statePayload = try XCTUnwrap(definitions["statePayload"] as? [String: Any])
        let restoredWindowsPayload = try XCTUnwrap(definitions["restoredWindowsPayload"] as? [String: Any])
        let windowState = try XCTUnwrap(definitions["windowState"] as? [String: Any])
        let windowProperties = try XCTUnwrap(windowState["properties"] as? [String: Any])

        XCTAssertEqual(version["const"] as? Int, OllyIPC.protocolVersion)
        XCTAssertEqual(commandName["enum"] as? [String], IPCCommandName.allCases.map(\.rawValue))
        XCTAssertEqual(eventKind["enum"] as? [String], IPCEventKind.allCases.map(\.rawValue))
        XCTAssertNotNil(statePayload["properties"] as? [String: Any])
        XCTAssertEqual(
            restoredWindowsPayload["required"] as? [String],
            ["restoredCount", "skippedCount", "failedCount"]
        )
        XCTAssertNotNil(windowProperties["layoutOrder"])
        XCTAssertNotNil(windowProperties["isOffSpace"])
    }

    private func documentedSchema() throws -> [String: Any] {
        let url = packageRoot()
            .appendingPathComponent("docs", isDirectory: true)
            .appendingPathComponent("ipc.md")
        let markdown = try String(contentsOf: url, encoding: .utf8)
        let schemaText = try extractSchema(from: markdown)
        let data = Data(schemaText.utf8)
        let object = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(object as? [String: Any])
    }

    private func extractSchema(from markdown: String) throws -> String {
        let startMarker = "<!-- ipc-schema:start -->"
        let endMarker = "<!-- ipc-schema:end -->"
        let start = try XCTUnwrap(markdown.range(of: startMarker)?.upperBound)
        let end = try XCTUnwrap(markdown.range(of: endMarker)?.lowerBound)
        let block = markdown[start..<end]
        let fenceStart = try XCTUnwrap(block.range(of: "```json")?.upperBound)
        let fenceEnd = try XCTUnwrap(block.range(of: "```", range: fenceStart..<block.endIndex)?.lowerBound)
        return String(block[fenceStart..<fenceEnd]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func packageRoot() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
