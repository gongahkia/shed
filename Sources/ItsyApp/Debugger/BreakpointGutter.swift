import AppKit
import Foundation
import ItsyDebugger
import ItsyRender

@MainActor enum ItsyBreakpointGutterCoordinator {
	private weak static var documentController: ItsyDocumentController?
	private static let store = BreakpointStore()
	private static var breakpointsByURL: [URL: [SourceBreakpoint]] = [:]
	private static var loaded = false
	private static var loading = false

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
		loadIfNeeded()
	}

	static func apply(to document: ItsyDocument) {
		loadIfNeeded()
		guard let url = document.fileURL?.standardizedFileURL else {
			document.setBreakpointGutterDecorator(nil)
			return
		}
		document.setBreakpointGutterDecorator(BreakpointGutterDecorator(
			url: url,
			breakpoints: breakpointsByURL[url, default: []],
			toggle: { toggle(line: $0, in: $1) },
			edit: { edit(line: $0, in: $1) }
		))
	}

	private static func loadIfNeeded() {
		guard !loaded, !loading else {
			return
		}
		loading = true
		Task {
			do {
				try await store.load()
				let snapshot = await store.snapshot()
				DispatchQueue.main.async {
					breakpointsByURL = normalize(snapshot)
					loaded = true
					loading = false
					applyAll()
				}
			} catch {
				DispatchQueue.main.async {
					NSLog("failed to load breakpoints: \(error)")
					loaded = true
					loading = false
					applyAll()
				}
			}
		}
	}

	private static func toggle(line: Int, in url: URL) {
		let key = url.standardizedFileURL
		var breakpoints = breakpointsByURL[key, default: []]
		if let index = breakpoints.firstIndex(where: { $0.line == line }) {
			breakpoints.remove(at: index)
		} else {
			breakpoints.append(SourceBreakpoint(line: line))
		}
		persist(breakpoints, for: key)
	}

	private static func edit(line: Int, in url: URL) {
		let key = url.standardizedFileURL
		let current = breakpointsByURL[key, default: []]
		let existing = current.first { $0.line == line }
		guard let result = BreakpointEditor.run(line: line, breakpoint: existing) else {
			return
		}
		var next = current.filter { $0.line != line }
		switch result {
		case let .save(breakpoint):
			next.append(breakpoint)
		case .remove:
			break
		}
		persist(next, for: key)
	}

	private static func persist(_ breakpoints: [SourceBreakpoint], for url: URL) {
		let sorted = sortedBreakpoints(breakpoints)
		if sorted.isEmpty {
			breakpointsByURL.removeValue(forKey: url)
		} else {
			breakpointsByURL[url] = sorted
		}
		applyAll()
		Task {
			await store.replace(sorted, for: url)
			do {
				try await store.save()
			} catch {
				NSLog("failed to save breakpoints: \(error)")
			}
		}
	}

	private static func applyAll() {
		for document in documentController?.documents ?? [] {
			(document as? ItsyDocument).map(apply(to:))
		}
	}

	private static func normalize(_ snapshot: [URL: [SourceBreakpoint]]) -> [URL: [SourceBreakpoint]] {
		Dictionary(uniqueKeysWithValues: snapshot.map { ($0.key.standardizedFileURL, sortedBreakpoints($0.value)) })
	}

	private static func sortedBreakpoints(_ breakpoints: [SourceBreakpoint]) -> [SourceBreakpoint] {
		breakpoints.sorted {
			if $0.line != $1.line {
				return $0.line < $1.line
			}
			return ($0.column ?? 0) < ($1.column ?? 0)
		}
	}
}

private final class BreakpointGutterDecorator: GutterDecorator {
	private let url: URL
	private let breakpoints: [SourceBreakpoint]
	private let toggle: (Int, URL) -> Void
	private let edit: (Int, URL) -> Void

	init(url: URL, breakpoints: [SourceBreakpoint], toggle: @escaping (Int, URL) -> Void, edit: @escaping (Int, URL) -> Void) {
		self.url = url
		self.breakpoints = breakpoints
		self.toggle = toggle
		self.edit = edit
	}

