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

@Test @MainActor func lspMissingBannerOffersCopyableRemediationAndSupportAccess() throws {
	let missing = LSPServerRegistry.MissingBinary(languageID: "typescript", command: "typescript-language-server", hint: "npm i -g typescript-language-server")
	let banner = LSPMissingBanner(frame: NSRect(x: 0, y: 0, width: 640, height: 38))
	var copied: LSPServerRegistry.MissingBinary?
	var supportRequests = 0
	banner.copyRequested = { copied = $0 }
	banner.supportRequested = { _ in supportRequests += 1 }
	banner.show(missingBinary: missing, fileURL: URL(fileURLWithPath: "/workspace/Project/app.ts"))
	let bannerButtons = buttons(in: banner)
	let copyButton = try #require(bannerButtons.first { $0.title == "Copy command" })
	let supportButton = try #require(bannerButtons.first { $0.title == "Manage support" })
	let detailsButton = try #require(bannerButtons.first { $0.title == "Details" })
	copyButton.performClick(nil)
	supportButton.performClick(nil)
	detailsButton.performClick(nil)
	let copyDiagnosticsButton = try #require(buttons(in: banner).first { $0.title == "Copy diagnostics" })
	copyDiagnosticsButton.performClick(nil)
	#expect(copied == missing)
	#expect(supportRequests == 1)
	#expect(detailsButton.title == "Hide details")
	#expect(NSPasteboard.general.string(forType: .string)?.contains("language: typescript") == true)
	#expect(NSPasteboard.general.string(forType: .string)?.contains("file: /workspace/Project/app.ts") == true)
}

@Test @MainActor func lspBannerRetainsActiveFileForARequestErrorWithoutFileContext() throws {
	let banner = LSPMissingBanner(frame: NSRect(x: 0, y: 0, width: 640, height: 38))
	let url = URL(fileURLWithPath: "/workspace/Project/config.toml")
	banner.show(unavailableLanguage: .init(languageID: "toml", reason: .noBundledServer), fileURL: url)
	banner.show(unavailableLanguage: .init(languageID: "toml", reason: .noBundledServer))
	let detailsButton = try #require(buttons(in: banner).first { $0.title == "Details" })
	detailsButton.performClick(nil)
	let copyDiagnosticsButton = try #require(buttons(in: banner).first { $0.title == "Copy diagnostics" })
	copyDiagnosticsButton.performClick(nil)
	#expect(NSPasteboard.general.string(forType: .string)?.contains("file: /workspace/Project/config.toml") == true)
}

private func buttons(in view: NSView) -> [NSButton] {
	(view.subviews.compactMap { $0 as? NSButton } + view.subviews.flatMap(buttons(in:)))
}
