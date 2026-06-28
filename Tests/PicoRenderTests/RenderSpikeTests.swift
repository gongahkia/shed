import Dispatch
import Foundation
import os
@testable import PicoRender
import Testing

@Test func renderSpikeScrollsTenMillionLineBuffer() {
	guard ProcessInfo.processInfo.environment["PICO_RENDER_SPIKE"] == "1" else {
		return
	}
	let buffer = String(repeating: "x\n", count: 10_000_000)
	let view = MetalTextView(frame: .init(x: 0, y: 0, width: 1440, height: 900))
	view.lineHeight = 18
	view.lineCount = buffer.reduce(0) { count, char in char == "\n" ? count + 1 : count }
	let pageDelta = view.bounds.height
	let frames = 600
	let log = OSLog(subsystem: "dev.pico.editor.tests", category: "RenderSpike")
	let signpostID = OSSignpostID(log: log)
	os_signpost(.begin, log: log, name: "scroll-10m-lines", signpostID: signpostID)
	let start = DispatchTime.now().uptimeNanoseconds
	for _ in 0 ..< frames {
		view.scroll(deltaX: 0, deltaY: pageDelta)
		_ = view.visibleLineRange
	}
	let end = DispatchTime.now().uptimeNanoseconds
	os_signpost(.end, log: log, name: "scroll-10m-lines", signpostID: signpostID)
	let seconds = Double(end - start) / 1_000_000_000
	let fps = Double(frames) / seconds
	print("render_spike_fps=\(fps)")
	#expect(fps >= 60)
}
