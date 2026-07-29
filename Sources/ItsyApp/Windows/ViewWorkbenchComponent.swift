import AppKit
import ItsyWorkbenchLayout

@MainActor final class ViewWorkbenchComponent: WorkbenchComponent {
	let id: WorkbenchComponentID
	let view: NSView
	private(set) var lifecycle: WorkbenchComponentLifecycle = .unmounted
	private weak var host: (any WorkbenchComponentHost)?

	init(id: WorkbenchComponentID, view: NSView) {
		self.id = id
		self.view = view
	}

	func mount(in host: any WorkbenchComponentHost) {
		self.host = host
		host.attach(view, for: id)
		lifecycle = .hidden
	}

	func setVisible(_ visible: Bool) {
		guard let host else {
			return
		}
		host.setVisible(visible, for: id)
		lifecycle = visible ? .visible : .hidden
	}

	func unmount() {
		host?.detach(component: id)
		host = nil
		lifecycle = .unmounted
	}
}
