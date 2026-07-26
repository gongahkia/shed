import Darwin
import Dispatch
import Foundation

@_silgen_name("proc_listchildpids")
private func proc_listchildpids(_ parentPID: pid_t, _ buffer: UnsafeMutableRawPointer?, _ bufferSize: Int32) -> Int32

public enum WorkspaceTaskSource: String, Codable, Equatable, Sendable {
	case swiftPackage
	case packageScript
	case makefile
	case shellScript
	case extensionManifest
	case workspaceTaskFile
	case globalTaskFile
}

public struct WorkspaceTask: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var source: WorkspaceTaskSource
	public var command: String
	public var arguments: [String]
	public var workingDirectory: URL?
	public var environment: [String: String]
	public var inputs: [WorkspaceTaskInput]
	public var dependsOn: [String]
	public var isBackground: Bool
	public var watch: WorkspaceTaskWatch?
	public var presentation: WorkspaceTaskPresentation
	public var problemMatchers: [String]

	public init(
		id: String,
		label: String,
		source: WorkspaceTaskSource,
		command: String,
		arguments: [String] = [],
		workingDirectory: URL? = nil,
		environment: [String: String] = [:],
		inputs: [WorkspaceTaskInput] = [],
		dependsOn: [String] = [],
		isBackground: Bool = false,
		watch: WorkspaceTaskWatch? = nil,
		presentation: WorkspaceTaskPresentation = WorkspaceTaskPresentation(),
		problemMatchers: [String] = []
	) {
		self.id = id
		self.label = label
		self.source = source
		self.command = command
		self.arguments = arguments
		self.workingDirectory = workingDirectory
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
		case source
		case command
		case arguments
		case workingDirectory
		case environment
		case inputs
		case dependsOn = "depends_on"
		case isBackground = "is_background"
		case watch
		case presentation
		case problemMatchers
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		label = try container.decode(String.self, forKey: .label)
		source = try container.decode(WorkspaceTaskSource.self, forKey: .source)
		command = try container.decode(String.self, forKey: .command)
		arguments = try container.decodeIfPresent([String].self, forKey: .arguments) ?? []
		workingDirectory = try container.decodeIfPresent(URL.self, forKey: .workingDirectory)
		environment = try container.decodeIfPresent([String: String].self, forKey: .environment) ?? [:]
		inputs = try container.decodeIfPresent([WorkspaceTaskInput].self, forKey: .inputs) ?? []
		dependsOn = try container.decodeIfPresent([String].self, forKey: .dependsOn) ?? []
		isBackground = try container.decodeIfPresent(Bool.self, forKey: .isBackground) ?? false
		watch = try container.decodeIfPresent(WorkspaceTaskWatch.self, forKey: .watch)
		presentation = try container.decodeIfPresent(WorkspaceTaskPresentation.self, forKey: .presentation) ?? WorkspaceTaskPresentation()
		problemMatchers = try container.decodeIfPresent([String].self, forKey: .problemMatchers) ?? []
	}

	public var commandLine: String {
		([command] + arguments).joined(separator: " ")
	}
}

public enum WorkspaceTaskWatchPolicy: String, Codable, Equatable, Sendable {
	case manual
	case onChange = "on_change"
}

public struct WorkspaceTaskWatch: Codable, Equatable, Sendable {
	public var paths: [String]
	public var debounceMillis: Int
	public var policy: WorkspaceTaskWatchPolicy

	public init(paths: [String], debounceMillis: Int = 300, policy: WorkspaceTaskWatchPolicy = .manual) {
		self.paths = paths
		self.debounceMillis = debounceMillis
		self.policy = policy
	}

	private enum CodingKeys: String, CodingKey {
		case paths
		case debounceMillis = "debounce_ms"
		case policy
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		paths = try container.decodeIfPresent([String].self, forKey: .paths) ?? []
		debounceMillis = try container.decodeIfPresent(Int.self, forKey: .debounceMillis) ?? 300
		policy = try container.decodeIfPresent(WorkspaceTaskWatchPolicy.self, forKey: .policy) ?? .manual
	}
}

