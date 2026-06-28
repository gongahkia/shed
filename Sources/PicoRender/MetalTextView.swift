import AppKit
import CoreText
import CoreVideo
import Metal
import PicoEditor
import QuartzCore

struct MetalGlyphInstance {
	var screenOrigin: SIMD2<Float>
	var size: SIMD2<Float>
	var atlasUV: SIMD4<Float>
	var color: SIMD4<Float>
}

struct MetalViewportUniforms {
	var size: SIMD2<Float>
}

struct MetalFragmentUniforms {
	var useAtlas: UInt32
}

public final class MetalTextView: NSView {
	public var clearColor = MTLClearColor(red: 0.08, green: 0.09, blue: 0.10, alpha: 1.0) {
		didSet { needsDisplay = true }
	}

	private let metalDevice: MTLDevice?
	private let commandQueue: MTLCommandQueue?
	private let dirtyLock = NSLock()
	private var dirty = true
	private var displayLink: CVDisplayLink?
	public private(set) var renderedFrameCount = 0
	private var cursorRect: CGRect?
	private var cursorBlinkVisible = true
	private var cursorBlinkTimer: Timer?
	private var selectionRects: [CGRect] = []
	private var renderPipeline: MTLRenderPipelineState?
	private var samplerState: MTLSamplerState?
	private var solidAtlasTexture: MTLTexture?
	private var glyphAtlas: GlyphAtlas?
	private var lineShaper: LineShaper?
	private var markedRangeUTF8: Range<Int>?
	private let textFont = CTFontCreateWithName("Menlo" as CFString, 14, nil)
	private let textInset = CGPoint(x: 8, y: 6)
	public private(set) var topLineIndex = 0
	public private(set) var xOffset: CGFloat = 0
	public var lineHeight: CGFloat = 17 {
		didSet { markDirty() }
	}
	public var lineCount: Int = 0 {
		didSet {
			topLineIndex = min(topLineIndex, max(0, lineCount - 1))
			markDirty()
		}
	}
	public var editor = Editor() {
		didSet { syncEditorState() }
	}

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

	public override func makeBackingLayer() -> CALayer {
		let layer = CAMetalLayer()
		layer.device = metalDevice
		layer.pixelFormat = .bgra8Unorm
		layer.framebufferOnly = true
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
		markDirty()
	}

	public override func updateLayer() {
		_ = consumeDirtyForDisplayLink()
		renderClearColor()
	}

	deinit {
		stopDisplayLink()
		stopCursorBlinkTimer()
	}

	public func markDirty() {
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

	public func setCursor(x: CGFloat, y: CGFloat, height: CGFloat) {
		cursorRect = CGRect(x: x, y: y, width: 2, height: height)
		cursorBlinkVisible = true
		markDirty()
	}

	public func clearCursor() {
		cursorRect = nil
		markDirty()
	}

	public func setSelectionRects(_ rects: [CGRect]) {
		selectionRects = rects
		markDirty()
	}

	public var visibleLineRange: Range<Int> {
		guard lineCount > 0 else {
			return 0 ..< 0
		}
		let visibleCount = max(1, Int(ceil(bounds.height / max(lineHeight, 1))) + 1)
		let end = min(lineCount, topLineIndex + visibleCount)
		return topLineIndex ..< end
	}

	public func scroll(deltaX: CGFloat, deltaY: CGFloat) {
		if deltaY != 0, lineCount > 0 {
			let lineDelta = Int((deltaY / max(lineHeight, 1)).rounded(.toNearestOrAwayFromZero))
			topLineIndex = min(max(topLineIndex + lineDelta, 0), max(0, lineCount - 1))
		}
		if deltaX != 0 {
			xOffset = max(0, xOffset + deltaX)
		}
		markDirty()
	}

	public override func scrollWheel(with event: NSEvent) {
		scroll(deltaX: event.scrollingDeltaX, deltaY: event.scrollingDeltaY)
	}

	public override func keyDown(with event: NSEvent) {
		if !event.modifierFlags.intersection([.command, .control]).isEmpty {
			super.keyDown(with: event)
			return
		}
		interpretKeyEvents([event])
	}

	@discardableResult
	func handleKey(
		characters: String?,
		charactersIgnoringModifiers: String?,
		keyCode: UInt16,
		modifierFlags: NSEvent.ModifierFlags = []
	) -> Bool {
		if !modifierFlags.intersection([.command, .control]).isEmpty {
			return false
		}
		switch keyCode {
		case 51:
			editor.deleteBackward()
		case 117:
			editor.deleteForward()
		case 123:
			editor.moveCursor(.charBackward)
		case 124:
			editor.moveCursor(.charForward)
		default:
			guard let characters, !characters.isEmpty, charactersIgnoringModifiers != "\u{1b}" else {
				return false
			}
			editor.insert(characters)
		}
		syncEditorState()
		return true
	}

	func solidOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		selectionOverlayInstances(scale: scale) + cursorOverlayInstances(scale: scale)
	}

