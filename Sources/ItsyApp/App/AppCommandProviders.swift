import ItsyEditor

@MainActor protocol AppCommandProvider {
	var featureID: String { get }
	func owns(commandID: String) -> Bool
	func register(_ command: Command, into registry: inout CommandRegistry) throws
}

@MainActor struct PrefixCommandProvider: AppCommandProvider {
	let featureID: String
	let prefixes: [String]

	func owns(commandID: String) -> Bool {
		prefixes.contains { commandID.hasPrefix($0) }
	}

	func register(_ command: Command, into registry: inout CommandRegistry) throws {
		try registry.register(command)
	}
}

@MainActor enum AppCommandProviderError: Error, Equatable {
	case duplicateFeatureID(String)
	case ambiguousCommandOwnership(String)
	case unownedCommandID(String)
}

@MainActor enum AppCommandProviderRegistry {
	static func owner(for commandID: String, providers: [any AppCommandProvider]? = nil) throws -> String {
		let providers = providers ?? AppCommandProviders.all
		try validate(providers)
		let owners = providers.filter { $0.owns(commandID: commandID) }
		guard owners.count == 1 else {
			if owners.isEmpty {
				throw AppCommandProviderError.unownedCommandID(commandID)
			}
			throw AppCommandProviderError.ambiguousCommandOwnership(commandID)
		}
		return owners[0].featureID
	}

	static func register(_ commands: [Command], into registry: inout CommandRegistry, providers: [any AppCommandProvider]? = nil) throws {
		let providers = providers ?? AppCommandProviders.all
		try validate(providers)
		for command in commands {
			let owners = providers.filter { $0.owns(commandID: command.id) }
			guard owners.count == 1 else {
				if owners.isEmpty {
					throw AppCommandProviderError.unownedCommandID(command.id)
				}
				throw AppCommandProviderError.ambiguousCommandOwnership(command.id)
			}
			try owners[0].register(command, into: &registry)
		}
	}

	private static func validate(_ providers: [any AppCommandProvider]) throws {
		var featureIDs = Set<String>()
		for provider in providers where !featureIDs.insert(provider.featureID).inserted {
			throw AppCommandProviderError.duplicateFeatureID(provider.featureID)
		}
	}
}

@MainActor enum AppCommandProviders {
	static let documents = PrefixCommandProvider(featureID: "documents", prefixes: ["file."])
	static let windows = PrefixCommandProvider(featureID: "windows", prefixes: ["view.", "history.", "pane."])
	static let palette = PrefixCommandProvider(featureID: "palette", prefixes: ["nav."])
	static let lsp = PrefixCommandProvider(featureID: "lsp", prefixes: ["lsp."])
	static let status = PrefixCommandProvider(featureID: "status", prefixes: ["integration."])
	static let settings = PrefixCommandProvider(featureID: "settings", prefixes: ["app.", "support."])
	static let find = PrefixCommandProvider(featureID: "find", prefixes: ["edit."])
	static let git = PrefixCommandProvider(featureID: "git", prefixes: ["git."])
	static let tasks = PrefixCommandProvider(featureID: "tasks", prefixes: ["task."])
	static let extensions = PrefixCommandProvider(featureID: "extensions", prefixes: ["extensions."])
	static let debugger = PrefixCommandProvider(featureID: "debugger", prefixes: ["debug."])
	static let terminal = PrefixCommandProvider(featureID: "terminal", prefixes: ["terminal."])
	static let problems = PrefixCommandProvider(featureID: "problems", prefixes: ["problems."])
	static let editor = PrefixCommandProvider(featureID: "editor", prefixes: ["editor."])
	static let keymap = PrefixCommandProvider(featureID: "keymap", prefixes: ["emacs.", "mode.", "vim."])

	static let all: [any AppCommandProvider] = [
		documents, windows, palette, lsp, status, settings, find, git, tasks, extensions, debugger, terminal, problems, editor, keymap,
	]
}
