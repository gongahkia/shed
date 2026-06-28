import AppKit
import Metal
import QuartzCore

public final class MetalTextView: NSView {
	public var clearColor = MTLClearColor(red: 0.08, green: 0.09, blue: 0.10, alpha: 1.0) {
		didSet { needsDisplay = true }
	}

	private let metalDevice: MTLDevice?
	private let commandQueue: MTLCommandQueue?

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
		needsDisplay = true
	}

	public override func setFrameSize(_ newSize: NSSize) {
		super.setFrameSize(newSize)
		updateDrawableSize()
		needsDisplay = true
	}

	public override func updateLayer() {
		renderClearColor()
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
		encoder.endEncoding()
		commandBuffer.present(drawable)
		commandBuffer.commit()
	}
}
