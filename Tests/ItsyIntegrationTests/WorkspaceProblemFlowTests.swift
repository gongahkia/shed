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
		]
	), source: "sourcekit-lsp")
	problemsCoordinator.setProblems(await aggregator.snapshot())
	problemsCoordinator.showProblemsForTesting()
	#expect(problemsCoordinator.problemCountForTesting == 1)
	#expect(problemsCoordinator.statusTextForTesting == "1 errors, 0 warnings, 1 total")

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
	#expect(problemsCoordinator.problemCountForTesting == 1)

	problemsCoordinator.focusProblemForTesting(index: 0)
	#expect(problemsCoordinator.selectedProblemIndexForTesting == 0)
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
