import AppKit
import ItsyWorkbenchLayout

@MainActor final class WorkbenchPanelSurface: NSObject, NSWindowDelegate {
	let panel: NSPanel
	let contentView: NSView
	private let host: WorkbenchComponentHostController
	private let component: ViewWorkbenchComponent

	init(id: WorkbenchComponentID, title: String, size: NSSize) {
		let panel = NSPanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		let mountPoint = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		self.panel = panel
		self.contentView = contentView
		host = WorkbenchComponentHostController(mountPoints: [id: mountPoint])
		component = ViewWorkbenchComponent(id: id, view: contentView)
		super.init()
		panel.title = title
		panel.isReleasedWhenClosed = false
		panel.contentView = mountPoint
		panel.delegate = self
		precondition(host.mount(component))
		component.setVisible(false)
	}

	var isVisible: Bool {
		panel.isVisible
	}

	var lifecycle: WorkbenchComponentLifecycle {
		host.lifecycle(for: component.id)
	}

	func show() {
		component.setVisible(true)
		panel.makeKeyAndOrderFront(nil)
	}

	func close() {
		component.setVisible(false)
		panel.close()
	}

	func windowWillClose(_: Notification) {
		component.setVisible(false)
	}
}
