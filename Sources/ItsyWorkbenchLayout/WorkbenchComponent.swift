import AppKit

public enum WorkbenchComponentLifecycle: String, CaseIterable, Codable, Equatable, Sendable {
	case unmounted
	case hidden
	case visible
}

@MainActor public protocol WorkbenchComponent: AnyObject {
	var id: WorkbenchComponentID { get }
	var view: NSView { get }
	var lifecycle: WorkbenchComponentLifecycle { get }
	func mount(in host: any WorkbenchComponentHost)
	func setVisible(_ visible: Bool)
	func unmount()
}

public extension WorkbenchComponent {
	var isMounted: Bool { lifecycle != .unmounted }
	var isVisible: Bool { lifecycle == .visible }
}

@MainActor public protocol WorkbenchComponentHost: AnyObject {
	func attach(_ view: NSView, for component: WorkbenchComponentID)
	func setVisible(_ visible: Bool, for component: WorkbenchComponentID)
	func detach(component: WorkbenchComponentID)
}
