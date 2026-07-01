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
	var pendingCharacterMotion: CharacterMotion?
	var lastCharacterMotion: (motion: CharacterMotion, value: Character)?
	var pendingOperator: VimOperator?
	var pendingOperatorCount = 1
	var visualAnchor: Int?
	var visualHead: Int?
	var visualMode: VisualMode?
	var awaitingRegister = false
	var pendingRegister: String?
	var registers: [String: String] = [:]
	var jumpBackSelection: SelectionSet?
	var macroRegisters: [String: [RecordedKey]] = [:]
	var recordingMacroRegister: String?
	var currentMacroEvents: [RecordedKey] = []
	var awaitingMacroRecordRegister = false
	var awaitingMacroReplayRegister = false
	var replayingMacro = false
	var pendingExCommand: String?
	var insertUndoGroupActive = false
	var killRing = KillRing()
	var lastYankRange: Range<Int>?
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

	func syncEditorState() {
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

	func replace(range: Range<Int>, with text: String) {
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
