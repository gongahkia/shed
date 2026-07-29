@testable import ItsyApp
import ItsyEditor
import Testing

@MainActor @Test func appCommandProvidersRouteFeatureCommandsAndPreserveRegistrationOrder() throws {
	let commands = [
		Command(id: "file.open", title: "Open") {},
		Command(id: "lsp.references", title: "References") {},
		Command(id: "terminal.toggle", title: "Terminal") {},
		Command(id: "vim.operator.delete", title: "Delete", isHidden: true) {},
	]
	var registry = CommandRegistry()
	try AppCommandProviderRegistry.register(commands, into: &registry)
	#expect(registry.allCommands.map(\.id) == commands.map(\.id))
	#expect(try AppCommandProviderRegistry.owner(for: "file.open") == "documents")
	#expect(try AppCommandProviderRegistry.owner(for: "lsp.references") == "lsp")
	#expect(try AppCommandProviderRegistry.owner(for: "terminal.toggle") == "terminal")
	#expect(try AppCommandProviderRegistry.owner(for: "vim.operator.delete") == "keymap")
}

@MainActor @Test func appCommandProvidersRejectMissingDuplicateAndAmbiguousOwnership() {
	let first = PrefixCommandProvider(featureID: "first", prefixes: ["test."])
	let duplicate = PrefixCommandProvider(featureID: "first", prefixes: ["other."])
	let second = PrefixCommandProvider(featureID: "second", prefixes: ["test."])
	#expect(throws: AppCommandProviderError.duplicateFeatureID("first")) {
		try AppCommandProviderRegistry.owner(for: "test.command", providers: [first, duplicate])
	}
	#expect(throws: AppCommandProviderError.ambiguousCommandOwnership("test.command")) {
		try AppCommandProviderRegistry.owner(for: "test.command", providers: [first, second])
	}
	#expect(throws: AppCommandProviderError.unownedCommandID("missing.command")) {
		try AppCommandProviderRegistry.owner(for: "missing.command", providers: [first])
	}
}

@MainActor @Test func appCoordinatorRegistersAllBuiltInCommandsThroughFeatureProviders() {
	let coordinator = AppCoordinator(documentController: ItsyDocumentController())
	let commands = coordinator.makeCommandRegistry().allCommands
	#expect(commands.contains { $0.id == "file.open" })
	#expect(commands.contains { $0.id == "lsp.references" })
	#expect(commands.contains { $0.id == "terminal.toggle" })
	#expect(commands.contains { $0.id == "vim.operator.delete" && $0.isHidden })
	#expect(Set(commands.map(\.id)).count == commands.count)
}
