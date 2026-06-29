import Foundation
import ItsyLSP
import Testing

@Test func publishDiagnosticsDecodesSeverityAndStringCode() throws {
	let data = Data(#"""
	{
	  "uri": "file:///tmp/main.swift",
	  "version": 7,
	  "diagnostics": [
	    {
	      "range": {
	        "start": { "line": 2, "character": 4 },
	        "end": { "line": 2, "character": 10 }
	      },
	      "severity": 1,
	      "code": "E001",
	      "source": "swift",
	      "message": "expected expression"
	    }
	  ]
	}
	"""#.utf8)

	let params = try JSONDecoder().decode(LSPPublishDiagnosticsParams.self, from: data)

	#expect(params.uri == "file:///tmp/main.swift")
	#expect(params.version == 7)
	let diagnostic = try #require(params.diagnostics.first)
	#expect(diagnostic.range == LSPRange(start: LSPPosition(line: 2, character: 4), end: LSPPosition(line: 2, character: 10)))
	#expect(diagnostic.severity == .error)
	#expect(diagnostic.code == .string("E001"))
	#expect(diagnostic.source == "swift")
	#expect(diagnostic.message == "expected expression")
}

@Test func didOpenNotificationEncodesTextDocumentPayload() throws {
	let params = LSPDidOpenTextDocumentParams(textDocument: LSPTextDocumentItem(
		uri: "file:///tmp/main.ts",
		languageId: "typescript",
		version: 1,
		text: "const value = 1;\n"
	))
	let message = JSONRPCMessage.notification(JSONRPCNotificationMessage(
		method: LSPMethod.textDocumentDidOpen,
		params: .object([
			"textDocument": .object([
				"uri": .string(params.textDocument.uri),
				"languageId": .string(params.textDocument.languageId),
				"version": .int(params.textDocument.version),
				"text": .string(params.textDocument.text),
			]),
		])
	))

	let payload = try JSONEncoder().encode(message)
	let decoded = try JSONDecoder().decode(JSONRPCMessage.self, from: payload)

	#expect(decoded == message)
}

@Test func didChangeParamsEncodeFullDocumentChange() throws {
	let params = LSPDidChangeTextDocumentParams(
		textDocument: LSPVersionedTextDocumentIdentifier(uri: "file:///tmp/main.ts", version: 2),
		contentChanges: [LSPTextDocumentContentChangeEvent(text: "let value = 2;\n")]
	)

	let data = try JSONEncoder().encode(params)
	let decoded = try JSONDecoder().decode(LSPDidChangeTextDocumentParams.self, from: data)

	#expect(decoded == params)
}
