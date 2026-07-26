@testable import ItsyWorkbenchLayout
import AppKit
import Testing

@MainActor @Test func componentHostPreservesInstanceStateAndVisibilityAcrossRemount() {
	let mountPoint = NSView(frame: .init(x: 0, y: 0, width: 640, height: 240))
	let host = WorkbenchComponentHostController(mountPoints: [.terminal: mountPoint])
	let component = StatefulWorkbenchComponent(id: .terminal)

	#expect(host.mount(component))
	#expect(component.lifecycle == .hidden)
	component.setVisible(true)
	component.state = "active session"
	#expect(host.lifecycle(for: .terminal) == .visible)
	#expect(host.unmount(.terminal))
	#expect(host.lifecycle(for: .terminal) == .unmounted)

	#expect(host.mount(component))
	#expect(component.lifecycle == .visible)
	#expect(component.state == "active session")
	#expect(component.view.superview === mountPoint)
}

@MainActor @Test func componentHostRejectsUnknownMountPointsAndReplacementInstances() {
	let host = WorkbenchComponentHostController(mountPoints: [.terminal: NSView()])
	let terminal = StatefulWorkbenchComponent(id: .terminal)
	let replacement = StatefulWorkbenchComponent(id: .terminal)
	let git = StatefulWorkbenchComponent(id: .git)
	let failed = FailingWorkbenchComponent(id: .terminal)

	#expect(!host.mount(git))
	#expect(!git.isMounted)
	#expect(!host.mount(failed))
	#expect(host.component(for: .terminal) == nil)
	#expect(host.mount(terminal))
	#expect(host.unmount(.terminal))
	#expect(!host.mount(replacement))
	#expect(host.component(for: .terminal) === terminal)
}

@MainActor private final class StatefulWorkbenchComponent: WorkbenchComponent {
	let id: WorkbenchComponentID
	let view = NSView()
	private(set) var lifecycle: WorkbenchComponentLifecycle = .unmounted
	private weak var host: (any WorkbenchComponentHost)?
	var state = ""

	init(id: WorkbenchComponentID) {
		self.id = id
	}

	func mount(in host: any WorkbenchComponentHost) {
		self.host = host
		host.attach(view, for: id)
		lifecycle = .hidden
	}

	func setVisible(_ visible: Bool) {
		host?.setVisible(visible, for: id)
		lifecycle = visible ? .visible : .hidden
	}

	func unmount() {
		host?.detach(component: id)
		host = nil
		lifecycle = .unmounted
	}
}

@MainActor private final class FailingWorkbenchComponent: WorkbenchComponent {
	let id: WorkbenchComponentID
	let view = NSView()
	let lifecycle: WorkbenchComponentLifecycle = .unmounted

	init(id: WorkbenchComponentID) {
		self.id = id
	}

	func mount(in _: any WorkbenchComponentHost) {}
	func setVisible(_: Bool) {}
	func unmount() {}
}
