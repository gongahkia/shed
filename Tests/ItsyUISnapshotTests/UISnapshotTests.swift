import AppKit
import CryptoKit
import Foundation
@testable import ItsyApp
import ItsyConfig
import ItsyEditor
@testable import ItsyRender
import Testing

@MainActor
@Suite(.serialized)
struct ItsyUISnapshotTests {
	@Test func mainEditorWithSelectionAndCursorMatchesGolden() throws {
		try SnapshotHarness.assertSnapshot(named: "main-editor") {
			SnapshotSubject(view: EditorSnapshotView(frame: NSRect(x: 0, y: 0, width: 640, height: 260)))
		}
	}

	@Test func gutterMarkersMatchGolden() throws {
		try SnapshotHarness.assertSnapshot(named: "gutter-markers", size: NSSize(width: 96, height: 180)) {
			let gutter = GutterView(frame: NSRect(x: 0, y: 0, width: 96, height: 180))
			gutter.showsLineNumbers = true
			gutter.lineCount = 9
			gutter.visibleLineRange = 0 ..< 9
			gutter.lineNumberRightEdge = 42
			gutter.markerLayouts = [
				GutterMarkerLayout(marker: GutterMarker(id: "git-added", line: 1, severity: .info, message: "added", color: SIMD4<Float>(0.28, 0.78, 0.46, 1.0)), rect: CGRect(x: 56, y: 29, width: 4, height: 20)),
				GutterMarkerLayout(marker: GutterMarker(id: "diagnostic", line: 3, severity: .error, message: "error", shape: .dot), rect: CGRect(x: 68, y: 69, width: 10, height: 10)),
				GutterMarkerLayout(marker: GutterMarker(id: "deleted", line: 5, severity: .error, message: "deleted", color: SIMD4<Float>(0.95, 0.25, 0.22, 1.0), placement: .betweenLines), rect: CGRect(x: 54, y: 115, width: 28, height: 6)),
				GutterMarkerLayout(marker: GutterMarker(id: "fold", line: 7, severity: .hint, message: "fold", shape: .foldClosed), rect: CGRect(x: 69, y: 149, width: 9, height: 9)),
			]
			return SnapshotSubject(view: gutter)
		}
	}

	@Test func tabsBarMatchesGolden() throws {
		try SnapshotHarness.assertSnapshot(named: "tabs-bar", size: NSSize(width: 640, height: 46)) {
			SnapshotSubject(view: TabsSnapshotView(frame: NSRect(x: 0, y: 0, width: 640, height: 46)))
		}
	}

	@Test func commandPaletteMatchesGolden() throws {
		try SnapshotHarness.assertSnapshot(named: "command-palette", size: NSSize(width: 560, height: 280)) {
			try commandPaletteSubject()
		}
	}

	@Test func terminalMatchesGolden() throws {
		try SnapshotHarness.assertSnapshot(named: "terminal", size: NSSize(width: 640, height: 240)) {
			let terminal = ItsyTerminalView(frame: NSRect(x: 0, y: 0, width: 640, height: 240))
			terminal.applyTerminalSettings(ItsySettings.TerminalSettings(fontSize: 13, scrollbackLines: 200))
			terminal.ingest(Data("""
			\u{001B}[32mitsy\u{001B}[0m@local ~/project
			$ swift test --filter Snapshot
			\u{001B}[33mwarning:\u{001B}[0m golden updated locally
			\u{001B}[31merror:\u{001B}[0m pixel drift detected
			""".utf8))
			let window = NSWindow(contentRect: terminal.frame, styleMask: [], backing: .buffered, defer: false)
			window.contentView = terminal
			window.makeFirstResponder(terminal)
			return SnapshotSubject(view: terminal, retained: [window])
		}
	}
}

private struct SnapshotSubject {
	var view: NSView
	var retained: [AnyObject] = []
	var cleanup: () -> Void = {}
}

