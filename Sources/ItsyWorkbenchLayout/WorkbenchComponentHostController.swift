import AppKit

@MainActor public final class WorkbenchComponentHostController: WorkbenchComponentHost {
	private let mountPoints: [WorkbenchComponentID: NSView]
	private var components: [WorkbenchComponentID: any WorkbenchComponent] = [:]
	private var mountedViews: [WorkbenchComponentID: NSView] = [:]
	private var visibility: [WorkbenchComponentID: Bool] = [:]

	public init(mountPoints: [WorkbenchComponentID: NSView]) {
		self.mountPoints = mountPoints
	}

	@discardableResult public func mount(_ component: any WorkbenchComponent) -> Bool {
		guard mountPoints[component.id] != nil else {
			return false
		}
		if let existing = components[component.id], existing !== component {
			return false
		}
		if component.isMounted {
			return true
		}
		components[component.id] = component
		let shouldBeVisible = isVisible(component.id)
		component.mount(in: self)
		guard component.isMounted, mountedViews[component.id] != nil else {
			detach(component: component.id)
			components[component.id] = nil
			return false
		}
		if shouldBeVisible {
			component.setVisible(true)
		}
		return true
	}

	@discardableResult public func unmount(_ component: WorkbenchComponentID) -> Bool {
		guard let component = components[component], component.isMounted else {
			return false
		}
		component.unmount()
		return !component.isMounted
	}

	public func component(for id: WorkbenchComponentID) -> (any WorkbenchComponent)? {
		components[id]
	}

	public func lifecycle(for id: WorkbenchComponentID) -> WorkbenchComponentLifecycle {
		guard mountedViews[id] != nil else {
			return .unmounted
		}
		return isVisible(id) ? .visible : .hidden
	}

	public func attach(_ view: NSView, for component: WorkbenchComponentID) {
		guard let mountPoint = mountPoints[component] else {
			return
		}
		if view.superview !== mountPoint {
			view.removeFromSuperview()
			mountPoint.addSubview(view)
		}
		view.frame = mountPoint.bounds
		view.autoresizingMask = [.width, .height]
		view.isHidden = !isVisible(component)
		mountedViews[component] = view
	}

	public func setVisible(_ visible: Bool, for component: WorkbenchComponentID) {
		visibility[component] = visible
		mountedViews[component]?.isHidden = !visible
	}

	public func detach(component: WorkbenchComponentID) {
		guard let view = mountedViews.removeValue(forKey: component) else {
			return
		}
		visibility[component] = !view.isHidden
		view.removeFromSuperview()
	}

	private func isVisible(_ component: WorkbenchComponentID) -> Bool {
		visibility[component] ?? (WorkbenchComponents.firstParty.descriptor(for: component)?.defaultLifecycle == .visible)
	}
}
