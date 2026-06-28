@testable import PicoRender
import Testing

@Test func dirtyFlagPreventsIdleDisplayLinkDraws() {
	let view = MetalTextView(frame: .zero)
	#expect(view.consumeDirtyForDisplayLink())
	#expect(!view.consumeDirtyForDisplayLink())
	view.markDirty()
	#expect(view.consumeDirtyForDisplayLink())
	#expect(!view.consumeDirtyForDisplayLink())
}

@Test func cursorAndSelectionBuildSolidOverlayInstances() {
	let view = MetalTextView(frame: .zero)
	view.setSelectionRects([.init(x: 4, y: 8, width: 20, height: 10)])
	view.setCursor(x: 30, y: 8, height: 10)
	let instances = view.solidOverlayInstances(scale: 2)
	#expect(instances.count == 2)
	#expect(instances[0].size == SIMD2<Float>(40, 20))
	#expect(instances[1].screenOrigin == SIMD2<Float>(60, 16))
	#expect(instances[1].size == SIMD2<Float>(4, 20))
}
