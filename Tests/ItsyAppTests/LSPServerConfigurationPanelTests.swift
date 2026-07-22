import AppKit
import Foundation
@testable import ItsyApp
import ItsyEditor
import Testing

@Test func lspConfigurationEntriesExposeCommandArgumentsAvailabilityAndRemediation() throws {
	let registry = LSPServerRegistry(configs: [
		LSPServerConfig(languageId: "swift", command: "/usr/bin/true", args: ["--stdio"], rootPatterns: ["Package.swift"]),
		LSPServerConfig(languageId: "typescript", command: "/missing/typescript-language-server", args: ["--stdio"], rootPatterns: ["package.json"]),
	])
	let entries = LSPServerConfigurationEntry.entries(registry: registry, environment: ["PATH": ""])
	let swift = try #require(entries.first { $0.languageID == "swift" })
	let typescript = try #require(entries.first { $0.languageID == "typescript" })
	#expect(swift.detailsText.contains("Command: /usr/bin/true"))
	#expect(swift.detailsText.contains("Arguments: --stdio"))
	#expect(swift.availability.contains("Available"))
	#expect(typescript.availability.contains("Unavailable"))
	#expect(typescript.remediation?.contains("install `/missing/typescript-language-server`") == true)
}

@Test @MainActor func lspMissingBannerOffersCopyableRemediationAndConfigurationAccess() throws {
	let missing = LSPServerRegistry.MissingBinary(languageID: "typescript", command: "typescript-language-server", hint: "npm i -g typescript-language-server")
	let banner = LSPMissingBanner(frame: NSRect(x: 0, y: 0, width: 640, height: 38))
	var copied: LSPServerRegistry.MissingBinary?
	var configurationRequests = 0
	banner.copyRequested = { copied = $0 }
	banner.configurationRequested = { configurationRequests += 1 }
	banner.show(missingBinary: missing)
	let buttons = banner.subviews.flatMap(\.subviews).compactMap { $0 as? NSButton }
	let copyButton = try #require(buttons.first { $0.title == "Copy command" })
	let configurationButton = try #require(buttons.first { $0.title == "Copy config path" })
	copyButton.performClick(nil)
	configurationButton.performClick(nil)
	#expect(copied == missing)
	#expect(configurationRequests == 1)
}