	func textGlyphInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		guard let atlas = makeGlyphAtlas(), let shaper = makeLineShaper() else {
			return []
		}
		var instances: [MetalGlyphInstance] = []
		for lineIndex in visibleLineRange {
			let range = editor.rope.lineRange(lineIndex)
			let line = editor.rope.slice(range)
			guard let glyphs = try? shaper.shape(line, font: textFont) else {
				continue
			}
			let y = textInset.y + CGFloat(lineIndex - topLineIndex) * lineHeight
			for glyph in glyphs {
				guard let entry = try? atlas.entry(for: glyph.glyphID, font: textFont) else {
					continue
				}
				instances.append(MetalGlyphInstance(
					screenOrigin: SIMD2<Float>(
						Float((textInset.x + glyph.x + entry.bounds.origin.x - xOffset) * scale),
						Float((y - entry.bounds.origin.y) * scale)
					),
					size: SIMD2<Float>(Float(CGFloat(entry.width) * scale), Float(CGFloat(entry.height) * scale)),
					atlasUV: SIMD4<Float>(
						Float(glyph.atlasUV.u0),
						Float(glyph.atlasUV.v0),
						Float(glyph.atlasUV.u1),
						Float(glyph.atlasUV.v1)
					),
					color: SIMD4<Float>(0.86, 0.88, 0.90, 1.0)
				))
			}
		}
		return instances
	}

	private func selectionOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		selectionRects.map { rect in
			solidInstance(rect: rect, scale: scale, color: SIMD4<Float>(0.25, 0.45, 0.95, 0.35))
		}
	}

	private func cursorOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		if cursorBlinkVisible, let cursorRect {
			return [solidInstance(rect: cursorRect, scale: scale, color: SIMD4<Float>(0.92, 0.94, 0.96, 1.0))]
		}
		return []
	}

	private func updateDrawableSize() {
		guard let layer = layer as? CAMetalLayer else {
			return
		}
		let scale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1
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
		let pass = MTLRenderPassDescriptor()
		pass.colorAttachments[0].texture = drawable.texture
		pass.colorAttachments[0].loadAction = .clear
		pass.colorAttachments[0].storeAction = .store
		pass.colorAttachments[0].clearColor = clearColor
		guard
			let commandBuffer = commandQueue.makeCommandBuffer(),
			let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass)
		else {
			return
		}
		let scale = layer.contentsScale
		renderSolidInstances(selectionOverlayInstances(scale: scale), encoder: encoder, drawableSize: layer.drawableSize)
		renderText(encoder: encoder, drawableSize: layer.drawableSize, scale: scale)
		renderSolidInstances(cursorOverlayInstances(scale: scale), encoder: encoder, drawableSize: layer.drawableSize)
		encoder.endEncoding()
		commandBuffer.present(drawable)
		commandBuffer.commit()
		renderedFrameCount += 1
	}

	private func startDisplayLink() {
		guard displayLink == nil else {
			return
		}
		var link: CVDisplayLink?
		guard CVDisplayLinkCreateWithActiveCGDisplays(&link) == kCVReturnSuccess, let link else {
			return
		}
		CVDisplayLinkSetOutputCallback(link, metalTextViewDisplayLinkCallback, Unmanaged.passUnretained(self).toOpaque())
		guard CVDisplayLinkStart(link) == kCVReturnSuccess else {
			CVDisplayLinkSetOutputCallback(link, nil, nil)
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
		var fragment = MetalFragmentUniforms(useAtlas: 0)
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

	private func renderText(encoder: MTLRenderCommandEncoder, drawableSize: CGSize, scale: CGFloat) {
		let instances = textGlyphInstances(scale: scale)
		guard !instances.isEmpty, let pipeline = makeRenderPipeline(), let sampler = makeSampler(), let atlas = makeGlyphAtlas() else {
			return
		}
		var viewport = MetalViewportUniforms(size: SIMD2<Float>(Float(drawableSize.width), Float(drawableSize.height)))
		var fragment = MetalFragmentUniforms(useAtlas: 1)
		instances.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				return
			}
			encoder.setRenderPipelineState(pipeline)
			setInstanceData(base: base, length: bytes.count, encoder: encoder)
			encoder.setVertexBytes(&viewport, length: MemoryLayout<MetalViewportUniforms>.stride, index: 1)
			encoder.setFragmentTexture(atlas.texture, index: 0)
			encoder.setFragmentSamplerState(sampler, index: 0)
			encoder.setFragmentBytes(&fragment, length: MemoryLayout<MetalFragmentUniforms>.stride, index: 0)
			encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: instances.count)
		}
	}

	private func setInstanceData(base: UnsafeRawPointer, length: Int, encoder: MTLRenderCommandEncoder) {
		if length <= 4_096 {
			encoder.setVertexBytes(base, length: length, index: 0)
			return
		}
		guard let buffer = metalDevice?.makeBuffer(bytes: base, length: length) else {
			return
		}
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

	private func makeGlyphAtlas() -> GlyphAtlas? {
		if let glyphAtlas {
			return glyphAtlas
		}
		guard let metalDevice, let atlas = try? GlyphAtlas(device: metalDevice) else {
			return nil
		}
		glyphAtlas = atlas
		return atlas
	}

	private func makeLineShaper() -> LineShaper? {
		if let lineShaper {
			return lineShaper
		}
		guard let atlas = makeGlyphAtlas() else {
			return nil
		}
		let shaper = LineShaper(atlas: atlas)
		lineShaper = shaper
		return shaper
	}

	private func syncEditorState() {
		lineCount = editor.rope.lineCount
		let head = editor.selections.primary.head
		let line = editor.rope.line(forOffset: head)
		let lineRange = editor.rope.lineRange(line)
		let prefix = editor.rope.slice(lineRange.lowerBound ..< min(head, lineRange.upperBound))
		let cursorX = textInset.x + typographicWidth(prefix) - xOffset
		let cursorY = textInset.y + CGFloat(line - topLineIndex) * lineHeight
		setCursor(x: cursorX, y: cursorY, height: lineHeight)
		setSelectionRects(selectionRects(for: editor.selections))
		markDirty()
	}

	private func selectionRects(for selections: SelectionSet) -> [CGRect] {
		([selections.primary] + selections.secondaries).flatMap { selection -> [CGRect] in
			let range = selection.range
			guard !range.isEmpty else {
				return []
			}
			let startLine = editor.rope.line(forOffset: range.lowerBound)
			let endLine = editor.rope.line(forOffset: range.upperBound)
			return (startLine ... endLine).compactMap { line -> CGRect? in
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
					y: textInset.y + CGFloat(line - topLineIndex) * lineHeight,
					width: max(2, typographicWidth(selected)),
					height: lineHeight
				)
			}
		}
	}

	private func typographicWidth(_ text: String) -> CGFloat {
		guard !text.isEmpty else {
			return 0
		}
		let attributed = NSAttributedString(string: text, attributes: [kCTFontAttributeName as NSAttributedString.Key: textFont])
		return CTLineGetTypographicBounds(CTLineCreateWithAttributedString(attributed), nil, nil, nil)
	}
}

extension MetalTextView: NSTextInputClient {
	public func insertText(_ string: Any, replacementRange: NSRange) {
		let text = plainString(from: string)
		let range = replacementUTF8Range(replacementRange) ?? markedRangeUTF8 ?? editor.selections.primary.range
		replace(range: range, with: text)
		markedRangeUTF8 = nil
		syncEditorState()
	}

	public override func doCommand(by selector: Selector) {
		switch selector {
		case #selector(NSResponder.deleteBackward(_:)):
			editor.deleteBackward()
		case #selector(NSResponder.deleteForward(_:)):
			editor.deleteForward()
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
		default:
			_ = tryToPerform(selector, with: nil)
		}
		markedRangeUTF8 = nil
		syncEditorState()
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
