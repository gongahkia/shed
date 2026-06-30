import AppKit
import CoreText
import CoreVideo
import Metal
import ItsyEditor
import ItsyKeymap
import QuartzCore

struct MetalGlyphInstance {
	var screenOrigin: SIMD2<Float>
	var size: SIMD2<Float>
	var atlasUV: SIMD4<Float>
	var color: SIMD4<Float>
}

public struct TextHighlightSpan: Sendable, Equatable {
	public var range: Range<Int>
	public var color: SIMD4<Float>

	public init(range: Range<Int>, color: SIMD4<Float>) {
		self.range = range
		self.color = color
	}
}

public struct GutterMarker: Sendable, Equatable {
	public var id: String
	public var line: Int
	public var severity: WorkspaceProblemSeverity
	public var message: String
	public var color: SIMD4<Float>?
	public var placement: GutterMarkerPlacement

	public init(
		id: String,
		line: Int,
		severity: WorkspaceProblemSeverity,
		message: String,
		color: SIMD4<Float>? = nil,
		placement: GutterMarkerPlacement = .line
	) {
		self.id = id
		self.line = line
		self.severity = severity
		self.message = message
		self.color = color
		self.placement = placement
	}
}

public enum GutterMarkerPlacement: Sendable, Equatable {
	case line
	case betweenLines
}

public protocol GutterDecorator: AnyObject {
	func gutterMarkers(in lineRange: Range<Int>, for view: MetalTextView) -> [GutterMarker]
	func gutterMarkerClicked(_ marker: GutterMarker, in view: MetalTextView)
	func gutterPopoverViewController(for marker: GutterMarker, in view: MetalTextView) -> NSViewController?
}

public struct TextHoverCandidate: Sendable, Equatable {
	public var offset: Int
	public var positioningRect: NSRect

	public init(offset: Int, positioningRect: NSRect) {
		self.offset = offset
		self.positioningRect = positioningRect
	}
}

struct MetalViewportUniforms {
	var size: SIMD2<Float>
}

struct MetalFragmentUniforms {
	var atlasMode: UInt32
}

private struct LineShapeCacheKey: Hashable {
	var lineIndex: Int
	var lowerBound: Int
	var upperBound: Int
	var renderingMode: GlyphAtlas.RenderingMode
	var scaleKey: Int
	var highlightRevision: Int
}

private struct CachedLineGlyph {
	var originX: CGFloat
	var originYOffset: CGFloat
	var width: CGFloat
	var height: CGFloat
	var atlasUV: SIMD4<Float>
	var color: SIMD4<Float>
}

public final class MetalTextView: NSView {
	private static let accessibilityLocale = Locale(identifier: "en")
	private static let benchStageLock = NSLock()
	private static var recordedBenchStages: Set<String> = []
	private static let maxCachedShapedLines = 512
	private static let defaultFontSize: CGFloat = 14.95
	private static let defaultFontName = "Menlo"
	private static let defaultTextColor = SIMD4<Float>(0.08, 0.09, 0.11, 1.0)
	private static let lineNumberTextColor = SIMD4<Float>(0.68, 0.70, 0.74, 1.0)
	private static let cursorColor = SIMD4<Float>(0.08, 0.09, 0.11, 1.0)

