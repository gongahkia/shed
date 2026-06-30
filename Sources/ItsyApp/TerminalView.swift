import AppKit
import Foundation

final class ItsyTerminalView: NSView {
	private let emulator = ItsyTerminalEmulator()
	private let font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
	private var characterSize = CGSize(width: 7, height: 15)
	private var scrollbackOffset = 0
	var onInput: ((Data) -> Void)?
	var onResize: ((Int, Int) -> Void)?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		commonInit()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		commonInit()
	}

	override var acceptsFirstResponder: Bool {
		true
	}

	override var isFlipped: Bool {
		true
	}

	var terminalSize: (columns: Int, rows: Int) {
		let columns = max(20, Int(bounds.width / max(characterSize.width, 1)))
		let rows = max(5, Int(bounds.height / max(characterSize.height, 1)))
		return (columns, rows)
	}

	func ingest(_ data: Data) {
		emulator.feed(data)
		if emulator.alternateScreen {
			scrollbackOffset = 0
		}
		needsDisplay = true
	}

	func reset() {
		emulator.reset()
		scrollbackOffset = 0
		needsDisplay = true
	}

	func clearScrollback() {
		emulator.clearScrollback()
		scrollbackOffset = 0
		needsDisplay = true
	}

	override func setFrameSize(_ newSize: NSSize) {
		super.setFrameSize(newSize)
		syncSize()
	}

	override func viewDidMoveToWindow() {
		super.viewDidMoveToWindow()
		window?.makeFirstResponder(self)
		syncSize()
	}

	override func draw(_ dirtyRect: NSRect) {
		NSColor.textBackgroundColor.setFill()
		dirtyRect.fill()
		let snapshot = emulator.snapshot(scrollbackOffset: scrollbackOffset)
		let paragraph = NSMutableParagraphStyle()
		paragraph.lineBreakMode = .byClipping
		let attributes: [NSAttributedString.Key: Any] = [
			.font: font,
			.foregroundColor: NSColor.textColor,
			.paragraphStyle: paragraph,
		]
		for (index, line) in snapshot.lines.enumerated() {
			let rect = NSRect(
				x: 4,
				y: CGFloat(index) * characterSize.height + 2,
				width: bounds.width - 8,
				height: characterSize.height
			)
			(line as NSString).draw(in: rect, withAttributes: attributes)
		}
		drawCursor(snapshot)
	}

	override func keyDown(with event: NSEvent) {
		guard let data = encodedInput(for: event) else {
			super.keyDown(with: event)
			return
		}
		scrollbackOffset = 0
		onInput?(data)
	}

	override func scrollWheel(with event: NSEvent) {
		guard !emulator.alternateScreen else {
			return
		}
		let lineDelta = Int((event.scrollingDeltaY / max(characterSize.height, 1)).rounded(.toNearestOrAwayFromZero))
		scrollbackOffset = max(0, scrollbackOffset + lineDelta)
		needsDisplay = true
	}

	@objc func paste(_ sender: Any?) {
		guard let text = NSPasteboard.general.string(forType: .string) else {
			return
		}
		if emulator.bracketedPaste {
			onInput?(Data("\u{1B}[200~\(text)\u{1B}[201~".utf8))
		} else {
			onInput?(Data(text.utf8))
		}
	}

	private func commonInit() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.textBackgroundColor.cgColor
		let sample = ("M" as NSString).size(withAttributes: [.font: font])
		characterSize = CGSize(width: ceil(sample.width), height: ceil(font.ascender - font.descender + font.leading + 2))
	}

	private func syncSize() {
		let size = terminalSize
		emulator.resize(columns: size.columns, rows: size.rows)
		onResize?(size.columns, size.rows)
		needsDisplay = true
	}

	private func drawCursor(_ snapshot: TerminalSnapshot) {
		guard window?.firstResponder === self, scrollbackOffset == 0 else {
			return
		}
		let x = 4 + CGFloat(min(snapshot.cursorColumn, emulator.columns - 1)) * characterSize.width
		let y = 2 + CGFloat(min(snapshot.cursorRow, emulator.rows - 1)) * characterSize.height
		let rect = NSRect(x: x, y: y, width: max(2, characterSize.width), height: characterSize.height)
		NSColor.textColor.withAlphaComponent(0.24).setFill()
		rect.fill()
	}

	private func encodedInput(for event: NSEvent) -> Data? {
		let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
		if flags.contains(.command) {
			return nil
		}
		if flags.contains(.control), let scalar = event.charactersIgnoringModifiers?.unicodeScalars.first {
			switch scalar.value {
			case 64 ... 95:
				return Data([UInt8(scalar.value - 64)])
			case 97 ... 122:
				return Data([UInt8(scalar.value - 96)])
			default:
				break
			}
		}
		if let special = encodedSpecialKey(event) {
			return special
		}
		guard let characters = event.characters, !characters.isEmpty else {
			return nil
		}
		if flags.contains(.option) {
			return Data("\u{1B}\(characters)".utf8)
		}
		return Data(characters.utf8)
	}

	private func encodedSpecialKey(_ event: NSEvent) -> Data? {
		switch event.keyCode {
		case 36:
			return Data([13])
		case 48:
			return Data([9])
		case 51:
			return Data([127])
		case 53:
			return Data([27])
		case 117:
			return Data("\u{1B}[3~".utf8)
		case 115:
			return Data("\u{1B}[1~".utf8)
		case 119:
			return Data("\u{1B}[4~".utf8)
		case 116:
			return Data("\u{1B}[5~".utf8)
		case 121:
			return Data("\u{1B}[6~".utf8)
		case 123:
			return Data("\u{1B}[D".utf8)
		case 124:
			return Data("\u{1B}[C".utf8)
		case 125:
			return Data("\u{1B}[B".utf8)
		case 126:
			return Data("\u{1B}[A".utf8)
		case 122:
			return Data("\u{1B}OP".utf8)
		case 120:
			return Data("\u{1B}OQ".utf8)
		case 99:
			return Data("\u{1B}OR".utf8)
		case 118:
			return Data("\u{1B}OS".utf8)
		case 96:
			return Data("\u{1B}[15~".utf8)
		case 97:
			return Data("\u{1B}[17~".utf8)
		case 98:
			return Data("\u{1B}[18~".utf8)
		case 100:
			return Data("\u{1B}[19~".utf8)
		case 101:
			return Data("\u{1B}[20~".utf8)
		case 109:
			return Data("\u{1B}[21~".utf8)
		case 103:
			return Data("\u{1B}[23~".utf8)
		case 111:
			return Data("\u{1B}[24~".utf8)
		default:
			return nil
		}
	}
}
