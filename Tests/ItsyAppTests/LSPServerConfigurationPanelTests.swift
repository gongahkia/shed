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