	var clearColor = MTLClearColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0) {
		didSet { needsDisplay = true }
	}

	private let metalDevice: MTLDevice?
	private let commandQueue: MTLCommandQueue?
	private let dirtyLock = NSLock()
	private var dirty = true
	private var displayLink: CVDisplayLink?
	private(set) var renderedFrameCount = 0
	private var cursorRect: CGRect?
	private var cursorBlinkVisible = true
	private var cursorBlinkTimer: Timer?
	private var selectionRects: [CGRect] = []
	private var findMatchRanges: [Range<Int>] = []
	private var findMatchRects: [CGRect] = []
	private var gutterMarkerRects: [(marker: GutterMarker, rect: CGRect)] = []
	private var gutterTrackingArea: NSTrackingArea?
	private var gutterPopover: NSPopover?
	private var hoveredGutterMarkerID: String?
	public var highlightSpans: [TextHighlightSpan] = [] {
		didSet {
			highlightRevision += 1
			lineShapeCache.removeAll(keepingCapacity: true)
			markDirty()
		}
	}
	public weak var gutterDecorator: GutterDecorator? {
		didSet {
			refreshGutterMarkerRects()
			markDirty()
		}
	}
	private var renderPipeline: MTLRenderPipelineState?
	private var samplerState: MTLSamplerState?
	private var solidAtlasTexture: MTLTexture?
	private var glyphAtlas: GlyphAtlas?
	private var glyphAtlasRenderingMode: GlyphAtlas.RenderingMode?
	private var glyphAtlasScaleKey: Int?
	private let renderPass = MTLRenderPassDescriptor()
	private var instanceBuffer: MTLBuffer?
	private var instanceBufferCapacity = 0
	private var textInstanceScratch: [MetalGlyphInstance] = []
	private var solidInstanceScratch: [MetalGlyphInstance] = []
	private var lineShapeCache: [LineShapeCacheKey: [CachedLineGlyph]] = [:]
	private var highlightRevision = 0
	private var markedRangeUTF8: Range<Int>?
	private var textFont = MetalTextView.makeDefaultTextFont()
	private let gutterWidth: CGFloat = 24
	private let baseTextInset = CGPoint(x: 30, y: 6)
	private var textInset: CGPoint {
		CGPoint(x: showLineNumbers ? lineNumberTextInsetX : baseTextInset.x, y: baseTextInset.y)
	}
	private var lineNumberTextInsetX: CGFloat {
		let digits = max(2, String(max(lineCount, 1)).count)
		return gutterWidth + CGFloat(digits) * textFontAdvance + 18
	}
	private var lineNumberRightEdge: CGFloat {
		textInset.x - 12
	}
	public var showLineNumbers = false {
		didSet {
			guard oldValue != showLineNumbers else {
				return
			}
			lineShapeCache.removeAll(keepingCapacity: true)
			syncEditorState()
		}
	}
	public var topContentInset: CGFloat = 0 {
		didSet { syncEditorState() }
	}
	private(set) var topLineIndex = 0
	private(set) var xOffset: CGFloat = 0
	private(set) var displayLinkRefreshRate: Double?
	var lineHeight: CGFloat = 20 {
		didSet { markDirty() }
	}
	var textFontPostScriptName: String {
		CTFontCopyPostScriptName(textFont) as String
	}
	public var textFontPointSize: CGFloat {
		CTFontGetSize(textFont)
	}
	var textFontAdvance: CGFloat {
		var glyph: CGGlyph = 0
		var character = UniChar(77)
		CTFontGetGlyphsForCharacters(textFont, &character, &glyph, 1)
		var advance = CGSize.zero
		CTFontGetAdvancesForGlyphs(textFont, .default, &glyph, &advance, 1)
		return advance.width
	}

	public func configureEditorAppearance(fontName: String, fontSize: CGFloat, showsLineNumbers: Bool) {
		let nextFont = Self.makeTextFont(name: fontName, size: fontSize)
		let currentName = CTFontCopyPostScriptName(textFont) as String
		let nextName = CTFontCopyPostScriptName(nextFont) as String
		let fontChanged = currentName != nextName || CTFontGetSize(textFont) != CTFontGetSize(nextFont)
		if fontChanged {
			textFont = nextFont
			lineHeight = ceil(CTFontGetAscent(nextFont) + CTFontGetDescent(nextFont) + CTFontGetLeading(nextFont) + 2)
			glyphAtlas = nil
			glyphAtlasRenderingMode = nil
			glyphAtlasScaleKey = nil
			lineShapeCache.removeAll(keepingCapacity: true)
		}
		showLineNumbers = showsLineNumbers
		syncEditorState()
	}

	var lineCount: Int = 0 {
		didSet {
			topLineIndex = min(topLineIndex, max(0, lineCount - 1))
			if showLineNumbers {
				lineShapeCache.removeAll(keepingCapacity: true)
			}
			markDirty()
		}
	}
	public var editor = Editor() {
		didSet {
			lineShapeCache.removeAll(keepingCapacity: true)
			syncEditorState()
		}
	}
	public var editorDidChange: ((Editor) -> Void)?
	public var saveRequested: (() -> Void)?
	public var closeRequested: (() -> Void)?
	public var commandRequested: ((String) -> Bool)?
	public var completionRequested: ((String?) -> Bool)?
	public var signatureHelpRequested: ((String?) -> Bool)?
	public var signatureHelpDismissRequested: (() -> Void)?
	public var hoverCandidateChanged: ((TextHoverCandidate?) -> Void)?
	public var exCommandRequested: ((String) -> Bool)?
	public var exCommandLineRequested: ((@escaping (String?) -> Void) -> Bool)?
	public var keymapEngine = KeymapEngine()
	public var completionTriggerCharacters: Set<String> = []
	public var signatureHelpTriggerCharacters: Set<String> = []
	private var pendingCharacterMotion: CharacterMotion?
	private var lastCharacterMotion: (motion: CharacterMotion, value: Character)?
	private var pendingOperator: VimOperator?
	private var pendingOperatorCount = 1
	private var visualAnchor: Int?
	private var visualHead: Int?
	private var visualMode: VisualMode?
	private var awaitingRegister = false
	private var pendingRegister: String?
	private var registers: [String: String] = [:]
	private var jumpBackSelection: SelectionSet?
	private var macroRegisters: [String: [RecordedKey]] = [:]
	private var recordingMacroRegister: String?
	private var currentMacroEvents: [RecordedKey] = []
	private var awaitingMacroRecordRegister = false
	private var awaitingMacroReplayRegister = false
	private var replayingMacro = false
	private var pendingExCommand: String?
	private var insertUndoGroupActive = false
	private var killRing = KillRing()
	private var lastYankRange: Range<Int>?
	private var optionDragAnchor: Int?
	private var mouseSelectionAnchor: Int?
	private var lastAccessibilityValue: String?

	public override init(frame frameRect: NSRect) {
		let device = MTLCreateSystemDefaultDevice()
		metalDevice = device
		commandQueue = device?.makeCommandQueue()
		super.init(frame: frameRect)
		wantsLayer = true
		syncEditorState()
	}

	public required init?(coder: NSCoder) {
		let device = MTLCreateSystemDefaultDevice()
		metalDevice = device
		commandQueue = device?.makeCommandQueue()
		super.init(coder: coder)
		wantsLayer = true
		syncEditorState()
	}

	public override var wantsUpdateLayer: Bool {
		true
	}

	public override var acceptsFirstResponder: Bool {
		true
	}

	public override var isFlipped: Bool {
		true
	}

	public override func isAccessibilityElement() -> Bool {
		true
	}

	public override func accessibilityRole() -> NSAccessibility.Role? {
		.textArea
	}

	public override func accessibilityLabel() -> String? {
		Self.localized("Editor")
	}

	public override func accessibilityValue() -> Any? {
		accessibilityCurrentLineValue()
	}

	public override func acceptsFirstMouse(for event: NSEvent?) -> Bool {
		true
	}

	public override func makeBackingLayer() -> CALayer {
		let layer = CAMetalLayer()
		layer.device = metalDevice
		layer.pixelFormat = .bgra8Unorm
		layer.framebufferOnly = true
		layer.maximumDrawableCount = 3
		if #available(macOS 10.11, *) {
			layer.wantsExtendedDynamicRangeContent = false
		}
		layer.contentsScale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1
		return layer
	}

	public override func viewDidMoveToWindow() {
		super.viewDidMoveToWindow()
		updateDrawableSize()
		if window == nil {
			stopDisplayLink()
			stopCursorBlinkTimer()
		} else {
			startDisplayLink()
			startCursorBlinkTimer()
			markDirty()
		}
	}

	public override func setFrameSize(_ newSize: NSSize) {
		super.setFrameSize(newSize)
		updateDrawableSize()
		refreshGutterMarkerRects()
		markDirty()
	}

	public override func updateTrackingAreas() {
		if let gutterTrackingArea {
			removeTrackingArea(gutterTrackingArea)
		}
		let area = NSTrackingArea(
			rect: bounds,
			options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved, .mouseEnteredAndExited],
			owner: self,
			userInfo: nil
		)
		addTrackingArea(area)
		gutterTrackingArea = area
		super.updateTrackingAreas()
	}

	public override func viewDidChangeBackingProperties() {
		super.viewDidChangeBackingProperties()
		updateDrawableSize()
		resetGlyphCacheForCurrentScale()
		restartDisplayLink()
		markDirty()
	}

	public override func updateLayer() {
		_ = consumeDirtyForDisplayLink()
		renderClearColor()
	}

	deinit {
		closeGutterPopover()
		stopDisplayLink()
		stopCursorBlinkTimer()
	}

	func markDirty() {
		dirtyLock.lock()
		dirty = true
		dirtyLock.unlock()
	}

	func consumeDirtyForDisplayLink() -> Bool {
		dirtyLock.lock()
		defer { dirtyLock.unlock() }
		guard dirty else {
			return false
		}
		dirty = false
		return true
	}

	func setCursor(x: CGFloat, y: CGFloat, height: CGFloat) {
		cursorRect = CGRect(x: x, y: y, width: 2, height: height)
		cursorBlinkVisible = true
		markDirty()
	}

	func clearCursor() {
		cursorRect = nil
		markDirty()
	}

	func setSelectionRects(_ rects: [CGRect]) {
		selectionRects = rects
		markDirty()
	}

	public func setFindMatchRanges(_ ranges: [Range<Int>]) {
		findMatchRanges = ranges
		refreshFindMatchRects()
	}

	var visibleLineRange: Range<Int> {
		guard lineCount > 0 else {
			return 0 ..< 0
		}
		let visibleHeight = max(0, bounds.height - topContentInset)
		let visibleCount = max(1, Int(ceil(visibleHeight / max(lineHeight, 1))) + 1)
		let end = min(lineCount, topLineIndex + visibleCount)
		return topLineIndex ..< end
	}

	func scroll(deltaX: CGFloat, deltaY: CGFloat) {
		if deltaY != 0, lineCount > 0 {
			let lineDelta = Int((deltaY / max(lineHeight, 1)).rounded(.toNearestOrAwayFromZero))
			topLineIndex = min(max(topLineIndex - lineDelta, 0), max(0, lineCount - 1))
		}
		if deltaX != 0 {
			xOffset = max(0, xOffset - deltaX)
		}
		refreshFindMatchRects()
		refreshGutterMarkerRects()
		markDirty()
	}

	public func performMotion(_ motion: Motion) {
		editor.moveCursor(motion)
		syncEditorState()
	}

	public func selectUTF8Range(_ range: Range<Int>) {
		selectUTF8Ranges([range])
	}

	public func selectUTF8Ranges(_ ranges: [Range<Int>]) {
		let selections = ranges.map { Selection(anchor: $0.lowerBound, head: $0.upperBound) }
		guard let primary = selections.first else {
			return
		}
		editor.setSelection(SelectionSet(primary: primary, secondaries: Array(selections.dropFirst())))
		syncEditorState()
	}

	public func replaceUTF8Range(_ range: Range<Int>, with text: String, selectUTF8Ranges ranges: [Range<Int>] = []) {
		let lower = min(max(range.lowerBound, 0), editor.rope.length)
		let upper = min(max(range.upperBound, lower), editor.rope.length)
		replace(range: lower ..< upper, with: text)
		if !ranges.isEmpty {
			selectUTF8Ranges(ranges)
		} else {
			syncEditorState()
		}
		editorDidChange?(editor)
	}

	func toggleAdditionalCursor(at offset: Int) {
		let clamped = min(max(offset, 0), editor.rope.length)
		var selections = [editor.selections.primary] + editor.selections.secondaries
		if let index = selections.firstIndex(where: { $0.isCaret && $0.head == clamped }) {
			guard selections.count > 1 else {
				return
			}
			selections.remove(at: index)
		} else {
			selections.append(Selection(anchor: clamped, head: clamped))
		}
		editor.setSelection(SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst())))
		syncEditorState()
	}

	func updateColumnCursors(anchor: Int, head: Int) {
		editor.setSelection(columnCursorSelection(anchor: anchor, head: head))
		syncEditorState()
	}

	func columnCursorSelection(anchor: Int, head: Int) -> SelectionSet {
		let clampedAnchor = min(max(anchor, 0), editor.rope.length)
		let clampedHead = min(max(head, 0), editor.rope.length)
		let anchorLine = editor.rope.line(forOffset: clampedAnchor)
		let headLine = editor.rope.line(forOffset: clampedHead)
		let lowerLine = min(anchorLine, headLine)
		let upperLine = max(anchorLine, headLine)
		let column = clampedAnchor - editor.rope.offset(forLine: anchorLine)
		let selections = (lowerLine ... upperLine).map { line -> Selection in
			let lineRange = editor.rope.lineRange(line)
			let offset = min(max(lineRange.lowerBound + column, lineRange.lowerBound), lineRange.upperBound)
			return Selection(anchor: offset, head: offset)
		}
		return SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst()))
	}

	public override func scrollWheel(with event: NSEvent) {
		scroll(deltaX: event.scrollingDeltaX, deltaY: event.scrollingDeltaY)
	}

	public override func mouseDown(with event: NSEvent) {
		window?.makeFirstResponder(self)
		if let marker = gutterMarker(forMouseEvent: event) {
			mouseSelectionAnchor = nil
			gutterDecorator?.gutterMarkerClicked(marker, in: self)
			return
		}
		if event.modifierFlags.contains(.command) {
			mouseSelectionAnchor = nil
			toggleAdditionalCursor(at: utf8Offset(forMouseEvent: event))
			return
		}
		if event.modifierFlags.contains(.option) {
			mouseSelectionAnchor = nil
			optionDragAnchor = utf8Offset(forMouseEvent: event)
			updateColumnCursors(anchor: optionDragAnchor ?? 0, head: optionDragAnchor ?? 0)
			return
		}
		let offset = utf8Offset(forMouseEvent: event)
		mouseSelectionAnchor = offset
		editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))
		syncEditorState()
	}

	public override func mouseDragged(with event: NSEvent) {
		if let optionDragAnchor {
			updateColumnCursors(anchor: optionDragAnchor, head: utf8Offset(forMouseEvent: event))
			return
		}
		if let mouseSelectionAnchor {
			editor.setSelection(SelectionSet(primary: Selection(anchor: mouseSelectionAnchor, head: utf8Offset(forMouseEvent: event))))
			syncEditorState()
			return
		}
		super.mouseDragged(with: event)
	}

	public override func mouseUp(with event: NSEvent) {
		optionDragAnchor = nil
		mouseSelectionAnchor = nil
		super.mouseUp(with: event)
	}

	public override func mouseMoved(with event: NSEvent) {
		if let marker = gutterMarker(forMouseEvent: event), let rect = gutterMarkerRect(for: marker.id) {
			hoverCandidateChanged?(nil)
			showGutterPopover(for: marker, relativeTo: rect)
			return
		}
		closeGutterPopover()
		guard event.modifierFlags.contains(.command) else {
			hoverCandidateChanged?(nil)
			return
		}
		let offset = utf8Offset(forMouseEvent: event)
		hoverCandidateChanged?(TextHoverCandidate(offset: offset, positioningRect: rectForUTF8Offset(offset)))
	}

	public override func mouseExited(with event: NSEvent) {
		hoverCandidateChanged?(nil)
		closeGutterPopover()
	}

	public func positioningRectForUTF8Offset(_ offset: Int) -> NSRect {
		rectForUTF8Offset(offset)
	}

	public func hoverCandidate(atLocalPoint point: NSPoint) -> TextHoverCandidate {
		let offset = utf8Offset(forLocalPoint: point)
		return TextHoverCandidate(offset: offset, positioningRect: rectForUTF8Offset(offset))
	}

	public override func keyDown(with event: NSEvent) {
		if event.keyCode == 53 {
			signatureHelpDismissRequested?()
		}
		if handlePendingMacroRegister(event) {
			return
		}
		let recordsMacro = shouldRecordMacroEvent(event)
		if handleExCommandInput(event) {
			recordMacroEvent(event, when: recordsMacro)
			return
		}
		if handlePendingRegister(event) {
			recordMacroEvent(event, when: recordsMacro)
			return
		}
		if handlePendingCharacterMotion(event) {
			recordMacroEvent(event, when: recordsMacro)
			return
		}
		if dispatchKeymap(event) == .handled {
			recordMacroEvent(event, when: recordsMacro)
			return
		}
		if !event.modifierFlags.intersection([.command, .control]).isEmpty {
			super.keyDown(with: event)
			return
		}
		interpretKeyEvents([event])
		recordMacroEvent(event, when: recordsMacro)
	}

	public override func performKeyEquivalent(with event: NSEvent) -> Bool {
		if dispatchKeymap(event) == .handled {
			return true
		}
		return super.performKeyEquivalent(with: event)
	}

	@discardableResult
	func handleKey(
		characters: String?,
		charactersIgnoringModifiers: String?,
		keyCode: UInt16,
		modifierFlags: NSEvent.ModifierFlags = []
	) -> Bool {
		if keyCode == 53 {
			signatureHelpDismissRequested?()
		}
		let event = NSEvent.keyEvent(
			with: .keyDown,
			location: .zero,
			modifierFlags: modifierFlags,
			timestamp: 0,
			windowNumber: 0,
			context: nil,
			characters: characters ?? "",
			charactersIgnoringModifiers: charactersIgnoringModifiers ?? characters ?? "",
			isARepeat: false,
			keyCode: keyCode
		)
		if let event, handlePendingMacroRegister(event) {
			return true
		}
		let recordsMacro = event.map { shouldRecordMacroEvent($0) } ?? false
		if let event, handleExCommandInput(event) {
			recordMacroEvent(event, when: recordsMacro)
			return true
		}
		if let event, handlePendingRegister(event) {
			recordMacroEvent(event, when: recordsMacro)
			return true
		}
		if let event, handlePendingCharacterMotion(event) {
			recordMacroEvent(event, when: recordsMacro)
			return true
		}
		if let event, dispatchKeymap(event) == .handled {
			recordMacroEvent(event, when: recordsMacro)
			return true
		}
		let handled = handlePassthroughKey(characters: characters, charactersIgnoringModifiers: charactersIgnoringModifiers, keyCode: keyCode, modifierFlags: modifierFlags)
		if let event, handled {
			recordMacroEvent(event, when: recordsMacro)
		}
		return handled
	}

	private enum KeyDispatchResult {
		case handled
		case passthrough
	}

	private enum CharacterMotion {
		case findForward
		case findBackward
		case tillForward
		case tillBackward

		var reversed: CharacterMotion {
			switch self {
			case .findForward:
				return .findBackward
			case .findBackward:
				return .findForward
			case .tillForward:
				return .tillBackward
			case .tillBackward:
				return .tillForward
			}
		}
	}

	private enum VimOperator {
		case delete
		case change
		case yank
	}

	private enum VisualMode {
		case character
		case line
		case block
	}

	private enum RegisterOperation {
		case yank
		case delete
	}

	private struct RecordedKey {
		var characters: String
		var charactersIgnoringModifiers: String
		var keyCode: UInt16
		var modifierFlags: NSEvent.ModifierFlags

		init(_ event: NSEvent) {
			characters = event.characters ?? ""
			charactersIgnoringModifiers = event.charactersIgnoringModifiers ?? characters
			keyCode = event.keyCode
			modifierFlags = event.modifierFlags.intersection([.command, .shift, .option, .control])
		}
	}

	private enum TextObject {
		case innerWord
		case aroundWord
		case innerPair(Character, Character)
		case aroundPair(Character, Character)
		case innerParagraph
		case aroundParagraph
	}

	private func dispatchKeymap(_ event: NSEvent) -> KeyDispatchResult {
		switch keymapEngine.handle(event) {
		case .command(let commandID):
			return performKeymapCommand(commandID) ? .handled : .passthrough
		case .partial, .consumed:
			return .handled
		case .passthrough:
			return .passthrough
		}
	}

	private func performKeymapCommand(_ commandID: String) -> Bool {
		if commandID != "emacs.yank", commandID != "emacs.yankPop" {
			lastYankRange = nil
		}
		if let motion = motion(for: commandID), visualMode != nil {
			extendVisualSelection(motion: motion)
			return true
		}
		if let textObject = textObject(for: commandID), pendingOperator != nil {
			applyPendingOperator(textObject: textObject)
			return true
		}
		if let motion = motion(for: commandID), pendingOperator != nil {
			applyPendingOperator(motion: motion)
			return true
		}
		switch commandID {
		case "editor.moveLeft":
			repeatMotion(.charBackward)
		case "editor.moveRight":
			repeatMotion(.charForward)
		case "editor.moveDown":
			repeatMotion(.lineDown)
		case "editor.moveUp":
			repeatMotion(.lineUp)
		case "editor.moveWordForward":
			repeatMotion(.wordForward)
		case "editor.moveWordBackward":
			repeatMotion(.wordBackward)
		case "editor.moveWordEnd":
			repeatMotion(.wordEnd)
		case "editor.moveBigWordForward":
			repeatMotion(.bigWordForward)
		case "editor.moveBigWordBackward":
			repeatMotion(.bigWordBackward)
		case "editor.moveBigWordEnd":
			repeatMotion(.bigWordEnd)
		case "editor.moveLineStart":
			repeatMotion(.lineStart)
		case "editor.moveLineEnd":
			repeatMotion(.lineEnd)
		case "editor.moveBufferStart":
			repeatMotion(.bufferStart)
		case "editor.moveBufferEnd":
			repeatMotion(.bufferEnd)
		case "editor.moveParagraphBackward":
			repeatMotion(.paragraphBackward)
		case "editor.moveParagraphForward":
			repeatMotion(.paragraphForward)
		case "editor.findCharForward":
			pendingCharacterMotion = .findForward
			return true
		case "editor.findCharBackward":
			pendingCharacterMotion = .findBackward
			return true
		case "editor.tillCharForward":
			pendingCharacterMotion = .tillForward
			return true
		case "editor.tillCharBackward":
			pendingCharacterMotion = .tillBackward
			return true
		case "editor.repeatCharFind":
			repeatLastCharacterMotion(reversed: false)
			return true
		case "editor.repeatCharFindReverse":
			repeatLastCharacterMotion(reversed: true)
			return true
		case "edit.undo":
			endInsertUndoGroup()
			editor.undo()
			syncEditorState()
			editorDidChange?(editor)
			return true
		case "edit.redo":
			endInsertUndoGroup()
			editor.redo()
			syncEditorState()
			editorDidChange?(editor)
			return true
		case "emacs.killRegion":
			killSelectedText(delete: true)
			return true
		case "emacs.copyRegion":
			killSelectedText(delete: false)
			return true
		case "emacs.yank":
			yankFromKillRing()
			return true
		case "emacs.yankPop":
			yankPopFromKillRing()
			return true
		case "editor.addNextSelection":
			addNextSelectionMatch()
			return true
		case "vim.operator.delete":
			return beginOperator(.delete)
		case "vim.operator.change":
			return beginOperator(.change)
		case "vim.operator.yank":
			return beginOperator(.yank)
		case "vim.registerPrefix":
			awaitingRegister = true
			return true
		case "vim.jumpBack":
			jumpBack()
		case "vim.macro.recordPrefix":
			handleMacroRecordPrefix()
			return true
		case "vim.macro.replayPrefix":
			awaitingMacroReplayRegister = true
			return true
		case "vim.pasteAfter":
			pasteRegister(after: true)
			return true
		case "vim.pasteBefore":
			pasteRegister(after: false)
			return true
		case "vim.ex.start":
			keymapEngine.setMode(.command)
			if exCommandLineRequested?({ [weak self] command in
				self?.finishExCommand(command)
			}) == true {
				return true
			}
			pendingExCommand = ""
			return true
		case "vim.visual.char":
			beginVisualMode(.character)
			return true
		case "vim.visual.line":
			beginVisualMode(.line)
			return true
		case "vim.visual.block":
			beginVisualMode(.block)
			return true
		case "vim.operator.line.delete":
			applyLineOperator(.delete)
			return true
		case "vim.operator.line.change":
			applyLineOperator(.change)
			return true
		case "vim.operator.line.yank":
			applyLineOperator(.yank)
			return true
		case "edit.findNext", "edit.findPrevious", "vim.searchForward", "vim.searchBackward":
			return performHostCommand(commandID, recordsJump: keymapEngine.mode == .normal)
		case "file.save":
			saveRequested?()
			return true
		case "file.close":
			closeRequested?()
			return true
		case "mode.normal":
			endInsertUndoGroup()
			leaveVisualMode(collapse: true)
			keymapEngine.setMode(.normal)
			return true
		case "mode.insert":
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			return true
		case "mode.emacs":
			keymapEngine.setMode(.emacs)
			return true
		default:
			return performHostCommand(commandID)
		}
		syncEditorState()
		return true
	}

	private func performHostCommand(_ commandID: String, recordsJump: Bool = false) -> Bool {
		let before = editor.selections
		guard commandRequested?(commandID) == true else {
			return false
		}
		if recordsJump {
			jumpBackSelection = before
		}
		return true
	}

	private func beginInsertUndoGroup() {
		guard !insertUndoGroupActive else {
			return
		}
		editor.beginUndoGroup()
		insertUndoGroupActive = true
	}

	private func endInsertUndoGroup() {
		guard insertUndoGroupActive else {
			return
		}
		editor.endUndoGroup()
		insertUndoGroupActive = false
	}

	private func killSelectedText(delete: Bool) {
		let ranges = selectedNonEmptyRanges()
		guard !ranges.isEmpty else {
			return
		}
		let text = ranges.map { editor.rope.slice($0) }.joined()
		killRing.push(text)
		NSPasteboard.general.clearContents()
		NSPasteboard.general.setString(text, forType: .string)
		if delete {
			editor.deleteForward()
			syncEditorState()
			editorDidChange?(editor)
		}
	}

	private func yankFromKillRing() {
		let text = killRing.current ?? NSPasteboard.general.string(forType: .string)
		guard let text, !text.isEmpty else {
			return
		}
		let range = editor.selections.primary.range
		replace(range: range, with: text)
		lastYankRange = range.lowerBound ..< range.lowerBound + text.utf8.count
		syncEditorState()
		editorDidChange?(editor)
	}

	private func yankPopFromKillRing() {
		guard let lastYankRange, let text = killRing.rotate() else {
			return
		}
		replace(range: lastYankRange, with: text)
		self.lastYankRange = lastYankRange.lowerBound ..< lastYankRange.lowerBound + text.utf8.count
		syncEditorState()
		editorDidChange?(editor)
	}

	private func selectedNonEmptyRanges() -> [Range<Int>] {
		([editor.selections.primary] + editor.selections.secondaries)
			.map(\.range)
			.filter { !$0.isEmpty }
			.sorted { $0.lowerBound < $1.lowerBound }
	}

	private func addNextSelectionMatch() {
		let selectedRanges = selectedNonEmptyRanges()
		let queryRange: Range<Int>
		if let selectedRange = selectedRanges.first {
			queryRange = selectedRange
		} else if let wordRange = wordRange(at: editor.selections.primary.head) {
			editor.setSelection(SelectionSet(primary: Selection(anchor: wordRange.lowerBound, head: wordRange.upperBound)))
			syncEditorState()
			return
		} else {
			return
		}
		let query = editor.rope.slice(queryRange)
		guard !query.isEmpty, let nextRange = nextMatchRange(for: query, after: selectedRanges.map(\.upperBound).max() ?? queryRange.upperBound, excluding: selectedRanges) else {
			return
		}
		let selections = [editor.selections.primary] + editor.selections.secondaries + [Selection(anchor: nextRange.lowerBound, head: nextRange.upperBound)]
		editor.setSelection(SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst())))
		syncEditorState()
	}

	private func wordRange(at offset: Int) -> Range<Int>? {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return nil
		}
		let clamped = min(max(offset, 0), editor.rope.length)
		let index = offsets.lastIndex { $0.offset <= clamped } ?? 0
		guard isTextObjectWordCharacter(offsets[index].character) else {
			return nil
		}
		var lowerIndex = index
		while lowerIndex > 0, isTextObjectWordCharacter(offsets[lowerIndex - 1].character) {
			lowerIndex -= 1
		}
		var upperIndex = index
		while upperIndex + 1 < offsets.count, isTextObjectWordCharacter(offsets[upperIndex + 1].character) {
			upperIndex += 1
		}
		return offsets[lowerIndex].offset ..< offsets[upperIndex].offset + String(offsets[upperIndex].character).utf8.count
	}

	private func nextMatchRange(for query: String, after offset: Int, excluding excluded: [Range<Int>]) -> Range<Int>? {
		let text = editor.text
		guard let startIndex = String.Index(text.utf8.index(text.utf8.startIndex, offsetBy: min(offset, text.utf8.count)), within: text) else {
			return nil
		}
		let ranges = [
			startIndex ..< text.endIndex,
			text.startIndex ..< startIndex,
		]
		for searchRange in ranges {
			var cursor = searchRange.lowerBound
			while cursor < searchRange.upperBound, let match = text.range(of: query, range: cursor ..< searchRange.upperBound) {
				let utf8Match = utf8Range(match, in: text)
				if !excluded.contains(where: { $0 == utf8Match }) {
					return utf8Match
				}
				cursor = match.upperBound
			}
		}
		return nil
	}

	private func utf8Range(_ range: Range<String.Index>, in text: String) -> Range<Int> {
		let lower = text.utf8.distance(from: text.utf8.startIndex, to: range.lowerBound.samePosition(in: text.utf8) ?? text.utf8.startIndex)
		let upper = text.utf8.distance(from: text.utf8.startIndex, to: range.upperBound.samePosition(in: text.utf8) ?? text.utf8.endIndex)
		return lower ..< upper
	}

	private func motion(for commandID: String) -> Motion? {
		switch commandID {
		case "editor.moveLeft":
			return .charBackward
		case "editor.moveRight":
			return .charForward
		case "editor.moveDown":
			return .lineDown
		case "editor.moveUp":
			return .lineUp
		case "editor.moveWordForward":
			return .wordForward
		case "editor.moveWordBackward":
			return .wordBackward
		case "editor.moveWordEnd":
			return .wordEnd
		case "editor.moveBigWordForward":
			return .bigWordForward
		case "editor.moveBigWordBackward":
			return .bigWordBackward
		case "editor.moveBigWordEnd":
			return .bigWordEnd
		case "editor.moveLineStart":
			return .lineStart
		case "editor.moveLineEnd":
			return .lineEnd
		case "editor.moveBufferStart":
			return .bufferStart
		case "editor.moveBufferEnd":
			return .bufferEnd
		case "editor.moveParagraphForward":
			return .paragraphForward
		case "editor.moveParagraphBackward":
			return .paragraphBackward
		default:
			return nil
		}
	}

	private func textObject(for commandID: String) -> TextObject? {
		switch commandID {
		case "vim.textObject.innerWord":
			return .innerWord
		case "vim.textObject.aroundWord":
			return .aroundWord
		case "vim.textObject.innerDoubleQuote":
			return .innerPair("\"", "\"")
		case "vim.textObject.aroundDoubleQuote":
			return .aroundPair("\"", "\"")
		case "vim.textObject.innerSingleQuote":
			return .innerPair("'", "'")
		case "vim.textObject.aroundSingleQuote":
			return .aroundPair("'", "'")
		case "vim.textObject.innerParen":
			return .innerPair("(", ")")
		case "vim.textObject.aroundParen":
			return .aroundPair("(", ")")
		case "vim.textObject.innerBracket":
			return .innerPair("[", "]")
		case "vim.textObject.aroundBracket":
			return .aroundPair("[", "]")
		case "vim.textObject.innerBrace":
			return .innerPair("{", "}")
		case "vim.textObject.aroundBrace":
			return .aroundPair("{", "}")
		case "vim.textObject.innerParagraph":
			return .innerParagraph
		case "vim.textObject.aroundParagraph":
			return .aroundParagraph
		default:
			return nil
		}
	}

	private func beginOperator(_ op: VimOperator) -> Bool {
		if !editor.selections.primary.isCaret {
			applySelectionOperator(op)
			leaveVisualMode(collapse: op == .yank)
			keymapEngine.setMode(op == .change ? .insert : .normal)
			return true
		}
		pendingOperator = op
		pendingOperatorCount = keymapRepeatCount
		keymapEngine.pushMode(.operatorPending)
		return true
	}

	private func applyPendingOperator(motion: Motion) {
		guard let pendingOperator else {
			return
		}
		let start = editor.selections.primary.head
		var projected = editor
		let count = max(1, pendingOperatorCount * keymapRepeatCount)
		for _ in 0 ..< count {
			projected.moveCursor(motion)
		}
		let end = projected.selections.primary.head
		clearPendingOperator()
		applyOperator(pendingOperator, range: min(start, end) ..< max(start, end))
	}

	private func applyPendingOperator(textObject: TextObject) {
		guard let pendingOperator, let range = textObjectRange(textObject) else {
			return
		}
		clearPendingOperator()
		applyOperator(pendingOperator, range: range)
	}

	private func applyLineOperator(_ op: VimOperator) {
		clearPendingOperator()
		applyOperator(op, range: currentLineIncludingNewline())
	}

	private func clearPendingOperator() {
		pendingOperator = nil
		pendingOperatorCount = 1
		if keymapEngine.mode == .operatorPending {
			_ = keymapEngine.popMode()
		}
	}

	private func applyOperator(_ op: VimOperator, range: Range<Int>) {
		guard !range.isEmpty else {
			return
		}
		let text = editor.rope.slice(range)
		switch op {
		case .delete:
			writeRegister(text, operation: .delete)
			replace(range: range, with: "")
		case .change:
			writeRegister(text, operation: .delete)
			replace(range: range, with: "")
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
		case .yank:
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.lowerBound)))
			syncEditorState()
		}
	}

	private func applySelectionOperator(_ op: VimOperator) {
		let selections = ([editor.selections.primary] + editor.selections.secondaries)
			.map(\.range)
			.filter { !$0.isEmpty }
			.sorted { $0.lowerBound < $1.lowerBound }
		guard !selections.isEmpty else {
			return
		}
		switch op {
		case .delete:
			writeRegister(selections.map { editor.rope.slice($0) }.joined(), operation: .delete)
			editor.deleteForward()
			syncEditorState()
			editorDidChange?(editor)
		case .change:
			writeRegister(selections.map { editor.rope.slice($0) }.joined(), operation: .delete)
			editor.deleteForward()
			beginInsertUndoGroup()
			keymapEngine.setMode(.insert)
			syncEditorState()
			editorDidChange?(editor)
		case .yank:
			let text = selections.map { editor.rope.slice($0) }.joined()
			writeRegister(text, operation: .yank)
			editor.setSelection(SelectionSet(primary: Selection(anchor: selections[0].lowerBound, head: selections[0].lowerBound)))
			syncEditorState()
		}
	}

	private func currentLineIncludingNewline() -> Range<Int> {
		let line = editor.rope.line(forOffset: editor.selections.primary.head)
		let start = editor.rope.offset(forLine: line)
		let end = line + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: line + 1) : editor.rope.length
		return start ..< end
	}

	private func handleExCommandInput(_ event: NSEvent) -> Bool {
		guard pendingExCommand != nil else {
			return false
		}
		switch event.keyCode {
		case 36:
			let command = pendingExCommand ?? ""
			pendingExCommand = nil
			finishExCommand(command)
		case 51:
			if pendingExCommand?.isEmpty == false {
				pendingExCommand?.removeLast()
			}
		case 53:
			pendingExCommand = nil
			keymapEngine.setMode(.normal)
		default:
			guard event.modifierFlags.intersection([.command, .control]).isEmpty, let characters = event.characters, !characters.isEmpty else {
				return true
			}
			pendingExCommand? += characters
		}
		return true
	}

	private func finishExCommand(_ command: String?) {
		keymapEngine.setMode(.normal)
		guard let command else {
			return
		}
		executeExCommand(command)
	}

	private func executeExCommand(_ command: String) {
		let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
		let normalized = trimmed.hasPrefix(":") ? String(trimmed.dropFirst()) : trimmed
		if executeSubstitution(normalized) {
			return
		}
		_ = exCommandRequested?(normalized)
	}

	private func executeSubstitution(_ command: String) -> Bool {
		guard command.hasPrefix("%s/"), command.hasSuffix("/g") else {
			return false
		}
		let body = command.dropFirst(3).dropLast(2)
		let parts = body.split(separator: "/", omittingEmptySubsequences: false)
		guard parts.count == 2 else {
			return false
		}
		let needle = String(parts[0])
		let replacement = String(parts[1])
		guard !needle.isEmpty else {
			return true
		}
		editor = Editor(text: editor.text.replacingOccurrences(of: needle, with: replacement))
		syncEditorState()
		editorDidChange?(editor)
		return true
	}

	private func handlePendingRegister(_ event: NSEvent) -> Bool {
		guard awaitingRegister, let key = Key(event: event), let register = registerName(for: key) else {
			return false
		}
		pendingRegister = register
		awaitingRegister = false
		return true
	}

	private func handlePendingMacroRegister(_ event: NSEvent) -> Bool {
		if awaitingMacroRecordRegister {
			awaitingMacroRecordRegister = false
			if let register = macroRegisterName(for: event) {
				startMacroRecording(register)
			}
			return true
		}
		if awaitingMacroReplayRegister {
			let recordsRegister = recordingMacroRegister != nil && !replayingMacro
			awaitingMacroReplayRegister = false
			recordMacroEvent(event, when: recordsRegister)
			if let register = macroRegisterName(for: event) {
				replayMacro(register)
			}
			return true
		}
		return false
	}

	private func registerName(for key: Key) -> String? {
		if key.modifiers == .shift, key.value == "'" {
			return "\""
		}
		if key.modifiers == .shift, key.value == "=" {
			return "+"
		}
		guard key.modifiers.isEmpty, key.value.count == 1 else {
			return nil
		}
		let value = key.value
		if value == "\"" || value == "+" || value == "0" || ("1" ... "9").contains(value) {
			return value
		}
		if value >= "a", value <= "z" {
			return value
		}
		return nil
	}

	private func macroRegisterName(for event: NSEvent) -> String? {
		guard let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1 else {
			return nil
		}
		if ("a" ... "z").contains(key.value) || ("0" ... "9").contains(key.value) {
			return key.value
		}
		return nil
	}

	private func handleMacroRecordPrefix() {
		if recordingMacroRegister != nil {
			stopMacroRecording()
		} else {
			awaitingMacroRecordRegister = true
		}
	}

	private func startMacroRecording(_ register: String) {
		recordingMacroRegister = register
		currentMacroEvents = []
	}

	private func stopMacroRecording() {
		guard let register = recordingMacroRegister else {
			return
		}
		macroRegisters[register] = currentMacroEvents
		recordingMacroRegister = nil
		currentMacroEvents = []
	}

	private func replayMacro(_ register: String) {
		guard !replayingMacro, let events = macroRegisters[register], !events.isEmpty else {
			return
		}
		replayingMacro = true
		defer { replayingMacro = false }
		for event in events {
			_ = handleKey(
				characters: event.characters,
				charactersIgnoringModifiers: event.charactersIgnoringModifiers,
				keyCode: event.keyCode,
				modifierFlags: event.modifierFlags
			)
		}
	}

	private func shouldRecordMacroEvent(_ event: NSEvent) -> Bool {
		guard recordingMacroRegister != nil, !replayingMacro else {
			return false
		}
		guard let key = Key(event: event) else {
			return false
		}
		if keymapEngine.mode == .normal, key.modifiers.isEmpty, key.value == "q" {
			return false
		}
		return true
	}

	private func recordMacroEvent(_ event: NSEvent, when shouldRecord: Bool) {
		if shouldRecord {
			currentMacroEvents.append(RecordedKey(event))
		}
	}

	private func writeRegister(_ text: String, operation: RegisterOperation) {
		let target = pendingRegister ?? "\""
		registers["\""] = text
		if operation == .yank {
			registers["0"] = text
		} else {
			for index in stride(from: 9, through: 2, by: -1) {
				registers[String(index)] = registers[String(index - 1)]
			}
			registers["1"] = text
		}
		if target == "+" {
			NSPasteboard.general.clearContents()
			NSPasteboard.general.setString(text, forType: .string)
		} else {
			registers[target] = text
		}
		pendingRegister = nil
	}

	private func readRegister() -> String? {
		let target = pendingRegister ?? "\""
		defer { pendingRegister = nil }
		if target == "+" {
			return NSPasteboard.general.string(forType: .string)
		}
		return registers[target] ?? registers["\""]
	}

	private func pasteRegister(after: Bool) {
		guard let text = readRegister(), !text.isEmpty else {
			return
		}
		let head = editor.selections.primary.head
		let offset = after ? offsetAfterCharacter(at: head) : head
		editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))
		editor.insert(text)
		syncEditorState()
		editorDidChange?(editor)
	}

	private func offsetAfterCharacter(at offset: Int) -> Int {
		let offsets = characterOffsets()
		guard let index = offsets.firstIndex(where: { $0.offset >= offset }) else {
			return editor.rope.length
		}
		return min(editor.rope.length, offsets[index].offset + String(offsets[index].character).utf8.count)
	}

	private func beginVisualMode(_ mode: VisualMode) {
		visualAnchor = editor.selections.primary.head
		visualHead = editor.selections.primary.head
		visualMode = mode
		keymapEngine.setMode(.visual)
		updateVisualSelection(head: editor.selections.primary.head)
	}

	private func extendVisualSelection(motion: Motion) {
		guard visualMode != nil else {
			return
		}
		var projected = editor
		let head = visualHead ?? editor.selections.primary.head
		projected.setSelection(SelectionSet(primary: Selection(anchor: head, head: head)))
		for _ in 0 ..< keymapRepeatCount {
			projected.moveCursor(motion)
		}
		updateVisualSelection(head: projected.selections.primary.head)
	}

	private func updateVisualSelection(head: Int) {
		guard let visualAnchor, let visualMode else {
			return
		}
		visualHead = head
		switch visualMode {
		case .character:
			editor.setSelection(SelectionSet(primary: Selection(anchor: visualAnchor, head: head)))
		case .line:
			editor.setSelection(SelectionSet(primary: Selection(anchor: lineStart(for: visualAnchor), head: lineEndIncludingNewline(for: head))))
		case .block:
			editor.setSelection(blockSelection(anchor: visualAnchor, head: head))
		}
		syncEditorState()
	}

	private func leaveVisualMode(collapse: Bool) {
		visualAnchor = nil
		visualHead = nil
		visualMode = nil
		if collapse {
			let head = editor.selections.primary.range.lowerBound
			editor.setSelection(SelectionSet(primary: Selection(anchor: head, head: head)))
			syncEditorState()
		}
	}

	private func lineStart(for offset: Int) -> Int {
		editor.rope.offset(forLine: editor.rope.line(forOffset: offset))
	}

	private func lineEndIncludingNewline(for offset: Int) -> Int {
		let line = editor.rope.line(forOffset: offset)
		return line + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: line + 1) : editor.rope.length
	}

	private func blockSelection(anchor: Int, head: Int) -> SelectionSet {
		let anchorLine = editor.rope.line(forOffset: anchor)
		let headLine = editor.rope.line(forOffset: head)
		let lowerLine = min(anchorLine, headLine)
		let upperLine = max(anchorLine, headLine)
		let anchorColumn = anchor - editor.rope.offset(forLine: anchorLine)
		let headColumn = head - editor.rope.offset(forLine: headLine)
		let lowerColumn = min(anchorColumn, headColumn)
		let upperColumn = max(anchorColumn, headColumn) + 1
		let selections = (lowerLine ... upperLine).map { line -> Selection in
			let lineStart = editor.rope.offset(forLine: line)
			let lineEnd = editor.rope.lineRange(line).upperBound
			let lower = min(lineStart + lowerColumn, lineEnd)
			let upper = min(lineStart + upperColumn, lineEnd)
			return Selection(anchor: lower, head: upper)
		}
		return SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst()))
	}

	private func textObjectRange(_ textObject: TextObject) -> Range<Int>? {
		switch textObject {
		case .innerWord:
			return wordTextObjectRange(includeWhitespace: false)
		case .aroundWord:
			return wordTextObjectRange(includeWhitespace: true)
		case .innerPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: false)
		case .aroundPair(let open, let close):
			return pairTextObjectRange(open: open, close: close, includeDelimiters: true)
		case .innerParagraph:
			return paragraphTextObjectRange(includeBlankLine: false)
		case .aroundParagraph:
			return paragraphTextObjectRange(includeBlankLine: true)
		}
	}

	private func wordTextObjectRange(includeWhitespace: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return nil
		}
		let head = editor.selections.primary.head
		let index = offsets.lastIndex { $0.offset <= head } ?? 0
		var lowerIndex = index
		while lowerIndex > 0, isTextObjectWordCharacter(offsets[lowerIndex - 1].character) {
			lowerIndex -= 1
		}
		var upperIndex = index
		while upperIndex + 1 < offsets.count, isTextObjectWordCharacter(offsets[upperIndex + 1].character) {
			upperIndex += 1
		}
		guard isTextObjectWordCharacter(offsets[lowerIndex].character) else {
			return nil
		}
		var lower = offsets[lowerIndex].offset
		var upper = offsets[upperIndex].offset + String(offsets[upperIndex].character).utf8.count
		if includeWhitespace {
			var cursor = upperIndex + 1
			while cursor < offsets.count, offsets[cursor].character.isWhitespace {
				upper = offsets[cursor].offset + String(offsets[cursor].character).utf8.count
				cursor += 1
			}
			if cursor == upperIndex + 1 {
				cursor = lowerIndex - 1
				while cursor >= 0, offsets[cursor].character.isWhitespace {
					lower = offsets[cursor].offset
					cursor -= 1
				}
			}
		}
		return lower ..< upper
	}

	private func pairTextObjectRange(open: Character, close: Character, includeDelimiters: Bool) -> Range<Int>? {
		let offsets = characterOffsets()
		let head = editor.selections.primary.head
		guard let openIndex = offsets.lastIndex(where: { $0.offset <= head && $0.character == open }) else {
			return nil
		}
		guard let closeIndex = offsets.firstIndex(where: { $0.offset > head && $0.character == close }) else {
			return nil
		}
		let openEnd = offsets[openIndex].offset + String(offsets[openIndex].character).utf8.count
		let closeEnd = offsets[closeIndex].offset + String(offsets[closeIndex].character).utf8.count
		return includeDelimiters ? offsets[openIndex].offset ..< closeEnd : openEnd ..< offsets[closeIndex].offset
	}

	private func paragraphTextObjectRange(includeBlankLine: Bool) -> Range<Int>? {
		let line = editor.rope.line(forOffset: editor.selections.primary.head)
		var startLine = line
		while startLine > 0, !lineIsBlank(startLine - 1) {
			startLine -= 1
		}
		var endLine = line
		while endLine + 1 < editor.rope.lineCount, !lineIsBlank(endLine + 1) {
			endLine += 1
		}
		let start = editor.rope.offset(forLine: startLine)
		let endLineAfterObject = min(endLine + 1, editor.rope.lineCount - 1)
		var end = endLine + 1 < editor.rope.lineCount ? editor.rope.offset(forLine: endLine + 1) : editor.rope.length
		if includeBlankLine, endLineAfterObject + 1 < editor.rope.lineCount, lineIsBlank(endLineAfterObject) {
			end = editor.rope.offset(forLine: endLineAfterObject + 1)
		}
		return start ..< end
	}

	private func lineIsBlank(_ line: Int) -> Bool {
		editor.rope.slice(editor.rope.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
	}

	private func isTextObjectWordCharacter(_ character: Character) -> Bool {
		!character.isWhitespace && character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0.value == 95 }
	}

	private func repeatMotion(_ motion: Motion) {
		let before = editor.selections
		for _ in 0 ..< keymapRepeatCount {
			editor.moveCursor(motion)
		}
		if keymapEngine.mode == .normal, isJumpMotion(motion), editor.selections != before {
			jumpBackSelection = before
		}
	}

	private func isJumpMotion(_ motion: Motion) -> Bool {
		switch motion {
		case .bufferStart, .bufferEnd, .paragraphForward, .paragraphBackward, .pageDown, .pageUp:
			return true
		default:
			return false
		}
	}

	private func jumpBack() {
		let current = editor.selections
		guard let target = jumpBackSelection else {
			return
		}
		editor.setSelection(clampedSelectionSet(target))
		jumpBackSelection = current
	}

	private func clampedSelectionSet(_ selectionSet: SelectionSet) -> SelectionSet {
		let length = editor.rope.length
		func clamped(_ selection: Selection) -> Selection {
			Selection(
				anchor: min(max(selection.anchor, 0), length),
				head: min(max(selection.head, 0), length),
				affinity: selection.affinity
			)
		}
		return SelectionSet(primary: clamped(selectionSet.primary), secondaries: selectionSet.secondaries.map { clamped($0) })
	}

	private var keymapRepeatCount: Int {
		max(1, min(keymapEngine.lastCommandCount, 9_999))
	}

	private func handlePendingCharacterMotion(_ event: NSEvent) -> Bool {
		guard let motion = pendingCharacterMotion, let key = Key(event: event), key.modifiers.isEmpty, key.value.count == 1, let value = key.value.first else {
			return false
		}
		pendingCharacterMotion = nil
		moveToCharacter(value, motion: motion, count: keymapRepeatCount)
		lastCharacterMotion = (motion, value)
		return true
	}

	private func repeatLastCharacterMotion(reversed: Bool) {
		guard let lastCharacterMotion else {
			return
		}
		let motion = reversed ? lastCharacterMotion.motion.reversed : lastCharacterMotion.motion
		moveToCharacter(lastCharacterMotion.value, motion: motion, count: keymapRepeatCount)
	}

	private func moveToCharacter(_ value: Character, motion: CharacterMotion, count: Int) {
		let offsets = characterOffsets()
		guard !offsets.isEmpty else {
			return
		}
		let lineRange = editor.rope.lineRange(editor.rope.line(forOffset: editor.selections.primary.head))
		let lineOffsets = offsets.enumerated().filter { _, element in
			lineRange.contains(element.offset)
		}
		let head = editor.selections.primary.head
		let matches: [(offset: Int, index: Int)]
		switch motion {
		case .findForward, .tillForward:
			matches = lineOffsets.filter { $0.element.offset > head && $0.element.character == value }.map { ($0.element.offset, $0.offset) }
		case .findBackward, .tillBackward:
			matches = Array(lineOffsets.filter { $0.element.offset < head && $0.element.character == value }.map { ($0.element.offset, $0.offset) }.reversed())
		}
		guard count > 0, count <= matches.count else {
			return
		}
		let match = matches[count - 1]
		let targetIndex: Int
		switch motion {
		case .findForward, .findBackward:
			targetIndex = match.index
		case .tillForward:
			targetIndex = max(match.index - 1, 0)
		case .tillBackward:
			targetIndex = min(match.index + 1, offsets.count - 1)
		}
		let target = offsets[targetIndex].offset
		editor.setSelection(SelectionSet(primary: Selection(anchor: target, head: target)))
		syncEditorState()
	}

	private func characterOffsets() -> [(offset: Int, character: Character)] {
		var offset = 0
		return editor.text.map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

	private func handlePassthroughKey(
		characters: String?,
		charactersIgnoringModifiers: String?,
		keyCode: UInt16,
		modifierFlags: NSEvent.ModifierFlags
	) -> Bool {
		lastYankRange = nil
		if !modifierFlags.intersection([.command, .control]).isEmpty {
			return false
		}
		var didEdit = false
		switch keyCode {
		case 51:
			editor.deleteBackward()
			didEdit = true
		case 117:
			editor.deleteForward()
			didEdit = true
		case 123:
			editor.moveCursor(.charBackward)
		case 124:
			editor.moveCursor(.charForward)
		default:
			guard let characters, !characters.isEmpty, charactersIgnoringModifiers != "\u{1b}" else {
				return false
			}
			editor.insert(characters)
			didEdit = true
		}
		syncEditorState()
		if didEdit {
			editorDidChange?(editor)
			if let characters, characters.count == 1 {
				if completionTriggerCharacters.contains(characters) {
					_ = completionRequested?(characters)
				}
				if signatureHelpTriggerCharacters.contains(characters) {
					_ = signatureHelpRequested?(characters)
				}
				if characters == ")" {
					signatureHelpDismissRequested?()
				}
			}
		}
		return true
	}

	func solidOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		var instances: [MetalGlyphInstance] = []
		appendSelectionOverlayInstances(scale: scale, into: &instances)
		appendCursorOverlayInstances(scale: scale, into: &instances)
		return instances
	}

	func gutterOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		var instances: [MetalGlyphInstance] = []
		appendGutterOverlayInstances(scale: scale, into: &instances)
		return instances
	}

	func textGlyphInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		var instances: [MetalGlyphInstance] = []
		appendTextGlyphInstances(scale: scale, into: &instances)
		return instances
	}

	private func appendTextGlyphInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		guard ensureGlyphAtlas(scale: scale) else {
			return
		}
		let shaper = LineShaper()
		let renderingMode = glyphRenderingMode(scale: scale)
		let scaleKey = Self.scaleKey(for: scale)
		for lineIndex in visibleLineRange {
			let lineRange = editor.rope.lineRange(lineIndex)
			let key = LineShapeCacheKey(
				lineIndex: lineIndex,
				lowerBound: lineRange.lowerBound,
				upperBound: lineRange.upperBound,
				renderingMode: renderingMode,
				scaleKey: scaleKey,
				highlightRevision: highlightRevision
			)
			let glyphs = cachedGlyphs(for: key, lineRange: lineRange, scale: scale, shaper: shaper)
			guard !glyphs.isEmpty else {
				continue
			}
			let y = topContentInset + textInset.y + CGFloat(lineIndex - topLineIndex) * lineHeight
			if showLineNumbers {
				appendLineNumberGlyphInstances(lineIndex: lineIndex, y: y, scale: scale, shaper: shaper, into: &instances)
			}
			for glyph in glyphs {
				let pixelX = ((glyph.originX - xOffset) * scale).rounded()
				let pixelY = ((y + glyph.originYOffset) * scale).rounded()
				instances.append(MetalGlyphInstance(
					screenOrigin: SIMD2<Float>(Float(pixelX), Float(pixelY)),
					size: SIMD2<Float>(Float(glyph.width * scale), Float(glyph.height * scale)),
					atlasUV: glyph.atlasUV,
					color: glyph.color
				))
			}
		}
	}

	private func cachedGlyphs(
		for key: LineShapeCacheKey,
		lineRange: Range<Int>,
		scale: CGFloat,
		shaper: LineShaper
	) -> [CachedLineGlyph] {
		if let cached = lineShapeCache[key] {
			return cached
		}
		let line = editor.rope.slice(lineRange)
		guard !line.isEmpty, let cached = shapeCachedGlyphs(line: line, lineRange: lineRange, scale: scale, shaper: shaper) else {
			return []
		}
		if lineShapeCache.count > Self.maxCachedShapedLines {
			lineShapeCache.removeAll(keepingCapacity: true)
		}
		lineShapeCache[key] = cached
		return cached
	}

	private func appendLineNumberGlyphInstances(lineIndex: Int, y: CGFloat, scale: CGFloat, shaper: LineShaper, into instances: inout [MetalGlyphInstance]) {
		let label = "\(lineIndex + 1)"
		let baseX = lineNumberRightEdge - typographicWidth(label)
		guard let glyphs = shapeGlyphs(line: label, baseX: baseX, scale: scale, shaper: shaper, color: Self.lineNumberTextColor) else {
			return
		}
		for glyph in glyphs {
			let pixelX = (glyph.originX * scale).rounded()
			let pixelY = ((y + glyph.originYOffset) * scale).rounded()
			instances.append(MetalGlyphInstance(
				screenOrigin: SIMD2<Float>(Float(pixelX), Float(pixelY)),
				size: SIMD2<Float>(Float(glyph.width * scale), Float(glyph.height * scale)),
				atlasUV: glyph.atlasUV,
				color: glyph.color
			))
		}
	}

	private func shapeCachedGlyphs(line: String, lineRange: Range<Int>, scale: CGFloat, shaper: LineShaper) -> [CachedLineGlyph]? {
		shapeGlyphs(line: line, baseX: textInset.x, scale: scale, shaper: shaper) { [weak self] range in
			self?.textColor(for: (range.lowerBound + lineRange.lowerBound) ..< (range.upperBound + lineRange.lowerBound)) ?? Self.defaultTextColor
		}
	}

	private func shapeGlyphs(line: String, baseX: CGFloat, scale: CGFloat, shaper: LineShaper, color: SIMD4<Float>) -> [CachedLineGlyph]? {
		shapeGlyphs(line: line, baseX: baseX, scale: scale, shaper: shaper) { _ in color }
	}

	private func shapeGlyphs(
		line: String,
		baseX: CGFloat,
		scale: CGFloat,
		shaper: LineShaper,
		colorForRange: (Range<Int>) -> SIMD4<Float>
	) -> [CachedLineGlyph]? {
		try? withGlyphAtlas { atlas in
			let rasterScale = max(scale, 1)
			let rasterFont = scaledTextFont(scale: rasterScale)
			let shaped = try shaper.shape(line, font: textFont, rasterFont: rasterFont, atlas: &atlas, colorForRange: colorForRange)
			let fontHeight = CTFontGetAscent(rasterFont) + CTFontGetDescent(rasterFont) + CTFontGetLeading(rasterFont)
			let baselineY = max(0, lineHeight * rasterScale - fontHeight) / 2 + CTFontGetAscent(rasterFont)
			var cached: [CachedLineGlyph] = []
			cached.reserveCapacity(shaped.count)
			for glyph in shaped {
				let entry = try atlas.entry(for: glyph.glyphID, font: rasterFont)
				let padding = CGFloat(entry.padding)
				cached.append(CachedLineGlyph(
					originX: baseX + glyph.x + (entry.bounds.origin.x - padding) / rasterScale,
					originYOffset: (baselineY - entry.bounds.maxY - padding) / rasterScale,
					width: CGFloat(entry.width) / rasterScale,
					height: CGFloat(entry.height) / rasterScale,
					atlasUV: SIMD4<Float>(
						Float(glyph.atlasUV.u0),
						Float(glyph.atlasUV.v0),
						Float(glyph.atlasUV.u1),
						Float(glyph.atlasUV.v1)
					),
					color: glyph.color
				))
			}
			return cached
		}
	}

	private func appendSelectionOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for rect in selectionRects {
			instances.append(solidInstance(rect: rect, scale: scale, color: SIMD4<Float>(0.25, 0.45, 0.95, 0.35)))
		}
	}

	private func appendFindMatchOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for rect in findMatchRects {
			instances.append(solidInstance(rect: rect, scale: scale, color: SIMD4<Float>(0.80, 0.62, 0.12, 0.34)))
		}
	}

	private func appendGutterOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		for item in gutterMarkerRects {
			let color = item.marker.color ?? gutterColor(for: item.marker.severity)
			if item.marker.placement == .betweenLines {
				appendGutterCaretInstances(rect: item.rect, scale: scale, color: color, into: &instances)
			} else {
				instances.append(solidInstance(rect: item.rect, scale: scale, color: color))
			}
		}
	}

	private func appendGutterCaretInstances(rect: CGRect, scale: CGFloat, color: SIMD4<Float>, into instances: inout [MetalGlyphInstance]) {
		let slices = [
			CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: 2),
			CGRect(x: rect.minX + 1, y: rect.minY + 2, width: rect.width - 2, height: 2),
			CGRect(x: rect.minX + 3, y: rect.minY + 4, width: rect.width - 6, height: 2),
		]
		for slice in slices where slice.width > 0 {
			instances.append(solidInstance(rect: slice, scale: scale, color: color))
		}
	}

	private func gutterColor(for severity: WorkspaceProblemSeverity) -> SIMD4<Float> {
		switch severity {
		case .error:
			return SIMD4<Float>(0.95, 0.25, 0.22, 1.0)
		case .warning:
			return SIMD4<Float>(0.95, 0.68, 0.18, 1.0)
		case .info:
			return SIMD4<Float>(0.24, 0.56, 0.96, 1.0)
		case .hint:
			return SIMD4<Float>(0.58, 0.62, 0.68, 1.0)
		}
	}

	private func textColor(for range: Range<Int>) -> SIMD4<Float> {
		guard let span = highlightSpans.last(where: { $0.range.overlaps(range) }) else {
			return Self.defaultTextColor
		}
		return span.color
	}

	private func appendCursorOverlayInstances(scale: CGFloat, into instances: inout [MetalGlyphInstance]) {
		if cursorBlinkVisible, let cursorRect {
			instances.append(solidInstance(rect: cursorRect, scale: scale, color: Self.cursorColor))
		}
	}

	private func updateDrawableSize() {
		guard let layer = layer as? CAMetalLayer else {
			return
		}
		let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1
		layer.maximumDrawableCount = 3
		if #available(macOS 10.11, *) {
			layer.wantsExtendedDynamicRangeContent = false
		}
		layer.contentsScale = scale
		layer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
	}

	private func renderClearColor() {
		guard
			let layer = layer as? CAMetalLayer,
			let commandQueue,
			let drawable = layer.nextDrawable()
		else {
			return
		}
		renderPass.colorAttachments[0].texture = drawable.texture
		renderPass.colorAttachments[0].loadAction = .clear
		renderPass.colorAttachments[0].storeAction = .store
		renderPass.colorAttachments[0].clearColor = clearColor
		guard
			let commandBuffer = commandQueue.makeCommandBuffer(),
			let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass)
		else {
			return
		}
		let scale = layer.contentsScale
		renderGutterInstances(scale: scale, encoder: encoder, drawableSize: layer.drawableSize)
		renderFindMatchInstances(scale: scale, encoder: encoder, drawableSize: layer.drawableSize)
		renderSelectionInstances(scale: scale, encoder: encoder, drawableSize: layer.drawableSize)
		renderText(encoder: encoder, drawableSize: layer.drawableSize, scale: scale)
		renderCursorInstances(scale: scale, encoder: encoder, drawableSize: layer.drawableSize)
		encoder.endEncoding()
		renderPass.colorAttachments[0].texture = nil
		commandBuffer.present(drawable)
		commandBuffer.commit()
		renderedFrameCount += 1
		Self.recordBenchStageOnce("first_draw")
	}

	private func startDisplayLink() {
		guard displayLink == nil else {
			return
		}
		var link: CVDisplayLink?
		let displayID = currentDisplayID() ?? CGMainDisplayID()
		guard CVDisplayLinkCreateWithCGDisplay(displayID, &link) == kCVReturnSuccess, let link else {
			return
		}
		displayLinkRefreshRate = refreshRate(for: link)
		CVDisplayLinkSetOutputCallback(link, metalTextViewDisplayLinkCallback, Unmanaged.passUnretained(self).toOpaque())
		guard CVDisplayLinkStart(link) == kCVReturnSuccess else {
			CVDisplayLinkSetOutputCallback(link, nil, nil)
			displayLinkRefreshRate = nil
			return
		}
		displayLink = link
	}

	private func stopDisplayLink() {
		guard let displayLink else {
			return
		}
		CVDisplayLinkStop(displayLink)
		CVDisplayLinkSetOutputCallback(displayLink, nil, nil)
		self.displayLink = nil
		displayLinkRefreshRate = nil
	}

	private func restartDisplayLink() {
		guard displayLink != nil else {
			return
		}
		stopDisplayLink()
		startDisplayLink()
	}

	private func startCursorBlinkTimer() {
		guard cursorBlinkTimer == nil else {
			return
		}
		cursorBlinkTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
			guard let self, self.cursorRect != nil else {
				return
			}
			self.cursorBlinkVisible.toggle()
			self.markDirty()
		}
	}

	private func stopCursorBlinkTimer() {
		cursorBlinkTimer?.invalidate()
		cursorBlinkTimer = nil
	}

	fileprivate func displayLinkDidTick() {
		guard consumeDirtyForDisplayLink() else {
			return
		}
		DispatchQueue.main.async { [weak self] in
			self?.renderClearColor()
		}
	}

	private func renderSolidInstances(_ instances: [MetalGlyphInstance], encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		guard !instances.isEmpty, let pipeline = makeRenderPipeline(), let sampler = makeSampler(), let atlas = makeSolidAtlasTexture() else {
			return
		}
		var viewport = MetalViewportUniforms(size: SIMD2<Float>(Float(drawableSize.width), Float(drawableSize.height)))
		var fragment = MetalFragmentUniforms(atlasMode: 0)
		instances.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				return
			}
			encoder.setRenderPipelineState(pipeline)
			setInstanceData(base: base, length: bytes.count, encoder: encoder)
			encoder.setVertexBytes(&viewport, length: MemoryLayout<MetalViewportUniforms>.stride, index: 1)
			encoder.setFragmentTexture(atlas, index: 0)
			encoder.setFragmentSamplerState(sampler, index: 0)
			encoder.setFragmentBytes(&fragment, length: MemoryLayout<MetalFragmentUniforms>.stride, index: 0)
			encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: instances.count)
		}
	}

	private func renderFindMatchInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendFindMatchOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	private func renderGutterInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendGutterOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	private func renderSelectionInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendSelectionOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	private func renderCursorInstances(scale: CGFloat, encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		solidInstanceScratch.removeAll(keepingCapacity: true)
		appendCursorOverlayInstances(scale: scale, into: &solidInstanceScratch)
		renderSolidInstances(solidInstanceScratch, encoder: encoder, drawableSize: drawableSize)
	}

	private func renderText(encoder: MTLRenderCommandEncoder, drawableSize: CGSize, scale: CGFloat) {
		textInstanceScratch.removeAll(keepingCapacity: true)
		appendTextGlyphInstances(scale: scale, into: &textInstanceScratch)
		guard !textInstanceScratch.isEmpty, let pipeline = makeRenderPipeline(), let sampler = makeSampler(), ensureGlyphAtlas(scale: scale), let atlas = glyphAtlas else {
			return
		}
		var viewport = MetalViewportUniforms(size: SIMD2<Float>(Float(drawableSize.width), Float(drawableSize.height)))
		var fragment = MetalFragmentUniforms(atlasMode: atlas.renderingMode == .subpixel ? 2 : 1)
		textInstanceScratch.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				return
			}
			encoder.setRenderPipelineState(pipeline)
			setInstanceData(base: base, length: bytes.count, encoder: encoder)
			encoder.setVertexBytes(&viewport, length: MemoryLayout<MetalViewportUniforms>.stride, index: 1)
			encoder.setFragmentTexture(atlas.texture, index: 0)
			encoder.setFragmentSamplerState(sampler, index: 0)
			encoder.setFragmentBytes(&fragment, length: MemoryLayout<MetalFragmentUniforms>.stride, index: 0)
			encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: textInstanceScratch.count)
		}
	}

	private func setInstanceData(base: UnsafeRawPointer, length: Int, encoder: MTLRenderCommandEncoder) {
		if length <= 4_096 {
			encoder.setVertexBytes(base, length: length, index: 0)
			return
		}
		if instanceBufferCapacity < length {
			instanceBuffer = metalDevice?.makeBuffer(length: length)
			instanceBufferCapacity = instanceBuffer?.length ?? 0
		}
		guard let buffer = instanceBuffer else {
			return
		}
		buffer.contents().copyMemory(from: base, byteCount: length)
		encoder.setVertexBuffer(buffer, offset: 0, index: 0)
	}

	private func makeRenderPipeline() -> MTLRenderPipelineState? {
		if let renderPipeline {
			return renderPipeline
		}
		guard
			let metalDevice,
			let source = try? ShaderSource.load(),
			let library = try? metalDevice.makeLibrary(source: source, options: nil)
		else {
			return nil
		}
		let descriptor = MTLRenderPipelineDescriptor()
		descriptor.vertexFunction = library.makeFunction(name: "glyph_vertex")
		descriptor.fragmentFunction = library.makeFunction(name: "glyph_fragment")
		descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
		descriptor.colorAttachments[0].isBlendingEnabled = true
		descriptor.colorAttachments[0].rgbBlendOperation = .add
		descriptor.colorAttachments[0].alphaBlendOperation = .add
		descriptor.colorAttachments[0].sourceRGBBlendFactor = .one
		descriptor.colorAttachments[0].sourceAlphaBlendFactor = .one
		descriptor.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
		descriptor.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha
		renderPipeline = try? metalDevice.makeRenderPipelineState(descriptor: descriptor)
		return renderPipeline
	}

	private func makeSampler() -> MTLSamplerState? {
		if let samplerState {
			return samplerState
		}
		let descriptor = MTLSamplerDescriptor()
		descriptor.minFilter = .linear
		descriptor.magFilter = .linear
		samplerState = metalDevice?.makeSamplerState(descriptor: descriptor)
		return samplerState
	}

	private func makeSolidAtlasTexture() -> MTLTexture? {
		if let solidAtlasTexture {
			return solidAtlasTexture
		}
		guard let metalDevice else {
			return nil
		}
		let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: 1, height: 1, mipmapped: false)
		descriptor.usage = [.shaderRead]
		guard let texture = metalDevice.makeTexture(descriptor: descriptor) else {
			return nil
		}
		var coverage: UInt8 = 255
		texture.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &coverage, bytesPerRow: 1)
		solidAtlasTexture = texture
		return texture
	}

	private func ensureGlyphAtlas(scale: CGFloat) -> Bool {
		let renderingMode = glyphRenderingMode(scale: scale)
		let scaleKey = Self.scaleKey(for: scale)
		if glyphAtlas != nil, glyphAtlasRenderingMode == renderingMode, glyphAtlasScaleKey == scaleKey {
			return true
		}
		guard let metalDevice, let atlas = try? GlyphAtlas(device: metalDevice, renderingMode: renderingMode) else {
			return false
		}
		glyphAtlas = atlas
		glyphAtlasRenderingMode = renderingMode
		glyphAtlasScaleKey = scaleKey
		return true
	}

	private func withGlyphAtlas<T>(_ body: (inout GlyphAtlas) throws -> T) rethrows -> T? {
		guard var atlas = glyphAtlas else {
			return nil
		}
		defer {
			glyphAtlas = atlas
		}
		return try body(&atlas)
	}

	private func resetGlyphCacheForCurrentScale() {
		let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1
		let mode = glyphRenderingMode(scale: scale)
		let scaleKey = Self.scaleKey(for: scale)
		guard glyphAtlasRenderingMode != mode || glyphAtlasScaleKey != scaleKey else {
			return
		}
		glyphAtlas = nil
		glyphAtlasRenderingMode = nil
		glyphAtlasScaleKey = nil
		lineShapeCache.removeAll(keepingCapacity: true)
	}

	private func glyphRenderingMode(scale: CGFloat) -> GlyphAtlas.RenderingMode {
		.grayscale
	}

	private static func makeDefaultTextFont() -> CTFont {
		makeTextFont(name: defaultFontName, size: defaultFontSize)
	}

	private static func makeTextFont(name: String, size: CGFloat) -> CTFont {
		let clampedSize = min(max(size, 6), 72)
		let font = NSFont(name: name, size: clampedSize) ?? NSFont(name: defaultFontName, size: clampedSize) ?? NSFont.monospacedSystemFont(ofSize: clampedSize, weight: .regular)
		return CTFontCreateWithName(font.fontName as CFString, font.pointSize, nil)
	}

	private func scaledTextFont(scale: CGFloat) -> CTFont {
		CTFontCreateCopyWithAttributes(textFont, CTFontGetSize(textFont) * scale, nil, nil)
	}

	private static func scaleKey(for scale: CGFloat) -> Int {
		Int((scale * 100).rounded())
	}

	private func currentDisplayID() -> CGDirectDisplayID? {
		guard let number = window?.screen?.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else {
			return nil
		}
		return CGDirectDisplayID(number.uint32Value)
	}

	private func refreshRate(for link: CVDisplayLink) -> Double? {
		let actualPeriod = CVDisplayLinkGetActualOutputVideoRefreshPeriod(link)
		if actualPeriod > 0 {
			return 1 / actualPeriod
		}
		let period = CVDisplayLinkGetNominalOutputVideoRefreshPeriod(link)
		guard period.timeValue > 0, period.timeScale > 0 else {
			return nil
		}
		return Double(period.timeScale) / Double(period.timeValue)
	}

	private func syncEditorState() {
		lineCount = editor.rope.lineCount
		let head = editor.selections.primary.head
		let line = editor.rope.line(forOffset: head)
		let lineRange = editor.rope.lineRange(line)
		let prefix = editor.rope.slice(lineRange.lowerBound ..< min(head, lineRange.upperBound))
		let cursorX = textInset.x + typographicWidth(prefix) - xOffset
		let cursorY = topContentInset + textInset.y + CGFloat(line - topLineIndex) * lineHeight
		setCursor(x: cursorX, y: cursorY, height: lineHeight)
		setSelectionRects(selectionRects(for: editor.selections))
		refreshFindMatchRects()
		refreshGutterMarkerRects()
		refreshAccessibilityValue()
		markDirty()
	}

	private func accessibilityCurrentLineValue() -> String {
		let head = min(max(editor.selections.primary.head, 0), editor.rope.length)
		let line = editor.rope.line(forOffset: head)
		let text = editor.rope.slice(editor.rope.lineRange(line))
		if text.isEmpty {
			return Self.localized("Line \(line + 1): blank")
		}
		return Self.localized("Line \(line + 1): \(text)")
	}

	private func refreshAccessibilityValue() {
		let value = accessibilityCurrentLineValue()
		guard value != lastAccessibilityValue else {
			return
		}
		lastAccessibilityValue = value
		if window != nil {
			NSAccessibility.post(element: self, notification: .valueChanged)
		}
	}

	private func selectionRects(for selections: SelectionSet) -> [CGRect] {
		([selections.primary] + selections.secondaries).flatMap { selection -> [CGRect] in
			rects(forUTF8Range: selection.range)
		}
	}

	private func refreshFindMatchRects() {
		findMatchRects = findMatchRanges.flatMap { rects(forUTF8Range: $0) }
		markDirty()
	}

	private func refreshGutterMarkerRects() {
		guard let gutterDecorator, !visibleLineRange.isEmpty else {
			gutterMarkerRects = []
			closeGutterPopover()
			markDirty()
			return
		}
		let markers = gutterDecorator.gutterMarkers(in: visibleLineRange, for: self)
			.filter { visibleLineRange.contains($0.line) }
			.sorted {
				if $0.line != $1.line {
					return $0.line < $1.line
				}
				return $0.id < $1.id
			}
		var slotsByLine: [Int: Int] = [:]
		gutterMarkerRects = markers.map { marker in
			let slot = slotsByLine[marker.line, default: 0]
			slotsByLine[marker.line] = slot + 1
			let lineY = topContentInset + textInset.y + CGFloat(marker.line - topLineIndex) * lineHeight
			let x = max(3, gutterWidth - 7 - CGFloat(slot % 3) * 6)
			if marker.placement == .betweenLines {
				let rect = CGRect(
					x: x - 2,
					y: max(topContentInset + 1, lineY - 3),
					width: 8,
					height: 6
				)
				return (marker, rect)
			}
			let markerHeight = min(12, max(6, lineHeight - 5))
			let markerWidth: CGFloat = 4
			let rect = CGRect(
				x: x,
				y: lineY + max(2, (lineHeight - markerHeight) / 2),
				width: markerWidth,
				height: markerHeight
			)
			return (marker, rect)
		}
		if let hoveredGutterMarkerID, !gutterMarkerRects.contains(where: { $0.marker.id == hoveredGutterMarkerID }) {
			closeGutterPopover()
		}
		markDirty()
	}

	func gutterMarker(atLocalPoint point: NSPoint) -> GutterMarker? {
		gutterMarkerRects.first { $0.rect.insetBy(dx: -3, dy: -2).contains(point) }?.marker
	}

	private func gutterMarker(forMouseEvent event: NSEvent) -> GutterMarker? {
		gutterMarker(atLocalPoint: convert(event.locationInWindow, from: nil))
	}

	private func gutterMarkerRect(for id: String) -> CGRect? {
		gutterMarkerRects.first { $0.marker.id == id }?.rect
	}

	private func showGutterPopover(for marker: GutterMarker, relativeTo rect: CGRect) {
		guard hoveredGutterMarkerID != marker.id || gutterPopover?.isShown != true else {
			return
		}
		closeGutterPopover()
		guard let controller = gutterDecorator?.gutterPopoverViewController(for: marker, in: self) else {
			return
		}
		let popover = NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = controller
		popover.show(relativeTo: rect, of: self, preferredEdge: .maxX)
		gutterPopover = popover
		hoveredGutterMarkerID = marker.id
	}

	private func closeGutterPopover() {
		gutterPopover?.close()
		gutterPopover = nil
		hoveredGutterMarkerID = nil
	}

	private func rects(forUTF8Range range: Range<Int>) -> [CGRect] {
		guard !range.isEmpty else {
			return []
		}
		let startLine = editor.rope.line(forOffset: range.lowerBound)
		let endLine = editor.rope.line(forOffset: range.upperBound)
		guard startLine < visibleLineRange.upperBound, endLine >= visibleLineRange.lowerBound else {
			return []
		}
		return (startLine ... endLine).compactMap { line -> CGRect? in
			guard visibleLineRange.contains(line) else {
				return nil
			}
			let lineRange = editor.rope.lineRange(line)
			let lower = max(range.lowerBound, lineRange.lowerBound)
			let upper = min(range.upperBound, lineRange.upperBound)
			guard lower < upper else {
				return nil
			}
			let before = editor.rope.slice(lineRange.lowerBound ..< lower)
			let selected = editor.rope.slice(lower ..< upper)
			return CGRect(
				x: textInset.x + typographicWidth(before) - xOffset,
				y: topContentInset + textInset.y + CGFloat(line - topLineIndex) * lineHeight,
				width: max(2, typographicWidth(selected)),
				height: lineHeight
			)
		}
	}

	private func typographicWidth(_ text: String) -> CGFloat {
		guard !text.isEmpty else {
			return 0
		}
		let attributed = NSAttributedString(string: text, attributes: [kCTFontAttributeName as NSAttributedString.Key: textFont])
		return CTLineGetTypographicBounds(CTLineCreateWithAttributedString(attributed), nil, nil, nil)
	}

	private static func localized(_ value: String.LocalizationValue) -> String {
		String(localized: value, bundle: .module, locale: accessibilityLocale)
	}

	private static func recordBenchStageOnce(_ name: String) {
		benchStageLock.lock()
		let inserted = recordedBenchStages.insert(name).inserted
		benchStageLock.unlock()
		guard inserted, let path = ProcessInfo.processInfo.environment["ITSY_BENCH_STAGES_PATH"] else {
			return
		}
		let line = "\(name) \(DispatchTime.now().uptimeNanoseconds)\n"
		let url = URL(fileURLWithPath: path)
		if !FileManager.default.fileExists(atPath: path) {
			FileManager.default.createFile(atPath: path, contents: nil)
		}
		guard let handle = try? FileHandle(forWritingTo: url) else {
			return
		}
		defer {
			try? handle.close()
		}
		_ = try? handle.seekToEnd()
		_ = try? handle.write(contentsOf: Data(line.utf8))
	}
}