public enum WorkspaceTaskDiscovery {
	public static func discover(root: URL, globalConfigurationURL: URL? = nil, fileManager: FileManager = .default) -> [WorkspaceTask] {
		var tasks: [WorkspaceTask] = []
		if let globalConfigurationURL, let data = try? Data(contentsOf: globalConfigurationURL),
		   let configuredTasks = try? configuredTasks(data: data, root: root, scope: .global)
		{
			tasks += configuredTasks
		}
		tasks += swiftPackageTasks(root: root, fileManager: fileManager)
		tasks += packageJSONTasks(root: root)
		tasks += makefileTasks(root: root)
		tasks += shellScriptTasks(root: root, fileManager: fileManager)
		tasks += workspaceFileTasks(root: root, fileManager: fileManager)
		tasks += extensionTasks(root: root, fileManager: fileManager)
		return tasks
	}

	private static func swiftPackageTasks(root: URL, fileManager: FileManager) -> [WorkspaceTask] {
		guard fileManager.fileExists(atPath: root.appendingPathComponent("Package.swift").path) else {
			return []
		}
		return [
			WorkspaceTask(id: "swift:build", label: "swift build", source: .swiftPackage, command: "swift", arguments: ["build"], workingDirectory: root),
			WorkspaceTask(id: "swift:test", label: "swift test", source: .swiftPackage, command: "swift", arguments: ["test"], workingDirectory: root),
		]
	}

	private static func packageJSONTasks(root: URL) -> [WorkspaceTask] {
		let url = root.appendingPathComponent("package.json")
		guard let data = try? Data(contentsOf: url),
		      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
		      let scripts = object["scripts"] as? [String: Any]
		else {
			return []
		}
		return scripts.keys.sorted().map { name in
			WorkspaceTask(id: "npm:\(name)", label: "npm run \(name)", source: .packageScript, command: "npm", arguments: ["run", name], workingDirectory: root)
		}
	}

	private static func makefileTasks(root: URL) -> [WorkspaceTask] {
		let candidates = ["Makefile", "makefile"].map { root.appendingPathComponent($0) }
		guard let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
		      let text = try? String(contentsOf: url, encoding: .utf8)
		else {
			return []
		}
		var seen = Set<String>()
		return text.split(whereSeparator: \.isNewline).compactMap { line -> WorkspaceTask? in
			guard !line.hasPrefix("\t"),
			      let colon = line.firstIndex(of: ":")
			else {
				return nil
			}
			let target = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
			guard !target.isEmpty,
			      !target.contains(" "),
			      !target.contains("$"),
			      !target.hasPrefix("."),
			      seen.insert(target).inserted
			else {
				return nil
			}
			return WorkspaceTask(id: "make:\(target)", label: "make \(target)", source: .makefile, command: "make", arguments: [target], workingDirectory: root)
		}
	}

