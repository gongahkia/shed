import Foundation
import ItsyLSP

public enum LSPSymbolAdapter {
	public static func workspaceSymbols(from documentSymbols: [LSPDocumentSymbol], relativePath: String) -> [WorkspaceSymbol] {
		var collected: [WorkspaceSymbol] = []
		collect(documentSymbols, relativePath: relativePath, into: &collected)
		return collected
	}

	public static func workspaceSymbols(from symbolInformation: [LSPSymbolInformation], root: URL) -> [WorkspaceSymbol] {
		symbolInformation.compactMap { info in
			workspaceSymbol(name: info.name, kind: info.kind, location: info.location, root: root)
		}
	}

	public static func workspaceSymbols(from workspaceSymbols: [LSPWorkspaceSymbol], root: URL) -> [WorkspaceSymbol] {
		workspaceSymbols.compactMap { symbol in
			guard let location = symbol.location.resolvedLocation else {
				return nil
			}
			return workspaceSymbol(name: symbol.name, kind: symbol.kind, location: location, root: root)
		}
	}

	public static func mapKind(_ kind: LSPSymbolKind) -> WorkspaceSymbolKind {
		switch kind {
		case .method, .constructor:
			return .method
		case .function:
			return .function
		case .class, .struct, .enum, .interface, .typeParameter:
			return .type
		case .variable, .constant, .property, .field, .enumMember:
			return .variable
		default:
			return .variable
		}
	}

	private static func collect(_ symbols: [LSPDocumentSymbol], relativePath: String, into output: inout [WorkspaceSymbol]) {
		for symbol in symbols {
			output.append(WorkspaceSymbol(
				name: symbol.name,
				kind: mapKind(symbol.kind),
				relativePath: relativePath,
				line: max(1, symbol.selectionRange.start.line + 1),
				column: max(1, symbol.selectionRange.start.character + 1),
				endLine: max(1, symbol.selectionRange.end.line + 1),
				endColumn: max(1, symbol.selectionRange.end.character + 1)
			))
			if let children = symbol.children, !children.isEmpty {
				collect(children, relativePath: relativePath, into: &output)
			}
		}
	}

	private static func workspaceSymbol(name: String, kind: LSPSymbolKind, location: LSPLocation, root: URL) -> WorkspaceSymbol? {
		guard let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: location.uri, root: root) else {
			return nil
		}
		return WorkspaceSymbol(
			name: name,
			kind: mapKind(kind),
			relativePath: relativePath,
			line: max(1, location.range.start.line + 1),
			column: max(1, location.range.start.character + 1),
			endLine: max(1, location.range.end.line + 1),
			endColumn: max(1, location.range.end.character + 1)
		)
	}
}
