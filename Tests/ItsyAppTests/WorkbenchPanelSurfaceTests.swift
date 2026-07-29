@testable import ItsyApp
import AppKit
import ItsyWorkbenchLayout
import Testing

@MainActor
@Test func utilityPanelSurfacesRetainMountedViewsAcrossVisibilityChanges() throws {
	for id in [WorkbenchComponentID.problems, .outline, .references, .tasks, .undoTree] {
		let surface = WorkbenchPanelSurface(id: id, title: id.rawValue, size: NSSize(width: 360, height: 240))
		let hostedView = try #require(surface.panel.contentView?.subviews.first)
		#expect(surface.lifecycle == .hidden)
		#expect(hostedView === surface.contentView)

		surface.show()
		#expect(surface.lifecycle == .visible)

		surface.panel.close()
		#expect(surface.lifecycle == .hidden)
		#expect(surface.panel.contentView?.subviews.first === hostedView)
	}
}
