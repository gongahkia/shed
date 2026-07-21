import Foundation
import ItsyEditor
import Testing

@Test func recoveryJournalRestoresDirtyTextAndSuccessfulDiscardRemovesIt() throws {
	let workspace = try recoveryWorkspace()
	defer { try? FileManager.default.removeItem(at: workspace) }
	let fileURL = workspace.appendingPathComponent("note.txt")
	let store = RecoveryJournalStore()
	let journal = RecoveryJournal(fileURL: fileURL, text: "unsaved text", updatedAt: Date(timeIntervalSince1970: 1))
	try store.save(journal, workspaceRoot: workspace)
	#expect(store.load(fileURL: fileURL, workspaceRoot: workspace) == journal)
	try store.remove(fileURL: fileURL, workspaceRoot: workspace)
	#expect(store.load(fileURL: fileURL, workspaceRoot: workspace) == nil)
}

@Test func recoverySchedulerCancelsSupersededDirtySnapshot() throws {
	let workspace = try recoveryWorkspace()
	defer { try? FileManager.default.removeItem(at: workspace) }
	let fileURL = workspace.appendingPathComponent("note.txt")
	let scheduler = RecoveryJournalScheduler()
	let first = RecoveryJournal(fileURL: fileURL, text: "first")
	let second = RecoveryJournal(fileURL: fileURL, text: "second")
	scheduler.schedule(first, workspaceRoot: workspace, delay: 0.03)
	scheduler.schedule(second, workspaceRoot: workspace, delay: 0.03)
	Thread.sleep(forTimeInterval: 0.1)
	let recovered = try #require(RecoveryJournalStore().load(fileURL: fileURL, workspaceRoot: workspace))
	#expect(String(data: recovered.text, encoding: .utf8) == "second")
	scheduler.discard(fileURL: fileURL, workspaceRoot: workspace)
	#expect(RecoveryJournalStore().load(fileURL: fileURL, workspaceRoot: workspace) == nil)
}

private func recoveryWorkspace() throws -> URL {
	let workspace = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-recovery-\(UUID().uuidString)", isDirectory: true)
	try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
	return workspace
}