	private static func shellScriptTasks(root: URL, fileManager: FileManager) -> [WorkspaceTask] {
		let roots = [root, root.appendingPathComponent("scripts", isDirectory: true)]
		var tasks: [WorkspaceTask] = []
		for directory in roots {
			guard let children = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
				continue
			}
			for child in children where child.pathExtension == "sh" {
				guard let relative = relativePath(child, root: root) else {
					continue
				}
				tasks.append(WorkspaceTask(
					id: "script:\(relative)",
					label: relative,
					source: .shellScript,
					command: "/bin/sh",
					arguments: [relative],
					workingDirectory: root
				))
			}
		}
		return tasks.sorted { $0.label.localizedStandardCompare($1.label) == .orderedAscending }
	}

	private static func extensionTasks(root: URL, fileManager: FileManager) -> [WorkspaceTask] {
		ExtensionManifestLoader.discover(root: root, fileManager: fileManager).flatMap { manifest in
			ExtensionTaskMapper.tasks(from: manifest, root: root)
		}
	}

	private static func workspaceFileTasks(root: URL, fileManager: FileManager) -> [WorkspaceTask] {
		let url = root
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("tasks.json")
		guard fileManager.fileExists(atPath: url.path), let data = try? Data(contentsOf: url)
		else {
			return []
		}
		if let tasks = try? configuredTasks(data: data, root: root, scope: .project) {
			return tasks
		}
		guard let file = try? JSONDecoder().decode(WorkspaceTaskFile.self, from: data) else {
			return []
		}
		return file.tasks.map {
			WorkspaceTask(
				id: "workspace:\($0.id)",
				label: $0.label,
				source: .workspaceTaskFile,
				command: $0.command,
				arguments: $0.arguments,
				workingDirectory: root,
				dependsOn: $0.dependsOn,
				isBackground: $0.isBackground,
				watch: $0.watch
			)
		}
	}

	public static func configuredTasks(
		data: Data,
		root: URL,
		scope: WorkspaceTaskConfigurationScope
	) throws -> [WorkspaceTask] {
		let configuration = try WorkspaceTaskConfigurationParser.parse(data: data, scope: scope)
		return configuration.tasks.map { definition in
			let workingDirectory = definition.cwd.map { root.appendingPathComponent($0, isDirectory: true).standardizedFileURL } ?? root
			return WorkspaceTask(
				id: "\(scope.rawValue):\(definition.id)",
				label: definition.label,
				source: scope == .project ? .workspaceTaskFile : .globalTaskFile,
				command: definition.command,
				arguments: definition.arguments,
				workingDirectory: workingDirectory,
				environment: definition.environment,
				inputs: definition.inputs,
				dependsOn: definition.dependsOn,
				isBackground: definition.isBackground,
				watch: definition.watch,
				presentation: definition.presentation,
				problemMatchers: definition.problemMatchers
			)
		}
	}

	private static func relativePath(_ url: URL, root: URL) -> String? {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		guard path.hasPrefix(prefix) else {
			return nil
		}
		return String(path.dropFirst(prefix.count))
	}
}

private struct WorkspaceTaskFile: Decodable {
	var tasks: [LegacyWorkspaceTaskDefinition]
}

private struct LegacyWorkspaceTaskDefinition: Decodable {
	var id: String
	var label: String
	var command: String
	var arguments: [String]
	var dependsOn: [String]
	var isBackground: Bool
	var watch: WorkspaceTaskWatch?

	private enum CodingKeys: String, CodingKey {
		case id
		case label
		case command
		case arguments
		case dependsOn = "depends_on"
		case isBackground = "is_background"
		case watch
	}

	init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(String.self, forKey: .id)
		label = try container.decodeIfPresent(String.self, forKey: .label) ?? id
		command = try container.decode(String.self, forKey: .command)
		arguments = try container.decodeIfPresent([String].self, forKey: .arguments) ?? []
		dependsOn = try container.decodeIfPresent([String].self, forKey: .dependsOn) ?? []
		isBackground = try container.decodeIfPresent(Bool.self, forKey: .isBackground) ?? false
		watch = try container.decodeIfPresent(WorkspaceTaskWatch.self, forKey: .watch)
	}
}

public enum WorkspaceTaskPlanError: Error, Equatable, Sendable {
	case missingDependency(String)
	case dependencyCycle([String])
}

public enum WorkspaceTaskPlanner {
	public static func executionPlan(for task: WorkspaceTask, in tasks: [WorkspaceTask]) throws -> [WorkspaceTask] {
		var visiting: [String] = []
		var visited = Set<String>()
		var plan: [WorkspaceTask] = []
		try visit(task, tasks: tasks, visiting: &visiting, visited: &visited, plan: &plan)
		return plan
	}