extension MetalTextView: NSTextInputClient {
	public func insertText(_ string: Any, replacementRange: NSRange) {
		lastYankRange = nil
		let text = plainString(from: string)
		let range = replacementUTF8Range(replacementRange) ?? markedRangeUTF8 ?? editor.selections.primary.range
		replace(range: range, with: text)
		markedRangeUTF8 = nil
		syncEditorState()
		editorDidChange?(editor)
	}

	public override func doCommand(by selector: Selector) {
		lastYankRange = nil
		var didEdit = false
		switch selector {
		case #selector(NSResponder.deleteBackward(_:)):
			editor.deleteBackward()
			didEdit = true
		case #selector(NSResponder.deleteForward(_:)):
			editor.deleteForward()
			didEdit = true
		case #selector(NSResponder.moveLeft(_:)):
			editor.moveCursor(.charBackward)
		case #selector(NSResponder.moveRight(_:)):
			editor.moveCursor(.charForward)
		case #selector(NSResponder.moveToBeginningOfLine(_:)):
			editor.moveCursor(.lineStart)
		case #selector(NSResponder.moveToEndOfLine(_:)):
			editor.moveCursor(.lineEnd)
		case #selector(NSResponder.insertNewline(_:)):
			editor.insert("\n")
			didEdit = true
		default:
			_ = tryToPerform(selector, with: nil)
		}
		markedRangeUTF8 = nil
		syncEditorState()
		if didEdit {
			editorDidChange?(editor)
		}
	}

