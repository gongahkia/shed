import Foundation
import ItsyLSP

public actor LSPDiagnosticsAggregator {
	private let root: URL
	private var problemsByURI: [String: [WorkspaceProblem]] = [:]
	private var documentVersionsByURI: [String: Int] = [:]

	public init(root: URL) {
		self.root = root.standardizedFileURL
	}

	@discardableResult
	public func ingest(_ params: LSPPublishDiagnosticsParams, source: String? = nil) -> Bool {
		if let expected = documentVersionsByURI[params.uri], let received = params.version, received != expected {
			return false
		}
		let mapped = params.diagnostics.compactMap { diagnostic in
			Self.toWorkspaceProblem(
				diagnostic,
				uri: params.uri,
				root: root,
				source: source
			)
		}
		if mapped.isEmpty {
			problemsByURI.removeValue(forKey: params.uri)
		} else {
			problemsByURI[params.uri] = mapped
		}
		return true
	}

	public func recordDocumentVersion(_ version: Int, forURI uri: String) {
		documentVersionsByURI[uri] = version
	}

	public func reset(forURI uri: String) {
		problemsByURI.removeValue(forKey: uri)
	}

	public func removeDocument(forURI uri: String) {
		problemsByURI.removeValue(forKey: uri)
		documentVersionsByURI.removeValue(forKey: uri)
	}

	public func snapshot() -> WorkspaceProblemSnapshot {
		let flat = problemsByURI.values.flatMap { $0 }
		return WorkspaceProblemSnapshot(root: root, problems: flat)
	}

	public func problems(forURI uri: String) -> [WorkspaceProblem] {
		problemsByURI[uri] ?? []
	}

	public static func toWorkspaceProblem(
		_ diagnostic: LSPDiagnostic,
		uri: String,
		root: URL,
		source: String? = nil
	) -> WorkspaceProblem? {
		guard let path = relativePath(forURI: uri, root: root) else {
			return nil
		}
		let lineOneBased = max(1, diagnostic.range.start.line + 1)
		let columnOneBased = max(1, diagnostic.range.start.character + 1)
		let related = diagnostic.relatedInformation?.compactMap {
			item -> WorkspaceProblemRelatedInformation? in
			guard let relatedPath = relativePath(forURI: item.location.uri, root: root) else {
				return nil
			}
			return WorkspaceProblemRelatedInformation(
				path: relatedPath,
				line: max(1, item.location.range.start.line + 1),
				column: max(1, item.location.range.start.character + 1),
				message: item.message
			)
		} ?? []
		return WorkspaceProblem(
			path: path,
			line: lineOneBased,
			column: columnOneBased,
			severity: mapSeverity(diagnostic.severity),
			message: diagnostic.message,
			source: source ?? diagnostic.source ?? "lsp",
			relatedInformation: related
		)
	}

	public static func mapSeverity(_ severity: LSPDiagnosticSeverity?) -> WorkspaceProblemSeverity {
		switch severity {
		case .error?:
			return .error
		case .warning?:
			return .warning
		case .information?:
			return .info
		case .hint?:
			return .hint
		case nil:
			return .error
		}
	}

	public static func relativePath(forURI uri: String, root: URL) -> String? {
		guard let url = URL(string: uri) else {
			return nil
		}
		let resolved = url.standardizedFileURL.path
		let rootPath = root.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		guard resolved.hasPrefix(prefix) else {
			return nil
		}
		return String(resolved.dropFirst(prefix.count))
	}
}
