@testable import ItsyApp
import AppKit
import Foundation
import ItsyDAP
import ItsyEditor
import Testing

@MainActor
@Test func integrationSelfHostingWorkspaceRunsEditNavigateTaskGitAndDebugFlows() async throws {
	let fixture = try SelfHostingFixture()
	var succeeded = false
	defer { fixture.finish(succeeded: succeeded) }
	try fixture.materialize()
	let sourceURL = fixture.sourceURL
	let original = try String(contentsOf: sourceURL, encoding: .utf8)
	var editor = Editor(text: original, storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: editor.textStorage.length, head: editor.textStorage.length)))
	editor.insert("\nprivate let selfHostingFixture = true\n")
	let edited = editor.textStorage.substring(0 ..< editor.textStorage.length)
	try edited.write(to: sourceURL, atomically: true, encoding: .utf8)
	try fixture.log("edited \(sourceURL.path)")

	let document = ItsyDocument()
	document.fileURL = sourceURL
	document.editor = editor
	let controller = EditorWindowController(document: document)
	document.addWindowController(controller)
	defer { controller.close() }
	try fixture.captureScreenshot(of: controller)

	let index = WorkspaceIndexer.build(root: fixture.root)
	try require(index.files.map(\.relativePath).contains("Sources/ItsyEditor/WorkspaceTasks.swift"), "navigation did not index copied Itsy source")
	try require(index.symbols.contains { $0.name == "WorkspaceTaskRunner" }, "navigation did not expose WorkspaceTaskRunner")
	try fixture.log("navigated WorkspaceTaskRunner")

	try fixture.git(["init"])
	try fixture.git(["config", "user.email", "itsy@example.invalid"])
	try fixture.git(["config", "user.name", "Itsy self-hosting fixture"])
	try fixture.git(["add", "."])
	try fixture.git(["commit", "-m", "fixture baseline"])
	try "\nprivate let selfHostingGitMutation = true\n".append(to: sourceURL)
	let status = try GitRepository(root: fixture.root).status()
	try require(status.entries.contains { $0.path == "Sources/ItsyEditor/WorkspaceTasks.swift" && $0.worktreeStatus == "M" }, "Git status did not report the edited source")
	try fixture.log("git status reported source mutation")

	let task = WorkspaceTask(
		id: "self-hosting:verify-source",
		label: "verify source",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "test -f Sources/ItsyEditor/WorkspaceTasks.swift && printf self-host-task"],
		workingDirectory: fixture.root
	)
	let taskResult = try WorkspaceTaskRunner().run(task, root: fixture.root)
	try require(taskResult.succeeded && taskResult.stdout == "self-host-task", "task did not verify the self-hosting workspace")
	try fixture.log("task completed")

	try await runSelfHostingDAPFlow(sourceURL: sourceURL)
	try fixture.log("mock DAP launch, breakpoint, continue, and termination completed")
	succeeded = true
}

private enum SelfHostingWorkflowError: Error, CustomStringConvertible {
	case missingTool(String)
	case expectation(String)
	case screenshotUnavailable
	case dapTimeout(Int)
	case dapFrame

	var description: String {
		switch self {
		case let .missingTool(tool):
			return "missing required tool: \(tool)"
		case let .expectation(message):
			return message
		case .screenshotUnavailable:
			return "could not render self-hosting editor screenshot"
		case let .dapTimeout(expected):
			return "timed out waiting for DAP request \(expected)"
		case .dapFrame:
			return "DAP transport emitted an invalid frame"
		}
	}
}

private final class SelfHostingFixture {
	private let fileManager: FileManager
	let root: URL
	let artifactRoot: URL
	private let logURL: URL

	init(fileManager: FileManager = .default, environment: [String: String] = ProcessInfo.processInfo.environment) throws {
		self.fileManager = fileManager
		let configuredRoot = environment["ITSY_SELF_HOSTING_ARTIFACTS"].map(URL.init(fileURLWithPath:))
		artifactRoot = configuredRoot ?? URL(fileURLWithPath: fileManager.currentDirectoryPath)
			.appendingPathComponent(".build/self-hosting", isDirectory: true)
		root = artifactRoot.appendingPathComponent("workspace-\(UUID().uuidString)", isDirectory: true)
		logURL = root.appendingPathComponent("workflow.log")
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
		fileManager.createFile(atPath: logURL.path, contents: nil)
	}

	var sourceURL: URL {
		root.appendingPathComponent("Sources/ItsyEditor/WorkspaceTasks.swift")
	}