	public func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
		let text = plainString(from: string)
		let range = replacementUTF8Range(replacementRange) ?? markedRangeUTF8 ?? editor.selections.primary.range
		replace(range: range, with: text)
		let lower = range.lowerBound
		let upper = lower + text.utf8.count
		markedRangeUTF8 = lower ..< upper
		let selection = utf8Range(in: text, forUTF16Range: selectedRange, baseUTF8Offset: lower) ?? upper ..< upper
		editor.setSelection(SelectionSet(primary: Selection(anchor: selection.lowerBound, head: selection.upperBound)))
		syncEditorState()
		editorDidChange?(editor)
	}

	public func unmarkText() {
		markedRangeUTF8 = nil
		markDirty()
	}

	public func selectedRange() -> NSRange {
		nsRange(forUTF8Range: editor.selections.primary.range)
	}

	public func markedRange() -> NSRange {
		guard let markedRangeUTF8 else {
			return NSRange(location: NSNotFound, length: 0)
		}
		return nsRange(forUTF8Range: markedRangeUTF8)
	}

	public func hasMarkedText() -> Bool {
		markedRangeUTF8 != nil
	}

	public func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? {
		guard let utf8Range = utf8Range(forNSRange: range) else {
			return nil
		}
		actualRange?.pointee = nsRange(forUTF8Range: utf8Range)
		return NSAttributedString(
			string: editor.rope.slice(utf8Range),
			attributes: [.font: NSFont.monospacedSystemFont(ofSize: 14, weight: .regular)]
		)
	}

	public func validAttributesForMarkedText() -> [NSAttributedString.Key] {
		[.underlineStyle, .foregroundColor, .backgroundColor]
	}

	public func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect {
		let utf8Range = utf8Range(forNSRange: range) ?? editor.selections.primary.range
		actualRange?.pointee = nsRange(forUTF8Range: utf8Range)
		let rect = rectForUTF8Offset(utf8Range.lowerBound)
		guard let window else {
			return convert(rect, to: nil)
		}
		return window.convertToScreen(convert(rect, to: nil))
	}

	public func characterIndex(for point: NSPoint) -> Int {
		let windowPoint = window?.convertPoint(fromScreen: point) ?? point
		let local = convert(windowPoint, from: nil)
		let line = min(max(Int((local.y - textInset.y) / max(lineHeight, 1)) + topLineIndex, 0), max(0, editor.rope.lineCount - 1))
		let lineRange = editor.rope.lineRange(line)
		let lineText = editor.rope.slice(lineRange)
		let targetX = local.x - textInset.x + xOffset
		var offset = lineRange.lowerBound
		var bestUTF16 = 0
		let baseUTF16 = nsRange(forUTF8Range: lineRange.lowerBound ..< lineRange.lowerBound).location
		for character in lineText {
			let next = offset + String(character).utf8.count
			let width = typographicWidth(editor.rope.slice(lineRange.lowerBound ..< next))
			if width > targetX {
				break
			}
			offset = next
			bestUTF16 += String(character).utf16.count
		}
		return baseUTF16 + bestUTF16
	}

	private func utf8Offset(forMouseEvent event: NSEvent) -> Int {
		utf8Offset(forLocalPoint: convert(event.locationInWindow, from: nil))
	}

	private func utf8Offset(forLocalPoint local: NSPoint) -> Int {
		let line = min(max(Int((local.y - topContentInset - textInset.y) / max(lineHeight, 1)) + topLineIndex, 0), max(0, editor.rope.lineCount - 1))
		let lineRange = editor.rope.lineRange(line)
		let lineText = editor.rope.slice(lineRange)
		let targetX = local.x - textInset.x + xOffset
		var offset = lineRange.lowerBound
		for character in lineText {
			let next = offset + String(character).utf8.count
			let width = typographicWidth(editor.rope.slice(lineRange.lowerBound ..< next))
			if width > targetX {
				break
			}
			offset = next
		}
		return offset
	}

	private func plainString(from value: Any) -> String {
		if let attributed = value as? NSAttributedString {
			return attributed.string
		}
		return String(describing: value)
	}

	private func replacementUTF8Range(_ range: NSRange) -> Range<Int>? {
		range.location == NSNotFound ? nil : utf8Range(forNSRange: range)
	}

	private func replace(range: Range<Int>, with text: String) {
		editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.upperBound)))
		editor.insert(text)
	}

	private func nsRange(forUTF8Range range: Range<Int>) -> NSRange {
		let text = editor.text
		let lower = utf16Offset(in: text, forUTF8Offset: range.lowerBound)
		let upper = utf16Offset(in: text, forUTF8Offset: range.upperBound)
		return NSRange(location: lower, length: upper - lower)
	}

	private func utf8Range(forNSRange range: NSRange) -> Range<Int>? {
		utf8Range(in: editor.text, forUTF16Range: range, baseUTF8Offset: 0)
	}

	private func utf8Range(in text: String, forUTF16Range range: NSRange, baseUTF8Offset: Int) -> Range<Int>? {
		guard range.location != NSNotFound else {
			return nil
		}
		let lower = utf8Offset(in: text, forUTF16Offset: range.location)
		let upper = utf8Offset(in: text, forUTF16Offset: range.location + range.length)
		return baseUTF8Offset + lower ..< baseUTF8Offset + upper
	}

	private func utf8Offset(in text: String, forUTF16Offset target: Int) -> Int {
		var utf8 = 0
		var utf16 = 0
		for character in text {
			if utf16 >= target {
				break
			}
			utf8 += String(character).utf8.count
			utf16 += String(character).utf16.count
		}
		return utf8
	}

	private func utf16Offset(in text: String, forUTF8Offset target: Int) -> Int {
		var utf8 = 0
		var utf16 = 0
		for character in text {
			if utf8 >= target {
				break
			}
			utf8 += String(character).utf8.count
			utf16 += String(character).utf16.count
		}
		return utf16
	}

	private func rectForUTF8Offset(_ offset: Int) -> NSRect {
		let line = editor.rope.line(forOffset: min(max(offset, 0), editor.rope.length))
		let lineRange = editor.rope.lineRange(line)
		let prefix = editor.rope.slice(lineRange.lowerBound ..< min(offset, lineRange.upperBound))
		return NSRect(
			x: textInset.x + typographicWidth(prefix) - xOffset,
			y: textInset.y + CGFloat(line - topLineIndex) * lineHeight,
			width: 2,
			height: lineHeight
		)
	}
}

private func solidInstance(rect: CGRect, scale: CGFloat, color: SIMD4<Float>) -> MetalGlyphInstance {
	MetalGlyphInstance(
		screenOrigin: SIMD2<Float>(Float(rect.minX * scale), Float(rect.minY * scale)),
		size: SIMD2<Float>(Float(rect.width * scale), Float(rect.height * scale)),
		atlasUV: SIMD4<Float>(0, 0, 1, 1),
		color: color
	)
}

private let metalTextViewDisplayLinkCallback: CVDisplayLinkOutputCallback = { _, _, _, _, _, context in
	guard let context else {
		return kCVReturnSuccess
	}
	let view = Unmanaged<MetalTextView>.fromOpaque(context).takeUnretainedValue()
	view.displayLinkDidTick()
	return kCVReturnSuccess
}
