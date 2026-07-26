@testable import ItsyWorkbenchLayout
import AppKit
import Testing

@MainActor @Test func workbenchComponentLifecycleContractRoutesHostOperations() {
	let host = WorkbenchComponentHostSpy()
	let component = WorkbenchComponentSpy(id: .terminal)

	component.mount(in: host)
	#expect(component.isMounted)
	#expect(!component.isVisible)
	#expect(host.operations == [.attach(.terminal)])

	component.setVisible(true)
	#expect(component.isVisible)
	#expect(host.operations == [.attach(.terminal), .visibility(.terminal, true)])

	component.unmount()
	#expect(!component.isMounted)
	#expect(host.operations == [.attach(.terminal), .visibility(.terminal, true), .detach(.terminal)])
}

@MainActor private final class WorkbenchComponentSpy: WorkbenchComponent {
	let id: WorkbenchComponentID
	let view = NSView()
	private(set) var lifecycle: WorkbenchComponentLifecycle = .unmounted
	private weak var host: (any WorkbenchComponentHost)?

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

@MainActor private final class WorkbenchComponentHostSpy: WorkbenchComponentHost {
	enum Operation: Equatable {
		case attach(WorkbenchComponentID)
		case visibility(WorkbenchComponentID, Bool)
		case detach(WorkbenchComponentID)
	}

	private(set) var operations: [Operation] = []

	func attach(_: NSView, for component: WorkbenchComponentID) {
		operations.append(.attach(component))
	}

	func setVisible(_ visible: Bool, for component: WorkbenchComponentID) {
		operations.append(.visibility(component, visible))
	}

	func detach(component: WorkbenchComponentID) {
		operations.append(.detach(component))
	}
}
