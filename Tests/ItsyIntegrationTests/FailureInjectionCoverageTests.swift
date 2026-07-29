@testable import ItsyApp
import Darwin
import Foundation
import ItsyDAP
import ItsyEditor
import ItsyLSP
import Testing

@Test func failureInjectionFullDiskSavePreservesDocumentAndReportsRecoveryAction() throws {
	let fixture = try FailureInjectionFixture()
	defer { fixture.cleanup() }
	let destination = try fixture.write("document.txt", "before")
	var operations = AtomicFileOperations.live
	operations.write = { _, _, _ in
		errno = ENOSPC
		return -1
	}
	let failure = try captureAtomicWriteFailure(destination: destination, operations: operations)

	#expect(failure.code == .fileWriteOutOfSpace)
	#expect(failure.localizedDescription.contains("No space"))
	#expect(try String(contentsOf: destination, encoding: .utf8) == "before")
}

@Test func failureInjectionPermissionDeniedSavePreservesDocumentAndReportsRecoveryAction() throws {
	let fixture = try FailureInjectionFixture()
	defer { fixture.cleanup() }
	let destination = try fixture.write("document.txt", "before")
	var operations = AtomicFileOperations.live
	operations.open = { _, _, _ in
		errno = EACCES
		return -1
	}
	let failure = try captureAtomicWriteFailure(destination: destination, operations: operations)

	#expect(failure.code == .fileWriteNoPermission)
	#expect(failure.localizedDescription.contains("Permission denied"))
	#expect(try String(contentsOf: destination, encoding: .utf8) == "before")
}

@Test func failureInjectionRecoveryJournalSurvivesKilledEditorAndRejectsMalformedJournal() throws {
	let fixture = try FailureInjectionFixture()
	defer { fixture.cleanup() }
	let source = try fixture.write("document.txt", "on disk")
	let store = RecoveryJournalStore()
	let journal = RecoveryJournal(fileURL: source, text: "unsaved after process kill")
	try store.save(journal, workspaceRoot: fixture.root)

	let recovered = try #require(RecoveryJournalStore().load(fileURL: source, workspaceRoot: fixture.root))
	#expect(String(decoding: recovered.text, as: UTF8.self) == "unsaved after process kill")
	try Data("{malformed".utf8).write(to: store.journalURL(fileURL: source, workspaceRoot: fixture.root))
	#expect(RecoveryJournalStore().load(fileURL: source, workspaceRoot: fixture.root) == nil)
}

@Test func failureInjectionKilledTaskReturnsOutputAndCancelledStatus() throws {
	let fixture = try FailureInjectionFixture()
	defer { fixture.cleanup() }
	let task = WorkspaceTask(
		id: "killed-task",
		label: "killed task",
		source: .workspaceTaskFile,
		command: "/bin/sh",
		arguments: ["-c", "printf before-kill; kill -TERM $$"]
	)
	let result = try WorkspaceTaskRunner().run(task, root: fixture.root)

	#expect(result.stdout == "before-kill")
	#expect(result.exitStatus == SIGTERM)
	#expect(!result.succeeded)
}

@Test @MainActor func failureInjectionMalformedLSPProtocolIsRetainedInStatusDetails() throws {
	var framer = LSPMessageFramer()
	let detail = try captureLSPFramingFailure(&framer)
	let key = LSPSessionKey(languageID: "swift", workspaceRoot: URL(fileURLWithPath: "/tmp/failure-injection"))
	let snapshot = LSPStatusPanelSnapshot(
		key: key,
		status: LSPHealthState.degraded.rawValue,
		health: .degraded,
		server: "injected-lsp",
		pid: nil,
		startDate: nil,
		lastError: detail,
		output: [LSPSessionOutput(kind: .protocolOutput, text: detail)]
	)

	#expect(detail.contains("invalidContentLength"))
	#expect(LSPStatusPanel.outputText(snapshot).contains("[protocol] \(detail)"))
	#expect(LSPStatusPanel.detailsText(snapshot).contains("Health: degraded"))
}

@Test func failureInjectionMalformedDAPProtocolReturnsActionableDetail() throws {
	var framer = DAPMessageFramer()
	let detail = try captureDAPFramingFailure(&framer)

	#expect(detail.contains("invalidContentLength"))
	#expect(detail.contains("not-a-number"))
}

@Test func failureInjectionFailedGitCommandReturnsActionableDetail() async throws {
	let coordinator = GitStatusRefreshCoordinator()
	let result = await coordinator.refresh(root: URL(fileURLWithPath: "/tmp/failure-injection")) { _ in
		throw GitCommandError.failed(status: 128, stderr: "fatal: injected Git failure")
	}
	guard case let .failure(detail)? = result else {
		Issue.record("expected failed Git detail")
		return
	}

	#expect(detail.contains("128"))
	#expect(detail.contains("fatal: injected Git failure"))
}

private func captureAtomicWriteFailure(destination: URL, operations: AtomicFileOperations) throws -> CocoaError {
	do {
		try AtomicFileWriter.write(data: Data("after".utf8), to: destination, operations: operations)
		throw FailureInjectionError.expectedFailure("atomic write")
	} catch let error as AtomicFileWriteError {
		return error.cocoaError
	}
}

private func captureLSPFramingFailure(_ framer: inout LSPMessageFramer) throws -> String {
	do {
		_ = try framer.append(Data("Content-Length: invalid\r\n\r\n{}".utf8))
		throw FailureInjectionError.expectedFailure("LSP framing")
	} catch let error as LSPFramingError {
		return String(describing: error)
	}
}

private func captureDAPFramingFailure(_ framer: inout DAPMessageFramer) throws -> String {
	do {
		_ = try framer.append(Data("Content-Length: not-a-number\r\n\r\n{}".utf8))
		throw FailureInjectionError.expectedFailure("DAP framing")
	} catch let error as DAPFramingError {
		return String(describing: error)
	}
}

private enum FailureInjectionError: Error {
	case expectedFailure(String)
}

private final class FailureInjectionFixture {
	let root: URL

	init(fileManager: FileManager = .default) throws {
		root = fileManager.temporaryDirectory.appendingPathComponent("itsy-failure-injection-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func write(_ path: String, _ contents: String) throws -> URL {
		let url = root.appendingPathComponent(path)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: url, atomically: true, encoding: .utf8)
		return url
	}

	func cleanup(fileManager: FileManager = .default) {
		try? fileManager.removeItem(at: root)
	}
}