	private static func visit(
		_ task: WorkspaceTask,
		tasks: [WorkspaceTask],
		visiting: inout [String],
		visited: inout Set<String>,
		plan: inout [WorkspaceTask]
	) throws {
		if let cycleIndex = visiting.firstIndex(of: task.id) {
			throw WorkspaceTaskPlanError.dependencyCycle(Array(visiting[cycleIndex...]) + [task.id])
		}
		guard visited.insert(task.id).inserted else {
			return
		}
		visiting.append(task.id)
		for dependency in task.dependsOn {
			guard let dependencyTask = resolve(dependency, in: tasks) else {
				throw WorkspaceTaskPlanError.missingDependency(dependency)
			}
			try visit(dependencyTask, tasks: tasks, visiting: &visiting, visited: &visited, plan: &plan)
		}
		_ = visiting.popLast()
		plan.append(task)
	}

	private static func resolve(_ id: String, in tasks: [WorkspaceTask]) -> WorkspaceTask? {
		tasks.first { task in
			task.id == id || task.id.hasSuffix(":\(id)")
		}
	}
}

public struct WorkspaceTaskResult: Equatable, Sendable {
	public var task: WorkspaceTask
	public var exitStatus: Int32
	public var stdout: String
	public var stderr: String
	public var wasCancelled: Bool
	public var wasReady: Bool

	public init(
		task: WorkspaceTask,
		exitStatus: Int32,
		stdout: String,
		stderr: String,
		wasCancelled: Bool = false,
		wasReady: Bool = false
	) {
		self.task = task
		self.exitStatus = exitStatus
		self.stdout = stdout
		self.stderr = stderr
		self.wasCancelled = wasCancelled
		self.wasReady = wasReady
	}

	public var succeeded: Bool {
		exitStatus == 0 && !wasCancelled
	}
}

public enum WorkspaceTaskRunError: Error, Equatable, Sendable {
	case invalidOutput
	case alreadyStarted
}

public enum WorkspaceTaskRunState: Equatable, Sendable {
	case pending
	case running
	case cancelling
	case finished(Int32)
	case cancelled(Int32)
}

public enum WorkspaceTaskOutputKind: Sendable, Equatable {
	case stdout
	case stderr
}

public struct WorkspaceTaskOutput: Sendable, Equatable {
	public var kind: WorkspaceTaskOutputKind
	public var text: String

	public init(kind: WorkspaceTaskOutputKind, text: String) {
		self.kind = kind
		self.text = text
	}
}

public final class WorkspaceTaskHandle: @unchecked Sendable {
	private let process: Process
	private let task: WorkspaceTask
	private let onOutput: @Sendable (WorkspaceTaskOutput) -> Void
	private let onReady: @Sendable (WorkspaceTaskHandle) -> Void
	private let onFinish: @Sendable (WorkspaceTaskResult) -> Void
	private let closePipes: @Sendable () -> Void
	private let cancelHandlers: @Sendable () -> Void
	private let lock = NSLock()
	private var stdout = Data()
	private var stderr = Data()
	private var stateStorage: WorkspaceTaskRunState = .pending
	private var didStart = false
	private var didBecomeReady = false
	private var cancellationRequested = false
	private var didFinish = false

	fileprivate init(
		task: WorkspaceTask,
		process: Process,
		onOutput: @escaping @Sendable (WorkspaceTaskOutput) -> Void,
		onReady: @escaping @Sendable (WorkspaceTaskHandle) -> Void,
		onFinish: @escaping @Sendable (WorkspaceTaskResult) -> Void,
		closePipes: @escaping @Sendable () -> Void,
		cancelHandlers: @escaping @Sendable () -> Void
	) {
		self.task = task
		self.process = process
		self.onOutput = onOutput
		self.onReady = onReady
		self.onFinish = onFinish
		self.closePipes = closePipes
		self.cancelHandlers = cancelHandlers
	}

	public var isRunning: Bool {
		lock.lock()
		let isRunning = didStart && !didFinish && process.isRunning
		lock.unlock()
		return isRunning
	}

	public var state: WorkspaceTaskRunState {
		lock.lock()
		let state = stateStorage
		lock.unlock()
		return state
	}

	public var wasReady: Bool {
		lock.lock()
		let wasReady = didBecomeReady
		lock.unlock()
		return wasReady
	}

