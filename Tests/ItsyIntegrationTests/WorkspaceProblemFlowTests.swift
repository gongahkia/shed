@testable import ItsyApp
import AppKit
import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@MainActor
@Test func integrationOpenDiagnosticsTaskAndProblemNavigation() async throws {
	let fixture = try IntegrationWorkspaceFixture()
	defer {
		fixture.cleanup()
	}
	let sourceURL = try fixture.write("Sources/App.swift", """
	import Foundation
	let value = 41
	let next = value + 1
	print(next)
	""")
	let documentController = ItsyDocumentController()
	let problemsCoordinator = ProblemsCoordinator(documentController: documentController)
	let taskCoordinator = TaskCoordinator(problemsCoordinator: problemsCoordinator)
	defer {
		problemsCoordinator.closeProblemsForTesting()
		for document in documentController.documents {
			document.close()
		}
	}

	#expect(documentController.openDocument(at: sourceURL))
	let document = try #require(documentController.document(for: sourceURL) as? ItsyDocument)
	#expect(document.fileURL?.standardizedFileURL == sourceURL.standardizedFileURL)

	let aggregator = LSPDiagnosticsAggregator(root: fixture.root)
	await aggregator.ingest(LSPPublishDiagnosticsParams(
		uri: sourceURL.standardizedFileURL.absoluteString,
		diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 1, character: 4), end: LSPPosition(line: 1, character: 9)),
				severity: .error,
				source: "sourcekit-lsp",
				message: "expected expression"
			),
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 3, character: 6), end: LSPPosition(line: 3, character: 10)),
				severity: .warning,
				source: "sourcekit-lsp",
				message: "unused value"
			),
		]
	), source: "sourcekit-lsp")
	problemsCoordinator.setProblems(await aggregator.snapshot(), sourceID: "lsp:swift:\(fixture.root.path)")
	problemsCoordinator.showProblemsForTesting()
	#expect(problemsCoordinator.problemCountForTesting == 2)
	#expect(problemsCoordinator.statusTextForTesting == "1 errors, 1 warnings, 2 total")
	problemsCoordinator.showNextProblemForTesting()
	#expect(problemsCoordinator.selectedProblemIndexForTesting == 1)
	problemsCoordinator.showPreviousProblemForTesting()
	#expect(problemsCoordinator.selectedProblemIndexForTesting == 0)

	let task = WorkspaceTask(
		id: "compile",
		label: "compile",
		source: .shellScript,
		command: "/bin/sh",
		arguments: ["-c", "printf 'Sources/App.swift:4:7: error: compile failed\\n' >&2; exit 1"],
		workingDirectory: fixture.root
	)
	let taskResult = try WorkspaceTaskRunner().run(task, root: fixture.root)
	#expect(!taskResult.succeeded)
	taskCoordinator.applyTaskResultForTesting(taskResult, root: fixture.root)
	#expect(problemsCoordinator.problemCountForTesting == 3)

	let taskIndex = try #require(problemsCoordinator.problemsForTesting.firstIndex { $0.message == "compile failed" })
	problemsCoordinator.focusProblemForTesting(index: taskIndex)
	#expect(problemsCoordinator.selectedProblemIndexForTesting == taskIndex)
	problemsCoordinator.openSelectedProblemForTesting()
	let reopened = try #require(documentController.document(for: sourceURL) as? ItsyDocument)
	let expectedOffset = reopened.editor.textStorage.offset(forLine: 3) + 6
	#expect(reopened.editor.selections.primary.head == expectedOffset)
}

private final class IntegrationWorkspaceFixture {
	let root: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-integration-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ path: String, _ contents: String, fileManager: FileManager = .default) throws -> URL {
		let url = root.appendingPathComponent(path)
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
		return url
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}
