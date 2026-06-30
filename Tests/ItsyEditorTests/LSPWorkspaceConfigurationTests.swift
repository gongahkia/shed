import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func workspaceConfigurationReturnsDirectStringForExactMatch() {
	let config = LSPServerConfig(
		languageId: "rust",
		command: "rust-analyzer",
		settings: ["rust-analyzer.checkOnSave": "true"]
	)
	let params = LSPConfigurationParams(items: [LSPConfigurationItem(section: "rust-analyzer.checkOnSave")])
	let result = LSPWorkspaceConfigurationHandler.respond(to: params, using: config)
	#expect(result == [.string("true")])
}

@Test func workspaceConfigurationGroupsNestedKeysByPrefix() {
	let config = LSPServerConfig(
		languageId: "pyright",
		command: "pyright-langserver",
		settings: [
			"python.analysis.typeCheckingMode": "strict",
			"python.analysis.diagnosticMode": "workspace",
			"python.formatting.provider": "black",
		]
	)
	let params = LSPConfigurationParams(items: [LSPConfigurationItem(section: "python.analysis")])
	let result = LSPWorkspaceConfigurationHandler.respond(to: params, using: config)
	#expect(result.count == 1)
	if case let .object(nested) = result[0] {
		#expect(nested["typeCheckingMode"] == .string("strict"))
		#expect(nested["diagnosticMode"] == .string("workspace"))
		#expect(nested["provider"] == nil)
	} else {
		Issue.record("expected object, got \(result[0])")
	}
}

@Test func workspaceConfigurationReturnsNullForMissingSection() {
	let config = LSPServerConfig(
		languageId: "go",
		command: "gopls",
		settings: [:]
	)
	let params = LSPConfigurationParams(items: [
		LSPConfigurationItem(section: "gopls.completeUnimported"),
		LSPConfigurationItem(section: nil),
	])
	let result = LSPWorkspaceConfigurationHandler.respond(to: params, using: config)
	#expect(result == [.null, .null])
}

@Test func workspaceConfigurationLSPAnyWrapsResultsInArray() {
	let config = LSPServerConfig(
		languageId: "swift",
		command: "/usr/bin/xcrun",
		settings: ["swift.sdk": "macosx"]
	)
	let params = LSPConfigurationParams(items: [
		LSPConfigurationItem(section: "swift.sdk"),
		LSPConfigurationItem(section: "swift.unknown"),
	])
	let any = LSPWorkspaceConfigurationHandler.respondLSPAny(to: params, using: config)
	#expect(any == .array([.string("macosx"), .null]))
}