	public func start() throws {
		lock.lock()
		guard !didStart else {
			lock.unlock()
			throw WorkspaceTaskRunError.alreadyStarted
		}
		didStart = true
		let wasCancelledBeforeStart = cancellationRequested
		lock.unlock()
		if wasCancelledBeforeStart {
			cancelHandlers()
			closePipes()
			if let result = finish(status: SIGTERM) {
				onFinish(result)
			}
			return
		}
		do {
			try process.run()
		} catch {
			cancelHandlers()
			closePipes()
			markLaunchFailed()
			throw error
		}
		closePipes()
		let shouldAnnounceReadiness = markStarted()
		if shouldAnnounceReadiness {
			onReady(self)
		}
		if cancellationWasRequested() {
			terminateProcessTree(escalationDelay: 1.0)
		}
	}

	public func cancel(escalationDelay: TimeInterval = 1.0) {
		lock.lock()
		guard !didFinish else {
			lock.unlock()
			return
		}
		cancellationRequested = true
		stateStorage = .cancelling
		let hasStarted = didStart
		lock.unlock()
		if hasStarted {
			terminateProcessTree(escalationDelay: escalationDelay)
		}
	}

	fileprivate func append(_ data: Data, kind: WorkspaceTaskOutputKind) {
		guard !data.isEmpty else {
			return
		}
		lock.lock()
		guard !didFinish else {
			lock.unlock()
			return
		}
		switch kind {
		case .stdout:
			stdout.append(data)
		case .stderr:
			stderr.append(data)
		}
		lock.unlock()
		onOutput(WorkspaceTaskOutput(kind: kind, text: String(decoding: data, as: UTF8.self)))
	}

	fileprivate func finish(status: Int32) -> WorkspaceTaskResult? {
		lock.lock()
		defer {
			lock.unlock()
		}
		guard !didFinish else {
			return nil
		}
		didFinish = true
		let wasCancelled = cancellationRequested
		stateStorage = wasCancelled ? .cancelled(status) : .finished(status)
		return WorkspaceTaskResult(
			task: task,
			exitStatus: status,
			stdout: String(decoding: stdout, as: UTF8.self),
			stderr: String(decoding: stderr, as: UTF8.self),
			wasCancelled: wasCancelled,
			wasReady: didBecomeReady
		)
	}

	private func markStarted() -> Bool {
		lock.lock()
		defer { lock.unlock() }
		guard !didFinish else {
			return false
		}
		if cancellationRequested {
			stateStorage = .cancelling
			return false
		}
		stateStorage = .running
		guard task.isBackground else {
			return false
		}
		didBecomeReady = true
		return true
	}

	private func markLaunchFailed() {
		lock.lock()
		defer { lock.unlock() }
		didFinish = true
		stateStorage = .finished(127)
	}

	private func cancellationWasRequested() -> Bool {
		lock.lock()
		let cancellationRequested = self.cancellationRequested
		lock.unlock()
		return cancellationRequested
	}

	private func terminateProcessTree(escalationDelay: TimeInterval) {
		let pid = process.processIdentifier
		guard pid > 0 else {
			return
		}
		Self.signalDescendants(of: pid, signal: SIGTERM)
		process.terminate()
		DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + max(0, escalationDelay)) {
			guard self.process.isRunning else {
				return
			}
			Self.signalDescendants(of: pid, signal: SIGKILL)
			_ = Darwin.kill(pid, SIGKILL)
		}
	}

	private static func signalDescendants(of rootPID: pid_t, signal: Int32) {
		for processID in descendantProcessIDs(of: rootPID).reversed() {
			_ = Darwin.kill(processID, signal)
		}
	}

	private static func descendantProcessIDs(of rootPID: pid_t) -> [pid_t] {
		var descendants: [pid_t] = []
		var pending: [pid_t] = [rootPID]
		while let parentPID = pending.popLast() {
			let children = childProcessIDs(of: parentPID)
			descendants.append(contentsOf: children)
			pending.append(contentsOf: children)
		}
		return descendants
	}

	private static func childProcessIDs(of parentPID: pid_t) -> [pid_t] {
		var capacity = 16
		while capacity <= 4_096 {
			var processIDs = [pid_t](repeating: 0, count: capacity)
			let byteCount = processIDs.withUnsafeMutableBytes { buffer in
				proc_listchildpids(parentPID, buffer.baseAddress, Int32(buffer.count))
			}
			guard byteCount > 0 else {
				return []
			}
			let count = Int(byteCount) / MemoryLayout<pid_t>.stride
			if count < capacity {
				return processIDs.prefix(count).filter { $0 > 0 }
			}
			capacity *= 2
		}
		return []
	}
}

