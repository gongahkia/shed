import Foundation

public struct LSPWorkspaceEditPreview: Equatable, Sendable {
	public struct File: Equatable, Sendable {
		public var uri: String
		public var originalText: String
		public var updatedText: String

		public init(uri: String, originalText: String, updatedText: String) {
			self.uri = uri
			self.originalText = originalText
			self.updatedText = updatedText
		}
	}

	public var files: [File]

	public init(resolved: [LSPWorkspaceEditApply.ResolvedFile], sources: [String: String]) throws {
		files = try resolved.map { file in
			guard let originalText = sources[file.uri] else {
				throw LSPWorkspaceEditApplyError.sourceMissing(uri: file.uri)
			}
			return File(uri: file.uri, originalText: originalText, updatedText: file.updatedText)
		}
	}

	public var requiresConfirmation: Bool {
		files.count > 1
	}
}

public enum LSPWorkspaceEditTransaction {
	public static func commit(
		_ files: [LSPWorkspaceEditPreview.File],
		apply: (LSPWorkspaceEditPreview.File) throws -> Void,
		rollback: (LSPWorkspaceEditPreview.File) throws -> Void
	) throws {
		var applied: [LSPWorkspaceEditPreview.File] = []
		do {
			for file in files {
				try apply(file)
				applied.append(file)
			}
		} catch {
			for file in applied.reversed() {
				try? rollback(file)
			}
			throw error
		}
	}
}
