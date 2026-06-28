import AppKit
import CoreVideo
import Metal
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

	public override init(frame frameRect: NSRect) {
		let device = MTLCreateSystemDefaultDevice()
		metalDevice = device
		commandQueue = device?.makeCommandQueue()
		super.init(frame: frameRect)
		wantsLayer = true
	}

	public required init?(coder: NSCoder) {
		let device = MTLCreateSystemDefaultDevice()
		metalDevice = device
		commandQueue = device?.makeCommandQueue()
		super.init(coder: coder)
		wantsLayer = true
	}

	public override var wantsUpdateLayer: Bool {
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

	func solidOverlayInstances(scale: CGFloat) -> [MetalGlyphInstance] {
		var instances = selectionRects.map { rect in
			solidInstance(rect: rect, scale: scale, color: SIMD4<Float>(0.25, 0.45, 0.95, 0.35))
		}
		if cursorBlinkVisible, let cursorRect {
			instances.append(solidInstance(rect: cursorRect, scale: scale, color: SIMD4<Float>(0.92, 0.94, 0.96, 1.0)))
		}
		return instances
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
		renderSolidOverlays(encoder: encoder, drawableSize: layer.drawableSize)
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

	private func renderSolidOverlays(encoder: MTLRenderCommandEncoder, drawableSize: CGSize) {
		let scale = layer?.contentsScale ?? 1
		let instances = solidOverlayInstances(scale: scale)
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
			encoder.setVertexBytes(base, length: bytes.count, index: 0)
			encoder.setVertexBytes(&viewport, length: MemoryLayout<MetalViewportUniforms>.stride, index: 1)
			encoder.setFragmentTexture(atlas, index: 0)
			encoder.setFragmentSamplerState(sampler, index: 0)
			encoder.setFragmentBytes(&fragment, length: MemoryLayout<MetalFragmentUniforms>.stride, index: 0)
			encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: instances.count)
		}
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
