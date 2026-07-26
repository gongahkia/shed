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
	      "message": "expected expression",
	      "relatedInformation": [
	        {
	          "location": {
	            "uri": "file:///tmp/helper.swift",
	            "range": {
	              "start": { "line": 1, "character": 2 },
	              "end": { "line": 1, "character": 8 }
	            }
	          },
	          "message": "declared here"
	        }
	      ]
	    }
	  ]
	}
	"""#.utf8)

	let params = try JSONDecoder().decode(LSPPublishDiagnosticsParams.self, from: data)

	#expect(params.uri == "file:///tmp/main.swift")
	#expect(params.version == 7)
	let diagnostic = try #require(params.diagnostics.first)
	#expect(diagnostic.range == LSPRange(
		start: LSPPosition(line: 2, character: 4),
		end: LSPPosition(line: 2, character: 10)
	))
	#expect(diagnostic.severity == .error)
	#expect(diagnostic.code == .string("E001"))
	#expect(diagnostic.source == "swift")
	#expect(diagnostic.message == "expected expression")
	#expect(diagnostic.relatedInformation == [
		LSPDiagnosticRelatedInformation(
			location: LSPLocation(
				uri: "file:///tmp/helper.swift",
				range: LSPRange(start: LSPPosition(line: 1, character: 2), end: LSPPosition(line: 1, character: 8))
			),
			message: "declared here"
		),
	])
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

@Test func completionParamsEncodeContextAndResultDecodesList() throws {
	let params = LSPCompletionParams(
		textDocument: LSPTextDocumentIdentifier(uri: "file:///tmp/main.swift"),
		position: LSPPosition(line: 2, character: 8),
		context: LSPCompletionContext(triggerKind: .triggerCharacter, triggerCharacter: ".")
	)
	let value = try LSPAny(encoding: params)

	#expect(value == .object([
		"textDocument": .object(["uri": .string("file:///tmp/main.swift")]),
		"position": .object(["line": .int(2), "character": .int(8)]),
		"context": .object(["triggerKind": .int(2), "triggerCharacter": .string(".")]),
	]))

	let data = Data(#"""
	{
	  "isIncomplete": false,
	  "items": [
	    {
	      "label": "print",
	      "detail": "Swift.print",
	      "documentation": { "kind": "markdown", "value": "prints" },
	      "filterText": "print",
	      "insertText": "print($1)",
	      "insertTextFormat": 2
	    }
	  ]
	}
	"""#.utf8)
	let result = try LSPCompletionResult(decoding: data)

	#expect(result.isIncomplete == false)
	#expect(result.items == [
		LSPCompletionItem(
			label: "print",
			detail: "Swift.print",
			documentation: .object(["kind": .string("markdown"), "value": .string("prints")]),
			filterText: "print",
			insertText: "print($1)",
			insertTextFormat: .snippet
		),
	])
}

@Test func completionItemDecodesCommitCharacters() throws {
	let item = try LSPCompletionItem(resolveResult: .object([
		"label": .string("print"),
		"commitCharacters": .array([.string("("), .string(".")]),
	]))
	#expect(item.commitCharacters == ["(", "."])
}

@Test func initializeResultDecodesCompletionTriggerCharacters() throws {
	let result = try LSPInitializeResult(result: .object([
		"capabilities": .object([
			"completionProvider": .object([
				"triggerCharacters": .array([.string("."), .string(":")]),
				"resolveProvider": .bool(true),
			]),
			"signatureHelpProvider": .object([
				"triggerCharacters": .array([.string("("), .string(",")]),
			]),
		]),
	]))

	#expect(result.capabilities.completionProvider?.triggerCharacters == [".", ":"])
	#expect(result.capabilities.completionProvider?.resolveProvider == true)
	#expect(result.capabilities.signatureHelpProvider?.triggerCharacters == ["(", ","])
}

@Test func initializeResultDecodesFormattingCapabilities() throws {
	let result = try LSPInitializeResult(result: .object([
		"capabilities": .object([
			"documentFormattingProvider": .bool(true),
			"documentRangeFormattingProvider": .bool(false),
		]),
	]))
	#expect(result.capabilities.documentFormattingProvider?.isEnabled == true)
	#expect(result.capabilities.documentRangeFormattingProvider?.isEnabled == false)
}

@Test func completionItemResolveDecodesMarkupAndPreservesData() throws {
	let original = LSPCompletionItem(
		label: "print",
		insertText: "print($1)",
		insertTextFormat: .snippet,
		commitCharacters: ["("],
		data: .object(["id": .int(42)])
	)
	let params = try LSPAny(encoding: original)

	let resolved = try LSPCompletionItem(resolveResult: .object([
		"label": .string("print"),
		"detail": .string("Swift.print"),
		"documentation": .object(["kind": .string("markdown"), "value": .string("prints a value")]),
		"additionalTextEdits": .array([
			.object([
				"range": .object([
					"start": .object(["line": .int(0), "character": .int(0)]),
					"end": .object(["line": .int(0), "character": .int(0)]),
				]),
				"newText": .string("import Foundation\n"),
			]),
		]),
		"data": .object(["id": .int(999)]),
	]))
	let merged = original.mergingResolvedFields(from: resolved)

	#expect(LSPMethod.completionItemResolve == "completionItem/resolve")
	#expect(params == .object([
		"label": .string("print"),
		"insertText": .string("print($1)"),
		"insertTextFormat": .int(2),
		"commitCharacters": .array([.string("(")]),
		"data": .object(["id": .int(42)]),
	]))
	#expect(merged.data == .object(["id": .int(42)]))
	#expect(merged.documentation == .object(["kind": .string("markdown"), "value": .string("prints a value")]))
	#expect(merged.insertText == "print($1)")
	#expect(merged.insertTextFormat == .snippet)
	#expect(merged.commitCharacters == ["("])
	#expect(merged.additionalTextEdits?.map(\.newText) == ["import Foundation\n"])
}

@Test func hoverResultDecodesMarkupAndMarkedStrings() throws {
	let markup = try LSPHoverResult(decoding: Data(#"""
	{
	  "contents": { "kind": "markdown", "value": "### Title\nbody" },
	  "range": {
	    "start": { "line": 1, "character": 2 },
	    "end": { "line": 1, "character": 6 }
	  }
	}
	"""#.utf8))
	let legacy = try LSPHoverResult(decoding: Data(#"""
	{
	  "contents": [
	    "plain",
	    { "language": "swift", "value": "let x = 1" }
	  ]
	}
	"""#.utf8))

	#expect(markup.hover == LSPHover(
		contents: .markup(LSPMarkupContent(kind: .markdown, value: "### Title\nbody")),
		range: LSPRange(start: LSPPosition(line: 1, character: 2), end: LSPPosition(line: 1, character: 6))
	))
	#expect(legacy.hover?.contents == .markedStrings([
		.string("plain"),
		.languageString(language: "swift", value: "let x = 1"),
	]))
}

@Test func signatureHelpParamsEncodeContextAndResultDecodesActiveParameter() throws {
	let params = LSPSignatureHelpParams(
		textDocument: LSPTextDocumentIdentifier(uri: "file:///tmp/main.swift"),
		position: LSPPosition(line: 4, character: 10),
		context: LSPSignatureHelpContext(triggerKind: .triggerCharacter, triggerCharacter: "(", isRetrigger: false)
	)
	let value = try LSPAny(encoding: params)
	let result = try LSPSignatureHelpResult(decoding: Data(#"""
	{
	  "signatures": [
	    {
	      "label": "print(_:separator:terminator:)",
	      "parameters": [
	        { "label": [6, 7] },
	        { "label": "separator:" }
	      ],
	      "activeParameter": 1
	    }
	  ],
	  "activeSignature": 0
	}
	"""#.utf8))

	#expect(LSPMethod.textDocumentSignatureHelp == "textDocument/signatureHelp")
	#expect(value == .object([
		"textDocument": .object(["uri": .string("file:///tmp/main.swift")]),
		"position": .object(["line": .int(4), "character": .int(10)]),
		"context": .object([
			"triggerKind": .int(2),
			"triggerCharacter": .string("("),
			"isRetrigger": .bool(false),
		]),
	]))
	#expect(result.help?.activeSignature == 0)
	#expect(result.help?.signatures.first?.activeParameter == 1)
	#expect(result.help?.signatures.first?.parameters?.first?.label == .offsets(start: 6, end: 7))
}

@Test func referencesParamsEncodeContextAndResultDecodesLocations() throws {
	let params = LSPReferenceParams(
		textDocument: LSPTextDocumentIdentifier(uri: "file:///tmp/main.swift"),
		position: LSPPosition(line: 3, character: 4),
		context: LSPReferenceContext(includeDeclaration: true)
	)
	let value = try LSPAny(encoding: params)
	let result = try LSPReferencesResult(decoding: Data(#"""
	[
	  {
	    "uri": "file:///tmp/main.swift",
	    "range": {
	      "start": { "line": 3, "character": 4 },
	      "end": { "line": 3, "character": 9 }
	    }
	  }
	]
	"""#.utf8))
	let none = try LSPReferencesResult(decoding: Data("null".utf8))

	#expect(value == .object([
		"textDocument": .object(["uri": .string("file:///tmp/main.swift")]),
		"position": .object(["line": .int(3), "character": .int(4)]),
		"context": .object(["includeDeclaration": .bool(true)]),
	]))
	#expect(result.locations == [
		LSPLocation(
			uri: "file:///tmp/main.swift",
			range: LSPRange(start: LSPPosition(line: 3, character: 4), end: LSPPosition(line: 3, character: 9))
		),
	])
	#expect(none.locations.isEmpty)
}

@Test func documentSymbolParamsEncodeAndResultDecodesHierarchyOrFlatList() throws {
	let params = try LSPAny(encoding: LSPDocumentSymbolParams(textDocument: LSPTextDocumentIdentifier(uri: "file:///tmp/main.swift")))
	let hierarchy = try LSPDocumentSymbolResult(decoding: Data(#"""
	[
	  {
	    "name": "AppShell",
	    "kind": 23,
	    "range": {
	      "start": { "line": 0, "character": 0 },
	      "end": { "line": 4, "character": 1 }
	    },
	    "selectionRange": {
	      "start": { "line": 0, "character": 7 },
	      "end": { "line": 0, "character": 15 }
	    },
	    "children": [
	      {
	        "name": "render",
	        "kind": 12,
	        "range": {
	          "start": { "line": 1, "character": 1 },
	          "end": { "line": 2, "character": 2 }
	        },
	        "selectionRange": {
	          "start": { "line": 1, "character": 6 },
	          "end": { "line": 1, "character": 12 }
	        }
	      }
	    ]
	  }
	]
	"""#.utf8))
	let flat = try LSPDocumentSymbolResult(decoding: Data(#"""
	[
	  {
	    "name": "run",
	    "kind": 12,
	    "location": {
	      "uri": "file:///tmp/main.swift",
	      "range": {
	        "start": { "line": 2, "character": 5 },
	        "end": { "line": 2, "character": 8 }
	      }
	    }
	  }
	]
	"""#.utf8))
	let none = try LSPDocumentSymbolResult(decoding: Data("null".utf8))

	#expect(LSPMethod.textDocumentDocumentSymbol == "textDocument/documentSymbol")
	#expect(params == .object(["textDocument": .object(["uri": .string("file:///tmp/main.swift")])]))
	#expect(hierarchy.documentSymbols.count == 1)
	#expect(hierarchy.documentSymbols.first?.children?.first?.name == "render")
	#expect(flat.symbolInformation.map(\.name) == ["run"])
	#expect(none.documentSymbols.isEmpty)
	#expect(none.symbolInformation.isEmpty)
}

@Test func workspaceSymbolParamsEncodeAndResultDecodesWorkspaceSymbols() throws {
	let params = try LSPAny(encoding: LSPWorkspaceSymbolParams(query: "App"))
	let result = try LSPWorkspaceSymbolResult(decoding: Data(#"""
	[
	  {
	    "name": "AppShell",
	    "kind": 23,
	    "tags": [1],
	    "containerName": "Sources",
	    "location": {
	      "uri": "file:///tmp/main.swift",
	      "range": {
	        "start": { "line": 1, "character": 6 },
	        "end": { "line": 1, "character": 14 }
	      }
	    },
	    "data": { "id": 7 }
	  },
	  {
	    "name": "Deferred",
	    "kind": 12,
	    "location": { "uri": "file:///tmp/deferred.swift" }
	  }
	]
	"""#.utf8))
	let symbols = result.workspaceSymbols

	#expect(LSPMethod.workspaceSymbol == "workspace/symbol")
	#expect(params == .object(["query": .string("App")]))
	#expect(symbols.count == 2)
	#expect(symbols[0].name == "AppShell")
	#expect(symbols[0].tags == [.deprecated])
	#expect(symbols[0].containerName == "Sources")
	#expect(symbols[0].data == .object(["id": .int(7)]))
	#expect(symbols[0].location.resolvedLocation?.range.start == LSPPosition(line: 1, character: 6))
	#expect(symbols[1].location.uri == "file:///tmp/deferred.swift")
	#expect(symbols[1].location.resolvedLocation == nil)
}

@Test func completionResultDecodesItemArrayAndNull() throws {
	let items = try LSPCompletionResult(decoding: Data(#"[{"label":"map","insertText":"map"}]"#.utf8))
	let none = try LSPCompletionResult(decoding: Data("null".utf8))

	#expect(items.items == [LSPCompletionItem(label: "map", insertText: "map")])
	#expect(none.items.isEmpty)
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

@Test func initializeParamsEncodeRequiredNulls() throws {
	let params = LSPInitializeParams(processId: nil, rootUri: nil)

	let value = try LSPAny(encoding: params)

	#expect(value == .object([
		"processId": .null,
		"rootUri": .null,
		"capabilities": .object([:]),
	]))
}

@Test func initializeParamsEncodeTypedInitializationOptions() throws {
	let params = LSPInitializeParams(
		processId: 1,
		rootUri: "file:///tmp/project",
		initializationOptions: .object(["enabled": .bool(true), "workers": .int(4)])
	)
	let value = try LSPAny(encoding: params)
	guard case let .object(object) = value else {
		Issue.record("expected initialize object")
		return
	}
	#expect(object["initializationOptions"] == .object(["enabled": .bool(true), "workers": .int(4)]))
}
