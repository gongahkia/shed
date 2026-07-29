import Foundation

public enum WorkspaceTaskConfigurationScope: String, Codable, Equatable, Sendable {
	case global
	case project
}

public struct WorkspaceTaskInput: Codable, Equatable, Sendable {
	public var id: String
	public var prompt: String
	public var defaultValue: String?
	public var secret: Bool

	public init(id: String, prompt: String, defaultValue: String? = nil, secret: Bool = false) {
		self.id = id
		self.prompt = prompt
		self.defaultValue = defaultValue
		self.secret = secret
	}

	private enum CodingKeys: String, CodingKey {
		case id
		case prompt
		case defaultValue = "default"
		case secret
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		prompt = try container.decode(String.self, forKey: .prompt)
		defaultValue = try container.decodeIfPresent(String.self, forKey: .defaultValue)
		secret = try container.decodeIfPresent(Bool.self, forKey: .secret) ?? false
	}
}

public enum WorkspaceTaskReveal: String, Codable, Equatable, Sendable {
	case always
	case silent
	case onFailure = "on_failure"
}

public struct WorkspaceTaskPresentation: Codable, Equatable, Sendable {
	public var reveal: WorkspaceTaskReveal
	public var focus: Bool
	public var dedicated: Bool
	public var showResolvedCommand: Bool

	public init(
		reveal: WorkspaceTaskReveal = .onFailure,
		focus: Bool = false,
		dedicated: Bool = false,
		showResolvedCommand: Bool = false
	) {
		self.reveal = reveal
		self.focus = focus
		self.dedicated = dedicated
		self.showResolvedCommand = showResolvedCommand
	}

	private enum CodingKeys: String, CodingKey {
		case reveal
		case focus
		case dedicated
		case showResolvedCommand = "show_resolved_command"
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		reveal = try container.decodeIfPresent(WorkspaceTaskReveal.self, forKey: .reveal) ?? .onFailure
		focus = try container.decodeIfPresent(Bool.self, forKey: .focus) ?? false
		dedicated = try container.decodeIfPresent(Bool.self, forKey: .dedicated) ?? false
		showResolvedCommand = try container.decodeIfPresent(Bool.self, forKey: .showResolvedCommand) ?? false
	}
}

public struct WorkspaceTaskDefinition: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var command: String
	public var arguments: [String]
	public var cwd: String?
	public var environment: [String: String]
	public var inputs: [WorkspaceTaskInput]
	public var dependsOn: [String]
	public var isBackground: Bool
	public var watch: WorkspaceTaskWatch?
	public var presentation: WorkspaceTaskPresentation
	public var problemMatchers: [String]

	public init(
		id: String,
		label: String? = nil,
		command: String,
		arguments: [String] = [],
		cwd: String? = nil,
		environment: [String: String] = [:],
		inputs: [WorkspaceTaskInput] = [],
		dependsOn: [String] = [],
		isBackground: Bool = false,
		watch: WorkspaceTaskWatch? = nil,
		presentation: WorkspaceTaskPresentation = WorkspaceTaskPresentation(),
		problemMatchers: [String] = []
	) {
		self.id = id
		self.label = label ?? id
		self.command = command
		self.arguments = arguments
		self.cwd = cwd
		self.environment = environment
		self.inputs = inputs
		self.dependsOn = dependsOn
		self.isBackground = isBackground
		self.watch = watch
		self.presentation = presentation
		self.problemMatchers = problemMatchers
	}

	private enum CodingKeys: String, CodingKey {
		case id
		case label
		case command
		case arguments
		case cwd
		case environment = "env"
		case inputs
		case dependsOn = "depends_on"
		case isBackground = "is_background"
		case watch
		case presentation
		case problemMatchers = "problem_matchers"
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		label = try container.decodeIfPresent(String.self, forKey: .label) ?? id
		command = try container.decode(String.self, forKey: .command)
		arguments = try container.decodeIfPresent([String].self, forKey: .arguments) ?? []
		cwd = try container.decodeIfPresent(String.self, forKey: .cwd)
		environment = try container.decodeIfPresent([String: String].self, forKey: .environment) ?? [:]
		inputs = try container.decodeIfPresent([WorkspaceTaskInput].self, forKey: .inputs) ?? []
		dependsOn = try container.decodeIfPresent([String].self, forKey: .dependsOn) ?? []
		isBackground = try container.decodeIfPresent(Bool.self, forKey: .isBackground) ?? false
		watch = try container.decodeIfPresent(WorkspaceTaskWatch.self, forKey: .watch)
		presentation = try container.decodeIfPresent(WorkspaceTaskPresentation.self, forKey: .presentation) ?? WorkspaceTaskPresentation()
		problemMatchers = try container.decodeIfPresent([String].self, forKey: .problemMatchers) ?? []
	}
}

