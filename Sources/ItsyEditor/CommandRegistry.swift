public struct Command {
	public let id: String
	public let title: String
	public let defaultKey: String?
	public let run: () -> Void

	public init(id: String, title: String, defaultKey: String? = nil, run: @escaping () -> Void) {
		self.id = id
		self.title = title
		self.defaultKey = defaultKey
		self.run = run
	}
}

public enum CommandRegistryError: Error, Equatable {
	case duplicateID(String)
	case missingID(String)
}

public final class CommandRegistry {
	private var orderedCommands: [Command] = []
	private var commandsByID: [String: Command] = [:]

	public init() {}

	public var commands: [Command] {
		orderedCommands
	}

	public func register(_ command: Command) throws {
		guard commandsByID[command.id] == nil else {
			throw CommandRegistryError.duplicateID(command.id)
		}
		orderedCommands.append(command)
		commandsByID[command.id] = command
	}

	public func register(_ commands: [Command]) throws {
		for command in commands {
			try register(command)
		}
	}

	public func command(id: String) -> Command? {
		commandsByID[id]
	}

	public func run(id: String) throws {
		guard let command = commandsByID[id] else {
			throw CommandRegistryError.missingID(id)
		}
		command.run()
	}
}
