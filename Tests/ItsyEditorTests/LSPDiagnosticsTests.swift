import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func lspDiagnosticsMapsLSPSeverityToWorkspaceSeverity() {
	#expect(LSPDiagnosticsAggregator.mapSeverity(.error) == .error)
	#expect(LSPDiagnosticsAggregator.mapSeverity(.warning) == .warning)
	#expect(LSPDiagnosticsAggregator.mapSeverity(.information) == .info)
	#expect(LSPDiagnosticsAggregator.mapSeverity(.hint) == .hint)
	#expect(LSPDiagnosticsAggregator.mapSeverity(nil) == .error)
}

@Test func lspDiagnosticsAggregatorReplacesByURIAndOneIndexesPositions() async {
	let root = URL(fileURLWithPath: "/tmp/itsy-diag")
	let aggregator = LSPDiagnosticsAggregator(root: root)
	let uri = "file:///tmp/itsy-diag/Sources/App.swift"
	let first = LSPPublishDiagnosticsParams(
		uri: uri,
		diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 0, character: 4), end: LSPPosition(line: 0, character: 10)),
				severity: .warning,
				source: "swift",
				message: "unused let"
			),
		]
	)
	await aggregator.ingest(first, source: "sourcekit-lsp")
	var snapshot = await aggregator.snapshot()
	#expect(snapshot.problems.count == 1)
	#expect(snapshot.problems[0].path == "Sources/App.swift")
	#expect(snapshot.problems[0].line == 1)
	#expect(snapshot.problems[0].column == 5)
	#expect(snapshot.problems[0].severity == .warning)
	#expect(snapshot.problems[0].source == "sourcekit-lsp")

	let replacement = LSPPublishDiagnosticsParams(
		uri: uri,
		diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 2, character: 0), end: LSPPosition(line: 2, character: 8)),
				severity: .error,
				message: "missing return"
			),
		]
	)
	await aggregator.ingest(replacement, source: "sourcekit-lsp")
	snapshot = await aggregator.snapshot()
	#expect(snapshot.problems.count == 1)
	#expect(snapshot.problems[0].severity == .error)
	#expect(snapshot.problems[0].line == 3)
	#expect(snapshot.problems[0].message == "missing return")
}

@Test func lspDiagnosticsMapsRelatedInformationToWorkspaceProblems() async {
	let root = URL(fileURLWithPath: "/tmp/itsy-diag")
	let aggregator = LSPDiagnosticsAggregator(root: root)
	await aggregator.ingest(LSPPublishDiagnosticsParams(
		uri: "file:///tmp/itsy-diag/Sources/App.swift",
		diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
				severity: .error,
				message: "bad call",
				relatedInformation: [
					LSPDiagnosticRelatedInformation(
						location: LSPLocation(
							uri: "file:///tmp/itsy-diag/Sources/Helper.swift",
							range: LSPRange(start: LSPPosition(line: 4, character: 6), end: LSPPosition(line: 4, character: 11))
						),
						message: "declared here"
					),
					LSPDiagnosticRelatedInformation(
						location: LSPLocation(
							uri: "file:///elsewhere/Other.swift",
							range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 1))
						),
						message: "outside"
					),
				]
			),
		]
	))

	let problem = await aggregator.snapshot().problems[0]
	#expect(problem.relatedInformation == [
		WorkspaceProblemRelatedInformation(path: "Sources/Helper.swift", line: 5, column: 7, message: "declared here"),
	])
}

@Test func lspDiagnosticsAggregatorRemovesEntryWhenEmptyDiagnosticsArrive() async {
	let root = URL(fileURLWithPath: "/tmp/itsy-diag")
	let aggregator = LSPDiagnosticsAggregator(root: root)
	let uri = "file:///tmp/itsy-diag/Sources/App.swift"
	await aggregator.ingest(LSPPublishDiagnosticsParams(uri: uri, diagnostics: [
		LSPDiagnostic(
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 1)),
			severity: .warning,
			message: "noop"
		),
	]))
	#expect(await aggregator.problems(forURI: uri).count == 1)
	await aggregator.ingest(LSPPublishDiagnosticsParams(uri: uri, diagnostics: []))
	#expect(await aggregator.problems(forURI: uri).isEmpty)
	#expect(await aggregator.snapshot().problems.isEmpty)
}

@Test func lspDiagnosticsAggregatorIgnoresFilesOutsideWorkspaceRoot() async {
	let root = URL(fileURLWithPath: "/tmp/itsy-diag")
	let aggregator = LSPDiagnosticsAggregator(root: root)
	await aggregator.ingest(LSPPublishDiagnosticsParams(
		uri: "file:///elsewhere/File.swift",
		diagnostics: [
			LSPDiagnostic(
				range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 1)),
				severity: .error,
				message: "x"
			),
		]
	))
	#expect(await aggregator.snapshot().problems.isEmpty)
}

@Test func lspDiagnosticsAggregatorRejectsMismatchedDocumentVersions() async {
	let root = URL(fileURLWithPath: "/tmp/itsy-diag")
	let aggregator = LSPDiagnosticsAggregator(root: root)
	let uri = "file:///tmp/itsy-diag/Sources/App.swift"
	await aggregator.recordDocumentVersion(3, forURI: uri)
	let stale = await aggregator.ingest(LSPPublishDiagnosticsParams(
		uri: uri,
		version: 2,
		diagnostics: [LSPDiagnostic(
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 1)),
			severity: .error,
			message: "stale"
		)]
	))
	#expect(!stale)
	#expect(await aggregator.snapshot().problems.isEmpty)
	let current = await aggregator.ingest(LSPPublishDiagnosticsParams(
		uri: uri,
		version: 3,
		diagnostics: [LSPDiagnostic(
			range: LSPRange(start: LSPPosition(line: 1, character: 0), end: LSPPosition(line: 1, character: 1)),
			severity: .warning,
			message: "current"
		)]
	))
	#expect(current)
	#expect(await aggregator.snapshot().problems.map(\.message) == ["current"])
}
