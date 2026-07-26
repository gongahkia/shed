import AppKit
import ItsyWorkbenchLayout

@MainActor final class FileTreeWorkbenchComponent: WorkbenchComponent {
	let id: WorkbenchComponentID = .fileTree
	let controller: FileTreeSidebarController
	private(set) var lifecycle: WorkbenchComponentLifecycle = .unmounted
	private weak var host: (any WorkbenchComponentHost)?

	init(controller: FileTreeSidebarController) {
		self.controller = controller
	}

	var view: NSView { controller.view }

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
