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
