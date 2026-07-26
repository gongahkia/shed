import Foundation
import ItsyEditor
import Testing

@Test func integrationOutputConsoleRedactsSecretsAndConfiguredPatterns() async throws {
	let console = IntegrationOutputConsole(environment: ["SERVICE_TOKEN": "env-secret"])
	try await console.configure(redactionPatterns: ["customer-[0-9]+"])
	await console.append(
		service: .gitHub,
		identifier: "workspace",
		kind: .standardError,
		text: "SERVICE_TOKEN=inline env-secret Bearer bearer-secret ghp_abcdef https://user:password@example.com customer-123",
		errorReference: "Authorization: Basic credentials"
	)
	let entry = try #require(await console.entries().first)
	#expect(!entry.text.contains("inline"))
	#expect(!entry.text.contains("env-secret"))
	#expect(!entry.text.contains("bearer-secret"))
	#expect(!entry.text.contains("ghp_abcdef"))
	#expect(!entry.text.contains("user:password"))
	#expect(!entry.text.contains("customer-123"))
	#expect(!entry.errorReference!.contains("credentials"))
}

@Test func integrationOutputConsoleFiltersClearsAndBoundsRetention() async {
	let console = IntegrationOutputConsole(maximumEntries: 2, maximumCharacters: 100)
	let task = IntegrationOutputScope(service: .task, identifier: "build")
	let terminal = IntegrationOutputScope(service: .terminal, identifier: "/tmp/itsy")
	await console.append(service: task.service, identifier: task.identifier, kind: .command, text: "swift build")
	await console.append(service: terminal.service, identifier: terminal.identifier, kind: .standardOutput, text: "shell ready")
	await console.append(service: task.service, identifier: task.identifier, kind: .standardError, text: "compile failed")
	#expect(await console.entries().count == 2)
	#expect(await console.entries(scope: task, matching: "failed").count == 1)
	await console.clear(scope: task)
	#expect(await console.entries().count == 1)
	#expect(await console.entries().first?.scope == terminal)
}

@Test func integrationOutputConsoleRejectsInvalidConfiguredPattern() async {
	let console = IntegrationOutputConsole()
	await #expect(throws: IntegrationOutputConsoleError.invalidRedactionPattern("[")) {
		try await console.configure(redactionPatterns: ["["])
	}
}