public struct WorkspaceTaskRunner: Sendable {
	public var executableURL: URL

	public init(executableURL: URL = URL(fileURLWithPath: "/usr/bin/env")) {
		self.executableURL = executableURL
	}

	public func run(_ task: WorkspaceTask, root: URL) throws -> WorkspaceTaskResult {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = [task.command] + task.arguments
		process.currentDirectoryURL = task.workingDirectory ?? root
		process.environment = ProcessInfo.processInfo.environment.merging(task.environment) { _, configured in configured }
		let stdout = Pipe()
		let stderr = Pipe()
		process.standardOutput = stdout
		process.standardError = stderr
		try process.run()
		let stdoutBox = WorkspaceTaskDataBox()
		let stderrBox = WorkspaceTaskDataBox()
		let readers = DispatchGroup()
		read(stdout.fileHandleForReading, into: stdoutBox, group: readers)
		read(stderr.fileHandleForReading, into: stderrBox, group: readers)
		process.waitUntilExit()
		readers.wait()
		guard let stdoutText = String(data: stdoutBox.data, encoding: .utf8),
		      let stderrText = String(data: stderrBox.data, encoding: .utf8)
		else {
			throw WorkspaceTaskRunError.invalidOutput
		}
		return WorkspaceTaskResult(task: task, exitStatus: process.terminationStatus, stdout: stdoutText, stderr: stderrText)
	}

	public func run(_ task: WorkspaceTask, root: URL, availableTasks: [WorkspaceTask]) throws -> WorkspaceTaskResult {
		let plan = try WorkspaceTaskPlanner.executionPlan(for: task, in: availableTasks)
		var stdout = ""
		var stderr = ""
		var exitStatus: Int32 = 0
		for plannedTask in plan {
			let result = try run(plannedTask, root: root)
			stdout += result.stdout
			stderr += result.stderr
			exitStatus = result.exitStatus
			if !result.succeeded {
				break
			}
		}
		return WorkspaceTaskResult(task: task, exitStatus: exitStatus, stdout: stdout, stderr: stderr)
	}

	public func prepare(
		_ task: WorkspaceTask,
		root: URL,
		onOutput: @escaping @Sendable (WorkspaceTaskOutput) -> Void,
		onFinish: @escaping @Sendable (WorkspaceTaskResult) -> Void,
		onReady: @escaping @Sendable (WorkspaceTaskHandle) -> Void = { _ in }
	) -> WorkspaceTaskHandle {
		let process = Process()
		process.executableURL = executableURL
		process.arguments = [task.command] + task.arguments
		process.currentDirectoryURL = task.workingDirectory ?? root
		process.environment = ProcessInfo.processInfo.environment.merging(task.environment) { _, configured in configured }
		let stdoutPipe = Pipe()
		let stderrPipe = Pipe()
		process.standardOutput = stdoutPipe
		process.standardError = stderrPipe
		let handle = WorkspaceTaskHandle(
			task: task,
			process: process,
			onOutput: onOutput,
			onReady: onReady,
			onFinish: onFinish,
			closePipes: {
				stdoutPipe.fileHandleForWriting.closeFile()
				stderrPipe.fileHandleForWriting.closeFile()
			},
			cancelHandlers: {
				stdoutPipe.fileHandleForReading.readabilityHandler = nil
				stderrPipe.fileHandleForReading.readabilityHandler = nil
				process.terminationHandler = nil
			}
		)
		stdoutPipe.fileHandleForReading.readabilityHandler = { fileHandle in
			handle.append(fileHandle.availableData, kind: .stdout)
		}
		stderrPipe.fileHandleForReading.readabilityHandler = { fileHandle in
			handle.append(fileHandle.availableData, kind: .stderr)
		}
		process.terminationHandler = { process in
			stdoutPipe.fileHandleForReading.readabilityHandler = nil
			stderrPipe.fileHandleForReading.readabilityHandler = nil
			handle.append(stdoutPipe.fileHandleForReading.availableData, kind: .stdout)
			handle.append(stderrPipe.fileHandleForReading.availableData, kind: .stderr)
			if let result = handle.finish(status: process.terminationStatus) {
				onFinish(result)
			}
			process.terminationHandler = nil
		}
		return handle
	}

