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
			guard let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: info.location.uri, root: root) else {
				return nil
			}
			return WorkspaceSymbol(
				name: info.name,
				kind: mapKind(info.kind),
				relativePath: relativePath,
				line: max(1, info.location.range.start.line + 1),
				column: max(1, info.location.range.start.character + 1)
			)
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
				column: max(1, symbol.selectionRange.start.character + 1)
			))
			if let children = symbol.children, !children.isEmpty {
				collect(children, relativePath: relativePath, into: &output)
			}
		}
	}
}