	func gutterMarkers(in lineRange: Range<Int>, for _: MetalTextView) -> [GutterMarker] {
		breakpoints.compactMap { breakpoint in
			let zeroLine = max(0, breakpoint.line - 1)
			guard lineRange.contains(zeroLine) else {
				return nil
			}
			return GutterMarker(
				id: "breakpoint-\(breakpoint.line)",
				line: zeroLine,
				severity: .error,
				message: message(for: breakpoint),
				color: SIMD4<Float>(0.88, 0.12, 0.18, 1.0),
				shape: .dot
			)
		}
	}

	func gutterMarkerClicked(_ marker: GutterMarker, in _: MetalTextView) {
		toggle(marker.line + 1, url)
	}

	func gutterMarkerRightClicked(_ marker: GutterMarker, in _: MetalTextView, event _: NSEvent) -> Bool {
		edit(marker.line + 1, url)
		return true
	}

	func gutterLineClicked(_ line: Int, in _: MetalTextView) -> Bool {
		toggle(line + 1, url)
		return true
	}

	func gutterLineRightClicked(_ line: Int, in _: MetalTextView, event _: NSEvent) -> Bool {
		edit(line + 1, url)
		return true
	}

	func gutterPopoverViewController(for _: GutterMarker, in _: MetalTextView) -> NSViewController? {
		nil
	}

	private func message(for breakpoint: SourceBreakpoint) -> String {
		let details = [
			breakpoint.condition.map { L10n.string("if \($0)") },
			breakpoint.hitCondition.map { L10n.string("hit \($0)") },
			breakpoint.logMessage.map { L10n.string("log \($0)") },
		].compactMap { $0 }
		guard !details.isEmpty else {
			return L10n.string("Breakpoint")
		}
		return "\(L10n.string("Breakpoint"))  \(details.joined(separator: "  "))"
	}
}

private enum BreakpointEditResult {
	case save(SourceBreakpoint)
	case remove
}

@MainActor private enum BreakpointEditor {
	static func run(line: Int, breakpoint: SourceBreakpoint?) -> BreakpointEditResult? {
		let conditionField = textField(value: breakpoint?.condition)
		let hitConditionField = textField(value: breakpoint?.hitCondition)
		let logMessageField = textField(value: breakpoint?.logMessage)
		let container = NSStackView(views: [
			row(title: L10n.string("Condition"), field: conditionField),
			row(title: L10n.string("Hit Condition"), field: hitConditionField),
			row(title: L10n.string("Log Message"), field: logMessageField),
		])
		container.orientation = .vertical
		container.spacing = 8
		container.frame = NSRect(x: 0, y: 0, width: 380, height: 92)

		let alert = NSAlert()
		alert.messageText = L10n.string("Breakpoint \(line)")
		alert.accessoryView = container
		alert.addButton(withTitle: L10n.string("Save"))
		alert.addButton(withTitle: L10n.string("Remove"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		switch alert.runModal() {
		case .alertFirstButtonReturn:
			return .save(SourceBreakpoint(
				line: line,
				column: breakpoint?.column,
				condition: trimmed(conditionField.stringValue),
				hitCondition: trimmed(hitConditionField.stringValue),
				logMessage: trimmed(logMessageField.stringValue)
			))
		case .alertSecondButtonReturn:
			return .remove
		default:
			return nil
		}
	}

	private static func textField(value: String?) -> NSTextField {
		let field = NSTextField(string: value ?? "")
		field.translatesAutoresizingMaskIntoConstraints = false
		field.widthAnchor.constraint(equalToConstant: 260).isActive = true
		return field
	}

	private static func row(title: String, field: NSTextField) -> NSView {
		let label = NSTextField(labelWithString: title)
		label.alignment = .right
		label.translatesAutoresizingMaskIntoConstraints = false
		label.widthAnchor.constraint(equalToConstant: 104).isActive = true
		let row = NSStackView(views: [label, field])
		row.orientation = .horizontal
		row.alignment = .firstBaseline
		row.spacing = 10
		return row
	}

	private static func trimmed(_ value: String) -> String? {
		let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
		return trimmed.isEmpty ? nil : trimmed
	}
}