	public func start(
		_ task: WorkspaceTask,
		root: URL,
		onOutput: @escaping @Sendable (WorkspaceTaskOutput) -> Void,
		onFinish: @escaping @Sendable (WorkspaceTaskResult) -> Void,
		onReady: @escaping @Sendable (WorkspaceTaskHandle) -> Void = { _ in }
	) throws -> WorkspaceTaskHandle {
		let handle = prepare(task, root: root, onOutput: onOutput, onFinish: onFinish, onReady: onReady)
		do {
			try handle.start()
		} catch {
			throw error
		}
		return handle
	}

	private func read(_ handle: FileHandle, into box: WorkspaceTaskDataBox, group: DispatchGroup) {
		group.enter()
		DispatchQueue.global(qos: .utility).async {
			box.data = handle.readDataToEndOfFile()
			group.leave()
		}
	}
}

public final class WorkspaceTaskWatcher: @unchecked Sendable {
	private let root: URL
	private let watch: WorkspaceTaskWatch
	private let queue: DispatchQueue
	private let onChange: @Sendable () -> Void
	private let lock = NSLock()
	private var sources: [DispatchSourceFileSystemObject] = []
	private var debounceWorkItem: DispatchWorkItem?

	public init(root: URL, watch: WorkspaceTaskWatch, queue: DispatchQueue = .main, onChange: @escaping @Sendable () -> Void) {
		self.root = root
		self.watch = watch
		self.queue = queue
		self.onChange = onChange
	}

	deinit {
		stop()
	}

	public func start(fileManager: FileManager = .default) {
		stop()
		for path in watch.paths {
			let url = root.appendingPathComponent(path)
			guard fileManager.fileExists(atPath: url.path) else {
				continue
			}
			let descriptor = open(url.path, O_EVTONLY)
			guard descriptor >= 0 else {
				continue
			}
			let source = DispatchSource.makeFileSystemObjectSource(
				fileDescriptor: descriptor,
				eventMask: [.write, .extend, .attrib, .delete, .rename],
				queue: queue
			)
			source.setEventHandler { [weak self] in
				self?.scheduleChange()
			}
			source.setCancelHandler {
				close(descriptor)
			}
			lock.lock()
			sources.append(source)
			lock.unlock()
			source.resume()
		}
	}

	public func stop() {
		lock.lock()
		let activeSources = sources
		sources.removeAll()
		debounceWorkItem?.cancel()
		debounceWorkItem = nil
		lock.unlock()
		for source in activeSources {
			source.cancel()
		}
	}

	private func scheduleChange() {
		let debounceMillis = max(0, watch.debounceMillis)
		lock.lock()
		debounceWorkItem?.cancel()
		let item = DispatchWorkItem { [weak self] in
			self?.onChange()
		}
		debounceWorkItem = item
		lock.unlock()
		queue.asyncAfter(deadline: .now() + .milliseconds(debounceMillis), execute: item)
	}
}

private final class WorkspaceTaskDataBox: @unchecked Sendable {
	private let lock = NSLock()
	private var storage = Data()

	var data: Data {
		get {
			lock.lock()
			let data = storage
			lock.unlock()
			return data
		}
		set {
			lock.lock()
			storage = newValue
			lock.unlock()
		}
	}
}
