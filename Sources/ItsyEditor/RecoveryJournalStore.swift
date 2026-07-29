import Foundation

public struct RecoveryJournal: Codable, Equatable, Sendable {
	public let filePath: String
	public let text: Data
	public let updatedAt: Date

	public init(fileURL: URL, text: String, updatedAt: Date = Date()) {
		filePath = fileURL.standardizedFileURL.path
		self.text = Data(text.utf8)
		self.updatedAt = updatedAt
	}
}

public struct RecoveryJournalStore: Sendable {
	public init() {}

	public func recoveryDirectory(workspaceRoot: URL) -> URL {
		workspaceRoot.appendingPathComponent(".itsy/recovery", isDirectory: true)
	}

	public func journalURL(fileURL: URL, workspaceRoot: URL) -> URL {
		recoveryDirectory(workspaceRoot: workspaceRoot).appendingPathComponent(UndoHistoryStore.fileHash(for: fileURL) + ".json")
	}

	public func save(_ journal: RecoveryJournal, workspaceRoot: URL) throws {
		let directory = recoveryDirectory(workspaceRoot: workspaceRoot)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		try AtomicFileWriter.write(data: JSONEncoder().encode(journal), to: journalURL(fileURL: URL(fileURLWithPath: journal.filePath), workspaceRoot: workspaceRoot))
	}

	public func load(fileURL: URL, workspaceRoot: URL) -> RecoveryJournal? {
		guard let data = try? Data(contentsOf: journalURL(fileURL: fileURL, workspaceRoot: workspaceRoot)),
		      let journal = try? JSONDecoder().decode(RecoveryJournal.self, from: data),
		      journal.filePath == fileURL.standardizedFileURL.path
		else {
			return nil
		}
		return journal
	}

	public func remove(fileURL: URL, workspaceRoot: URL) throws {
		let url = journalURL(fileURL: fileURL, workspaceRoot: workspaceRoot)
		guard FileManager.default.fileExists(atPath: url.path) else {
			return
		}
		try FileManager.default.removeItem(at: url)
	}
}

public final class RecoveryJournalScheduler: @unchecked Sendable {
	private let queue = DispatchQueue(label: "dev.itsy.recovery-journal")
	private var generation = 0

	public init() {}

	public func schedule(_ journal: RecoveryJournal, workspaceRoot: URL, delay: TimeInterval = 0.2) {
		queue.async {
			self.generation += 1
			let generation = self.generation
			self.queue.asyncAfter(deadline: .now() + delay) {
				guard generation == self.generation else {
					return
				}
				try? RecoveryJournalStore().save(journal, workspaceRoot: workspaceRoot)
			}
		}
	}

	public func discard(fileURL: URL, workspaceRoot: URL) {
		queue.sync {
			generation += 1
			try? RecoveryJournalStore().remove(fileURL: fileURL, workspaceRoot: workspaceRoot)
		}
	}
}
