import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func itsyClientCapabilitiesDefaultsMatchSpecAdvertisement() throws {
	let capabilities = ItsyClientCapabilities()
	let encoded = try JSONEncoder().encode(capabilities)
	let json = try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
	let textDocument = json?["textDocument"] as? [String: Any]
	let documentSymbol = textDocument?["documentSymbol"] as? [String: Any]
	#expect(documentSymbol?["hierarchicalDocumentSymbolSupport"] as? Bool == true)
	let completionItem = (textDocument?["completion"] as? [String: Any])?["completionItem"] as? [String: Any]
	#expect(completionItem?["snippetSupport"] as? Bool == true)
	#expect(completionItem?["labelDetailsSupport"] as? Bool == true)
	let resolveSupport = completionItem?["resolveSupport"] as? [String: Any]
	#expect(resolveSupport?["properties"] as? [String] == ["documentation", "detail", "additionalTextEdits"])
	let hover = textDocument?["hover"] as? [String: Any]
	#expect(hover?["contentFormat"] as? [String] == ["markdown", "plaintext"])
	let publish = textDocument?["publishDiagnostics"] as? [String: Any]
	#expect(publish?["relatedInformation"] as? Bool == true)
	let definition = textDocument?["definition"] as? [String: Any]
	#expect(definition?["linkSupport"] as? Bool == true)
	let rename = textDocument?["rename"] as? [String: Any]
	#expect(rename?["prepareSupport"] as? Bool == true)
	let codeAction = textDocument?["codeAction"] as? [String: Any]
	#expect(codeAction?["dataSupport"] as? Bool == true)
	#expect(codeAction?["isPreferredSupport"] as? Bool == true)
	#expect(codeAction?["disabledSupport"] as? Bool == true)
	let codeActionResolveSupport = codeAction?["resolveSupport"] as? [String: Any]
	#expect(codeActionResolveSupport?["properties"] as? [String] == ["edit"])
	let literalSupport = codeAction?["codeActionLiteralSupport"] as? [String: Any]
	let actionKind = literalSupport?["codeActionKind"] as? [String: Any]
	#expect((actionKind?["valueSet"] as? [String])?.contains("refactor.extract") == true)
	#expect((actionKind?["valueSet"] as? [String])?.contains("source.organizeImports") == true)
	let semanticTokens = textDocument?["semanticTokens"] as? [String: Any]
	let semanticRequests = semanticTokens?["requests"] as? [String: Any]
	#expect(semanticRequests?["range"] as? Bool == true)
	#expect((semanticRequests?["full"] as? [String: Any])?["delta"] as? Bool == true)
	#expect((semanticTokens?["tokenTypes"] as? [String])?.contains("decorator") == true)
	#expect(semanticTokens?["formats"] as? [String] == ["relative"])
	#expect(semanticTokens?["augmentsSyntaxTokens"] as? Bool == true)
	let inlayHint = textDocument?["inlayHint"] as? [String: Any]
	let inlayResolve = inlayHint?["resolveSupport"] as? [String: Any]
	#expect((inlayResolve?["properties"] as? [String])?.contains("label.location") == true)
	let foldingRange = textDocument?["foldingRange"] as? [String: Any]
	#expect(foldingRange?["lineFoldingOnly"] as? Bool == true)
	let foldingKind = foldingRange?["foldingRangeKind"] as? [String: Any]
	#expect(foldingKind?["valueSet"] as? [String] == ["comment", "imports", "region"])
	let documentHighlight = textDocument?["documentHighlight"] as? [String: Any]
	#expect(documentHighlight?["dynamicRegistration"] as? Bool == false)
	let callHierarchy = textDocument?["callHierarchy"] as? [String: Any]
	#expect(callHierarchy?["dynamicRegistration"] as? Bool == false)
	let typeHierarchy = textDocument?["typeHierarchy"] as? [String: Any]
	#expect(typeHierarchy?["dynamicRegistration"] as? Bool == false)

	let workspace = json?["workspace"] as? [String: Any]
	#expect(workspace?["applyEdit"] as? Bool == true)
	#expect(workspace?["configuration"] as? Bool == true)
	let workspaceEdit = workspace?["workspaceEdit"] as? [String: Any]
	#expect(workspaceEdit?["documentChanges"] as? Bool == true)
}

@Test func lspInitializeParamsItsyHelperEncodesRootUriAndCapabilities() throws {
	let root = URL(fileURLWithPath: "/tmp/itsy-init")
	let params = try LSPInitializeParams.itsy(processID: 12345, workspaceRoot: root)
	#expect(params.processId == 12345)
	#expect(params.rootUri == root.standardizedFileURL.absoluteString)
	if case let .object(capabilities) = params.capabilities {
		#expect(capabilities["textDocument"] != nil)
		#expect(capabilities["workspace"] != nil)
	} else {
		Issue.record("expected capabilities object, got \(params.capabilities)")
	}
}

@Test func lspInitializeParamsItsyHelperAllowsNilRoot() throws {
	let params = try LSPInitializeParams.itsy(processID: 0, workspaceRoot: nil)
	#expect(params.rootUri == nil)
}