private enum SnapshotHarness {
	static func assertSnapshot(named name: String, size: NSSize? = nil, makeSubject: () throws -> SnapshotSubject) throws {
		_ = NSApplication.shared
		let subject = try makeSubject()
		defer { subject.cleanup() }
		let targetSize = size ?? subject.view.frame.size
		let actual = try render(subject.view, size: targetSize)
		if ProcessInfo.processInfo.environment["ITSY_RECORD_SNAPSHOTS"] == "1" {
			try record(actual.pngData, named: name)
			return
		}
		let goldenURL = try fixtureURL(named: name)
		let golden = try decodePNG(Data(contentsOf: goldenURL))
		guard actual.width == golden.width, actual.height == golden.height, actual.rgba == golden.rgba else {
			let actualURL = try writeFailure(actual.pngData, named: name)
			let actualHash = sha256(actual.rgba)
			let goldenHash = sha256(golden.rgba)
			Issue.record("snapshot \(name) differed: actual \(actualHash), golden \(goldenHash), wrote \(actualURL.path)")
			return
		}
	}

	private static func render(_ view: NSView, size: NSSize, scale: CGFloat = 2) throws -> SnapshotImage {
		view.appearance = NSAppearance(named: .aqua)
		view.frame = NSRect(origin: .zero, size: size)
		view.layoutSubtreeIfNeeded()
		let width = Int((size.width * scale).rounded())
		let height = Int((size.height * scale).rounded())
		guard let rep = NSBitmapImageRep(
			bitmapDataPlanes: nil,
			pixelsWide: width,
			pixelsHigh: height,
			bitsPerSample: 8,
			samplesPerPixel: 4,
			hasAlpha: true,
			isPlanar: false,
			colorSpaceName: .deviceRGB,
			bitmapFormat: [],
			bytesPerRow: 0,
			bitsPerPixel: 0
		) else {
			throw SnapshotError.renderFailed
		}
		rep.size = size
		guard let context = NSGraphicsContext(bitmapImageRep: rep) else {
			throw SnapshotError.renderFailed
		}
		NSGraphicsContext.saveGraphicsState()
		NSGraphicsContext.current = context
		NSColor(calibratedWhite: 1, alpha: 1).setFill()
		NSRect(origin: .zero, size: size).fill()
		view.displayIgnoringOpacity(view.bounds, in: context)
		NSGraphicsContext.restoreGraphicsState()
		guard let png = rep.representation(using: .png, properties: [:]) else {
			throw SnapshotError.renderFailed
		}
		let decoded = try decodePNG(png)
		return SnapshotImage(width: decoded.width, height: decoded.height, rgba: decoded.rgba, pngData: png)
	}

	private static func fixtureURL(named name: String) throws -> URL {
		guard let url = Bundle.module.url(forResource: name, withExtension: "png", subdirectory: "Fixtures") else {
			throw SnapshotError.missingFixture(name)
		}
		return url
	}

	private static func record(_ data: Data, named name: String) throws {
		let directory = ProcessInfo.processInfo.environment["ITSY_SNAPSHOT_FIXTURE_DIR"].map(URL.init(fileURLWithPath:))
			?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("Tests/ItsyUISnapshotTests/Fixtures", isDirectory: true)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		try data.write(to: directory.appendingPathComponent("\(name).png"), options: .atomic)
	}

	private static func writeFailure(_ data: Data, named name: String) throws -> URL {
		let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("itsy-ui-snapshots", isDirectory: true)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		let url = directory.appendingPathComponent("\(name)-actual.png")
		try data.write(to: url, options: .atomic)
		return url
	}

	private static func decodePNG(_ data: Data) throws -> SnapshotImage {
		guard
			let image = NSImage(data: data),
			let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
		else {
			throw SnapshotError.decodeFailed
		}
		let width = cgImage.width
		let height = cgImage.height
		var rgba = Data(count: width * height * 4)
		let colorSpace = CGColorSpaceCreateDeviceRGB()
		try rgba.withUnsafeMutableBytes { buffer in
			guard let baseAddress = buffer.baseAddress,
			      let context = CGContext(
			      	data: baseAddress,
			      	width: width,
			      	height: height,
			      	bitsPerComponent: 8,
			      	bytesPerRow: width * 4,
			      	space: colorSpace,
			      	bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
			      )
			else {
				throw SnapshotError.decodeFailed
			}
			context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
		}
		return SnapshotImage(width: width, height: height, rgba: rgba, pngData: data)
	}

	private static func sha256(_ data: Data) -> String {
		SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
	}
}

private struct SnapshotImage {
	var width: Int
	var height: Int
	var rgba: Data
	var pngData: Data
}

private enum SnapshotError: Error, CustomStringConvertible {
	case decodeFailed
	case missingFixture(String)
	case renderFailed

