import Foundation
import ItsyLSP

public enum LSPWorkspaceEditApplyError: Error, Equatable, Sendable {
	case sourceMissing(uri: String)
	case staleDocumentVersion(uri: String, expected: Int, received: Int)
	case editFailed(uri: String, underlying: String)
	case empty
}

public enum LSPWorkspaceEditApply {
	public struct ResolvedFile: Equatable, Sendable {
		public let uri: String
		public let updatedText: String

		public init(uri: String, updatedText: String) {
			self.uri = uri
			self.updatedText = updatedText
		}
	}

	public static func apply(
		_ edit: LSPWorkspaceEdit,
		sources: [String: String],
		documentVersions: [String: Int] = [:]
	) throws -> [ResolvedFile] {
		try validateVersions(edit, documentVersions: documentVersions)
		let groups = normalize(edit)
		guard !groups.isEmpty else {
			throw LSPWorkspaceEditApplyError.empty
		}
		var results: [ResolvedFile] = []
		results.reserveCapacity(groups.count)
		for (uri, edits) in groups {
			guard let source = sources[uri] else {
				throw LSPWorkspaceEditApplyError.sourceMissing(uri: uri)
			}
			do {
				let updated = try LSPTextEditApply.apply(edits, to: source)
				results.append(ResolvedFile(uri: uri, updatedText: updated))
			} catch {
				throw LSPWorkspaceEditApplyError.editFailed(uri: uri, underlying: String(describing: error))
			}
		}
		results.sort { $0.uri < $1.uri }
		return results
	}

	private static func validateVersions(_ edit: LSPWorkspaceEdit, documentVersions: [String: Int]) throws {
		for change in edit.documentChanges ?? [] {
			guard
				let received = change.textDocument.version,
				let expected = documentVersions[change.textDocument.uri],
				expected != received
			else {
				continue
			}
			throw LSPWorkspaceEditApplyError.staleDocumentVersion(
				uri: change.textDocument.uri,
				expected: expected,
				received: received
			)
		}
	}

	public static func normalize(_ edit: LSPWorkspaceEdit) -> [String: [LSPTextEdit]] {
		var result: [String: [LSPTextEdit]] = [:]
		if let documentChanges = edit.documentChanges {
			for change in documentChanges {
				result[change.textDocument.uri, default: []].append(contentsOf: change.edits)
			}
		} else if let changes = edit.changes {
			for (uri, edits) in changes {
				result[uri, default: []].append(contentsOf: edits)
			}
		}
		return result
	}
}