public struct WorkspaceTaskConfiguration: Codable, Equatable, Sendable {
	public static let currentVersion = 1
	public var version: Int
	public var scope: WorkspaceTaskConfigurationScope
	public var tasks: [WorkspaceTaskDefinition]

	public init(version: Int = currentVersion, scope: WorkspaceTaskConfigurationScope, tasks: [WorkspaceTaskDefinition]) {
		self.version = version
		self.scope = scope
		self.tasks = tasks
	}

	private enum CodingKeys: String, CodingKey {
		case version
		case scope
		case tasks
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		version = try container.decode(Int.self, forKey: .version)
		scope = try container.decodeIfPresent(WorkspaceTaskConfigurationScope.self, forKey: .scope) ?? .project
		tasks = try container.decode([WorkspaceTaskDefinition].self, forKey: .tasks)
	}
}

public enum WorkspaceTaskConfigurationError: Error, Equatable, Sendable {
	case invalidJSON
	case unsupportedVersion(Int)
	case duplicateTaskID(String)
	case invalidField(taskID: String, field: String, reason: String)
}

public enum WorkspaceTaskConfigurationParser {
	public static func parse(data: Data, scope: WorkspaceTaskConfigurationScope) throws -> WorkspaceTaskConfiguration {
		let decoded: WorkspaceTaskConfiguration
		do {
			decoded = try JSONDecoder().decode(WorkspaceTaskConfiguration.self, from: data)
		} catch {
			throw WorkspaceTaskConfigurationError.invalidJSON
		}
		guard decoded.version == WorkspaceTaskConfiguration.currentVersion else {
			throw WorkspaceTaskConfigurationError.unsupportedVersion(decoded.version)
		}
		var configuration = decoded
		configuration.scope = scope
		var taskIDs = Set<String>()
		for task in configuration.tasks {
			guard taskIDs.insert(task.id).inserted else {
				throw WorkspaceTaskConfigurationError.duplicateTaskID(task.id)
			}
			try validate(task)
		}
		return configuration
	}

	private static func validate(_ task: WorkspaceTaskDefinition) throws {
		try validateIdentifier(task.id, taskID: task.id, field: "id")
		guard !task.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !containsControl(task.label) else {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "label", reason: "must be non-empty plain text")
		}
		guard !task.command.isEmpty, !containsControl(task.command) else {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "command", reason: "must not contain control characters")
		}
		for argument in task.arguments where containsControl(argument) {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "arguments", reason: "must not contain control characters")
		}
		if let cwd = task.cwd, !isSafeRelativePath(cwd) {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "cwd", reason: "must be a relative path without traversal")
		}
		for (key, value) in task.environment {
			guard isEnvironmentKey(key) else {
				throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "env.\(key)", reason: "must be a POSIX environment key")
			}
			guard !containsControl(value) else {
				throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "env.\(key)", reason: "must not contain control characters")
			}
		}
		var inputIDs = Set<String>()
		for input in task.inputs {
			try validateIdentifier(input.id, taskID: task.id, field: "inputs.id")
			guard inputIDs.insert(input.id).inserted else {
				throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "inputs", reason: "contains duplicate input id")
			}
			guard !input.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !containsControl(input.prompt) else {
				throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "inputs.\(input.id).prompt", reason: "must be non-empty plain text")
			}
		}
		for dependency in task.dependsOn {
			try validateIdentifier(dependency, taskID: task.id, field: "depends_on")
		}
		for matcher in task.problemMatchers {
			try validateIdentifier(matcher, taskID: task.id, field: "problem_matchers")
		}
		if let watch = task.watch, watch.paths.contains(where: { !isSafeRelativePath($0) }) || watch.debounceMillis < 0 {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: task.id, field: "watch", reason: "contains an unsafe path or negative debounce")
		}
	}

	private static func validateIdentifier(_ value: String, taskID: String, field: String) throws {
		let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._:-"))
		guard !value.isEmpty, value.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
			throw WorkspaceTaskConfigurationError.invalidField(taskID: taskID, field: field, reason: "must use [A-Za-z0-9._:-]")
		}
	}

	private static func isEnvironmentKey(_ value: String) -> Bool {
		guard let first = value.unicodeScalars.first,
		      CharacterSet.letters.union(CharacterSet(charactersIn: "_")).contains(first)
		else {
			return false
		}
		let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_"))
		return value.unicodeScalars.allSatisfy { allowed.contains($0) }
	}

	private static func isSafeRelativePath(_ value: String) -> Bool {
		guard !value.isEmpty, !value.hasPrefix("/"), !containsControl(value) else {
			return false
		}
		return !value.split(separator: "/").contains("..")
	}

	private static func containsControl(_ value: String) -> Bool {
		value.unicodeScalars.contains { $0.value == 0 || $0.value < 0x20 || $0.value == 0x7F }
	}
}