	var description: String {
		switch self {
		case .decodeFailed:
			return "failed to decode snapshot PNG"
		case let .missingFixture(name):
			return "missing snapshot fixture: \(name).png"
		case .renderFailed:
			return "failed to render snapshot"
		}
	}
}

private final class EditorSnapshotView: NSView {
	private let lines = [
		"import AppKit",
		"",
		"let message = \"Hello, Itsy\"",
		"view.render(selection: range)",
		"cursor.move(to: .lineEnd)",
	]

	override var isFlipped: Bool { true }

	override func draw(_ dirtyRect: NSRect) {
		NSColor(calibratedRed: 0.985, green: 0.988, blue: 0.992, alpha: 1).setFill()
		dirtyRect.fill()
		NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
		NSRect(x: 0, y: 0, width: 56, height: bounds.height).fill()
		let font = NSFont.monospacedSystemFont(ofSize: 15, weight: .regular)
		let lineAttributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(calibratedRed: 0.18, green: 0.20, blue: 0.24, alpha: 1)]
		let numberAttributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(calibratedRed: 0.50, green: 0.54, blue: 0.60, alpha: 1)]
		NSColor.selectedContentBackgroundColor.withAlphaComponent(0.30).setFill()
		NSRect(x: 154, y: 51, width: 168, height: 22).fill()
		NSColor.systemBlue.setFill()
		NSRect(x: 331, y: 51, width: 2, height: 22).fill()
		for (index, line) in lines.enumerated() {
			let y = 18 + CGFloat(index) * 34
			"\(index + 1)".draw(at: NSPoint(x: 28, y: y), withAttributes: numberAttributes)
			line.draw(at: NSPoint(x: 76, y: y), withAttributes: lineAttributes)
		}
	}
}

private final class TabsSnapshotView: NSView {
	override var isFlipped: Bool { true }

	override func draw(_ dirtyRect: NSRect) {
		NSColor.windowBackgroundColor.setFill()
		dirtyRect.fill()
		NSColor.separatorColor.setFill()
		NSRect(x: 0, y: bounds.height - 1, width: bounds.width, height: 1).fill()
		let tabs = [
			(title: "EditorWindowController.swift", dirty: true, selected: true),
			(title: "Parser.swift", dirty: false, selected: false),
			(title: "README.md", dirty: false, selected: false),
		]
		var x: CGFloat = 8
		for tab in tabs {
			let width = min(max(CGFloat(tab.title.count) * 7.2 + 42, 92), 220)
			let rect = NSRect(x: x, y: 7, width: width, height: 28)
			(tab.selected ? NSColor.selectedControlColor.withAlphaComponent(0.24) : NSColor.clear).setFill()
			NSBezierPath(roundedRect: rect, xRadius: 5, yRadius: 5).fill()
			let text = "\(tab.dirty ? "• " : "")\(tab.title)"
			let attributes: [NSAttributedString.Key: Any] = [
				.font: NSFont.systemFont(ofSize: 12, weight: tab.selected ? .semibold : .regular),
				.foregroundColor: NSColor.labelColor,
			]
			text.draw(at: NSPoint(x: x + 10, y: 13), withAttributes: attributes)
			"×".draw(at: NSPoint(x: rect.maxX - 19, y: 12), withAttributes: attributes)
			x += width + 3
		}
	}
}

@MainActor private func commandPaletteSubject() throws -> SnapshotSubject {
	var registry = CommandRegistry()
	try registry.register([
		Command(id: "file.open", title: "Open File", defaultKey: "Cmd-O") {},
		Command(id: "view.commandPalette", title: "Command Palette", defaultKey: "Cmd-Shift-P") {},
		Command(id: "editor.rename", title: "Rename Symbol", defaultKey: "F2") {},
	])
	let documentController = ItsyDocumentController()
	let coordinator = CommandPaletteCoordinator(
		documentController: documentController,
		commandRegistryProvider: { registry },
		activeDocumentProvider: { nil }
	)
	let contentView = coordinator.makeCommandPaletteContentView()
	coordinator.loadCommandPaletteCommands()
	contentView.frame = NSRect(x: 0, y: 0, width: 560, height: 280)
	contentView.layoutSubtreeIfNeeded()
	return SnapshotSubject(view: contentView, retained: [documentController, coordinator])
}
