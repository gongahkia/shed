import Foundation

public enum WorkspaceTaskInputResolution: Equatable, Sendable {
	case value(String)
	case cancelled
}

public struct WorkspaceTaskExpansionContext: Equatable, Sendable {
	public var workspaceRoot: URL
	public var fileURL: URL?
	public var selectedText: String
	public var environment: [String: String]
	public var inputValues: [String: String]

	public init(
		workspaceRoot: URL,
		fileURL: URL? = nil,
		selectedText: String = "",
		environment: [String: String] = ProcessInfo.processInfo.environment,
		inputValues: [String: String] = [:]
	) {
		self.workspaceRoot = workspaceRoot.standardizedFileURL
		self.fileURL = fileURL?.standardizedFileURL
		self.selectedText = selectedText
		self.environment = environment
		self.inputValues = inputValues
	}
}

public struct WorkspaceTaskExpansion: Equatable, Sendable {
	public var task: WorkspaceTask
	public var previewCommandLine: String

	public init(task: WorkspaceTask, previewCommandLine: String) {
		self.task = task
		self.previewCommandLine = previewCommandLine
	}
}

public enum WorkspaceTaskExpansionError: Error, Equatable, Sendable {
	case malformedVariable(String)
	case missingContextValue(String)
	case missingEnvironment(String)
	case missingInput(String)
	case inputCancelled(String)
}

public enum WorkspaceTaskExpander {
	public static func expand(
		_ task: WorkspaceTask,
		context: WorkspaceTaskExpansionContext,
		inputResolver: (WorkspaceTaskInput) -> WorkspaceTaskInputResolution = { input in
			input.defaultValue.map(WorkspaceTaskInputResolution.value) ?? .cancelled
		}
	) throws -> WorkspaceTaskExpansion {
		var inputValues = context.inputValues
		let inputsByID = Dictionary(uniqueKeysWithValues: task.inputs.map { ($0.id, $0) })
		var resolved = task
		let command = try expand(task.command, context: context, inputsByID: inputsByID, inputValues: &inputValues, inputResolver: inputResolver)
		let arguments = try task.arguments.map {
			try expand($0, context: context, inputsByID: inputsByID, inputValues: &inputValues, inputResolver: inputResolver)
		}
		var environment: [String: String] = [:]
		for key in task.environment.keys.sorted() {
			guard let value = task.environment[key] else {
				continue
			}
			environment[key] = try expand(value, context: context, inputsByID: inputsByID, inputValues: &inputValues, inputResolver: inputResolver).value
		}
		resolved.command = command.value
		resolved.arguments = arguments.map(\.value)
		resolved.environment = environment
		return WorkspaceTaskExpansion(
			task: resolved,
			previewCommandLine: displayCommandLine(command: command.preview, arguments: arguments.map(\.preview))
		)
	}

	private static func expand(
		_ source: String,
		context: WorkspaceTaskExpansionContext,
		inputsByID: [String: WorkspaceTaskInput],
		inputValues: inout [String: String],
		inputResolver: (WorkspaceTaskInput) -> WorkspaceTaskInputResolution
	) throws -> (value: String, preview: String) {
		var value = ""
		var preview = ""
		var index = source.startIndex
		while index < source.endIndex {
			guard source[index] == "$", source.index(after: index) < source.endIndex, source[source.index(after: index)] == "{" else {
				value.append(source[index])
				preview.append(source[index])
				index = source.index(after: index)
				continue
			}
			let variableStart = source.index(index, offsetBy: 2)
			guard let variableEnd = source[variableStart...].firstIndex(of: "}") else {
				throw WorkspaceTaskExpansionError.malformedVariable(String(source[index...]))
			}
			let variable = String(source[variableStart ..< variableEnd])
			let replacement = try replacement(
				for: variable,
				context: context,
				inputsByID: inputsByID,
				inputValues: &inputValues,
				inputResolver: inputResolver
			)
			value += replacement.value
			preview += replacement.preview
			index = source.index(after: variableEnd)
		}
		return (value, preview)
	}

	private static func replacement(
		for variable: String,
		context: WorkspaceTaskExpansionContext,
		inputsByID: [String: WorkspaceTaskInput],
		inputValues: inout [String: String],
		inputResolver: (WorkspaceTaskInput) -> WorkspaceTaskInputResolution
	) throws -> (value: String, preview: String) {
		let value: String
		switch variable {
		case "workspace", "workspaceFolder":
			value = context.workspaceRoot.path
		case "file":
			guard let fileURL = context.fileURL else {
				throw WorkspaceTaskExpansionError.missingContextValue(variable)
			}
			value = fileURL.path
		case "fileDir", "fileDirname":
			guard let fileURL = context.fileURL else {
				throw WorkspaceTaskExpansionError.missingContextValue(variable)
			}
			value = fileURL.deletingLastPathComponent().path
		case "relativeFile":
			guard let fileURL = context.fileURL else {
				throw WorkspaceTaskExpansionError.missingContextValue(variable)
			}
			guard let relative = relativePath(fileURL, to: context.workspaceRoot) else {
				throw WorkspaceTaskExpansionError.missingContextValue(variable)
			}
			value = relative
		case "selection", "selectedText":
			value = context.selectedText
		default:
			if variable.hasPrefix("env:") {
				let key = String(variable.dropFirst(4))
				guard !key.isEmpty, let environmentValue = context.environment[key] else {
					throw WorkspaceTaskExpansionError.missingEnvironment(key)
				}
				value = environmentValue
			} else if variable.hasPrefix("input:") {
				let identifier = String(variable.dropFirst(6))
				guard !identifier.isEmpty, let input = inputsByID[identifier] else {
					throw WorkspaceTaskExpansionError.missingInput(identifier)
				}
				if let existing = inputValues[identifier] {
					return (existing, input.secret ? "••••" : existing)
				}
				switch inputResolver(input) {
				case let .value(resolved):
					inputValues[identifier] = resolved
					return (resolved, input.secret ? "••••" : resolved)
				case .cancelled:
					throw WorkspaceTaskExpansionError.inputCancelled(identifier)
				}
			} else {
				throw WorkspaceTaskExpansionError.missingContextValue(variable)
			}
		}
		return (value, value)
	}

	private static func relativePath(_ fileURL: URL, to workspaceRoot: URL) -> String? {
		let root = workspaceRoot.standardizedFileURL.path
		let file = fileURL.standardizedFileURL.path
		let prefix = root.hasSuffix("/") ? root : root + "/"
		guard file.hasPrefix(prefix) else {
			return nil
		}
		return String(file.dropFirst(prefix.count))
	}

	private static func displayCommandLine(command: String, arguments: [String]) -> String {
		([command] + arguments).map(displayArgument).joined(separator: " ")
	}

	private static func displayArgument(_ value: String) -> String {
		let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._/-:=•"))
		guard !value.isEmpty, value.unicodeScalars.allSatisfy(allowed.contains) else {
			return "'\(value.replacingOccurrences(of: "'", with: "'\\\"'\\\"'"))'"
		}
		return value
	}
}
