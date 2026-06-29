import Dispatch
import Foundation

public enum WorkspaceTaskSource: String, Codable, Equatable, Sendable {
	case swiftPackage
	case packageScript
	case makefile
	case shellScript
}

public struct WorkspaceTask: Codable, Equatable, Sendable {
	public var id: String
	public var label: String
	public var source: WorkspaceTaskSource
	public var command: String
	public var arguments: [String]
	public var workingDirectory: URL?

	public init(id: String, label: String, source: WorkspaceTaskSource, command: String, arguments: [String] = [], workingDirectory: URL? = nil) {
		self.id = id
		self.label = label
		self.source = source
		self.command = command
		self.arguments = arguments
		self.workingDirectory = workingDirectory
	}
}

public enum WorkspaceTaskDiscovery {
	public static func discover(root: URL, fileManager: FileManager = .default) -> [WorkspaceTask] {
		var tasks: [WorkspaceTask] = []
		tasks += swiftPackageTasks(root: root, fileManager: fileManager)
		tasks += packageJSONTasks(root: root)
		tasks += makefileTasks(root: root)
		tasks += shellScriptTasks(root: root, fileManager: fileManager)
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

public struct WorkspaceTaskResult: Equatable, Sendable {
	public var task: WorkspaceTask
	public var exitStatus: Int32
	public var stdout: String
	public var stderr: String

	public init(task: WorkspaceTask, exitStatus: Int32, stdout: String, stderr: String) {
		self.task = task
		self.exitStatus = exitStatus
		self.stdout = stdout
		self.stderr = stderr
	}

	public var succeeded: Bool {
		exitStatus == 0
	}
}

public enum WorkspaceTaskRunError: Error, Equatable, Sendable {
	case invalidOutput
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

	private func read(_ handle: FileHandle, into box: WorkspaceTaskDataBox, group: DispatchGroup) {
		group.enter()
		DispatchQueue.global(qos: .utility).async {
			box.data = handle.readDataToEndOfFile()
			group.leave()
		}
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
