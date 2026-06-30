import Foundation
import ItsyLSP

public actor LSPDiagnosticsAggregator {
	private let root: URL
	private var problemsByURI: [String: [WorkspaceProblem]] = [:]

	public init(root: URL) {
		self.root = root.standardizedFileURL
	}

	public func ingest(_ params: LSPPublishDiagnosticsParams, source: String? = nil) {
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
	}

	public func reset(forURI uri: String) {
		problemsByURI.removeValue(forKey: uri)
	}

	public func snapshot() -> WorkspaceProblemSnapshot {
		let flat = problemsByURI.values.flatMap { $0 }
		return WorkspaceProblemSnapshot(root: root, problems: flat)
	}

	public func problems(forURI uri: String) -> [WorkspaceProblem] {
		problemsByURI[uri] ?? []
	}

	public static func toWorkspaceProblem(_ diagnostic: LSPDiagnostic, uri: String, root: URL, source: String? = nil) -> WorkspaceProblem? {
		guard let path = relativePath(forURI: uri, root: root) else {
			return nil
		}
		let lineOneBased = max(1, diagnostic.range.start.line + 1)
		let columnOneBased = max(1, diagnostic.range.start.character + 1)
		return WorkspaceProblem(
			path: path,
			line: lineOneBased,
			column: columnOneBased,
			severity: mapSeverity(diagnostic.severity),
			message: diagnostic.message,
			source: source ?? diagnostic.source ?? "lsp"
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