	func materialize() throws {
		guard fileManager.isExecutableFile(atPath: "/usr/bin/git") else {
			throw SelfHostingWorkflowError.missingTool("git")
		}
		guard fileManager.isExecutableFile(atPath: "/bin/sh") else {
			throw SelfHostingWorkflowError.missingTool("/bin/sh")
		}
		let repositoryRoot = URL(fileURLWithPath: #filePath)
			.deletingLastPathComponent()
			.deletingLastPathComponent()
			.deletingLastPathComponent()
		for relativePath in ["Package.swift", "Sources/ItsyEditor/WorkspaceTasks.swift"] {
			let source = repositoryRoot.appendingPathComponent(relativePath)
			let destination = root.appendingPathComponent(relativePath)
			try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
			try fileManager.copyItem(at: source, to: destination)
		}
		try "Derived/\n".write(to: root.appendingPathComponent(".gitignore"), atomically: true, encoding: .utf8)
		try log("materialized copied Itsy source")
	}

	func git(_ arguments: [String]) throws {
		_ = try ProcessGitCommandRunner().runGit(arguments: arguments, root: root)
	}

	func log(_ message: String) throws {
		let line = "\(message)\n"
		let handle = try FileHandle(forWritingTo: logURL)
		defer { try? handle.close() }
		try handle.seekToEnd()
		try handle.write(contentsOf: Data(line.utf8))
	}

	func captureScreenshot(of controller: EditorWindowController) throws {
		guard let window = controller.window, let view = window.contentView else {
			throw SelfHostingWorkflowError.screenshotUnavailable
		}
		window.setFrame(NSRect(x: 0, y: 0, width: 1_024, height: 640), display: false)
		view.frame = NSRect(x: 0, y: 0, width: 1_024, height: 640)
		view.layoutSubtreeIfNeeded()
		guard let image = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
			throw SelfHostingWorkflowError.screenshotUnavailable
		}
		view.cacheDisplay(in: view.bounds, to: image)
		guard let png = image.representation(using: .png, properties: [:]) else {
			throw SelfHostingWorkflowError.screenshotUnavailable
		}
		try png.write(to: root.appendingPathComponent("editor.png"))
		try log("captured editor.png")
	}

	func finish(succeeded: Bool) {
		if succeeded {
			try? fileManager.removeItem(at: root)
		}
	}
}

private func require(_ condition: Bool, _ message: String) throws {
	guard condition else {
		throw SelfHostingWorkflowError.expectation(message)
	}
}

private func runSelfHostingDAPFlow(sourceURL: URL) async throws {
	let transport = SelfHostingDAPTransport()
	let session = DAPClientSession(transport: transport)
	let initialize = Task { try await session.initialize(clientCapabilities: DAPInitializeRequestArguments(adapterID: "self-hosting")) }
	let initializeRequest = try await transport.request(at: 0)
	try await respond(to: initializeRequest, sequence: 1, in: session)
	_ = try await initialize.value

	let launch = Task { try await session.launch(arguments: .object(["program": .string(sourceURL.path)])) }
	let launchRequest = try await transport.request(at: 1)
	try await respond(to: launchRequest, sequence: 2, in: session)
	_ = try await launch.value
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 3, event: DAPEvent.initialized))))
	try require(await session.state == .configuring, "DAP did not configure after initialized")

	let breakpoints = Task {
		try await session.setBreakpoints(DAPSetBreakpointsArguments(
			source: DAPSource(name: sourceURL.lastPathComponent, path: sourceURL.path),
			breakpoints: [DAPSourceBreakpoint(line: 1)]
		))
	}
	let breakpointsRequest = try await transport.request(at: 2)
	try await respond(to: breakpointsRequest, sequence: 4, body: try DAPAny(encoding: DAPSetBreakpointsResponseBody(breakpoints: [DAPBreakpoint(id: 1, verified: true, line: 1)])), in: session)
	_ = try await breakpoints.value

	let configurationDone = Task { try await session.configurationDone() }
	let configurationRequest = try await transport.request(at: 3)
	try await respond(to: configurationRequest, sequence: 5, in: session)
	_ = try await configurationDone.value
	try require(await session.state == .running, "DAP did not enter running state")
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 6, event: DAPEvent.stopped, body: .object(["reason": .string("breakpoint")]))) ))
	try require(await session.state == .stopped, "DAP did not stop at breakpoint")
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 7, event: DAPEvent.continued))))
	try require(await session.state == .running, "DAP did not resume")
	_ = try await session.receive(DAPMessageFramer.frame(message: .event(DAPEventMessage(seq: 8, event: DAPEvent.terminated))))
	try require(await session.state == .terminated, "DAP did not terminate")
}

private func respond(to request: DAPRequestMessage, sequence: Int, body: DAPAny? = nil, in session: DAPClientSession) async throws {
	_ = try await session.receive(DAPMessageFramer.frame(message: .response(DAPResponseMessage(
		seq: sequence,
		requestSeq: request.seq,
		success: true,
		command: request.command,
		body: body
	))))
}

private final class SelfHostingDAPTransport: DAPClientTransport, @unchecked Sendable {
	private let lock = NSLock()
	private var writes: [Data] = []

	func write(_ data: Data) throws {
		lock.lock()
		writes.append(data)
		lock.unlock()
	}

	func request(at index: Int) async throws -> DAPRequestMessage {
		for _ in 0 ..< 200 {
			if let data = write(at: index) {
				var framer = DAPMessageFramer()
				guard let payload = try framer.append(data).first,
				      case let .request(request) = try JSONDecoder().decode(DAPMessage.self, from: payload)
				else {
					throw SelfHostingWorkflowError.dapFrame
				}
				return request
			}
			try await Task.sleep(nanoseconds: 1_000_000)
		}
		throw SelfHostingWorkflowError.dapTimeout(index)
	}

	private func write(at index: Int) -> Data? {
		lock.lock()
		defer { lock.unlock() }
		return writes.indices.contains(index) ? writes[index] : nil
	}
}

private extension String {
	func append(to url: URL) throws {
		let handle = try FileHandle(forWritingTo: url)
		defer { try? handle.close() }
		try handle.seekToEnd()
		try handle.write(contentsOf: Data(utf8))
	}
}
