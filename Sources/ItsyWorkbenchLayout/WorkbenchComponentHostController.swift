import AppKit

public enum WorkbenchComponentFailureReason: String, Equatable, Sendable {
	case missingMountPoint
	case replacementInstance
	case mountDidNotAttach
}

public struct WorkbenchComponentFailure: Equatable, Sendable {
	public let component: WorkbenchComponentID
	public let reason: WorkbenchComponentFailureReason

	public init(component: WorkbenchComponentID, reason: WorkbenchComponentFailureReason) {
		self.component = component
		self.reason = reason
	}
}

@MainActor public final class WorkbenchComponentHostController: WorkbenchComponentHost {
	private let mountPoints: [WorkbenchComponentID: NSView]
	private var components: [WorkbenchComponentID: any WorkbenchComponent] = [:]
	private var mountedViews: [WorkbenchComponentID: NSView] = [:]
	private var visibility: [WorkbenchComponentID: Bool] = [:]
	private var failures: [WorkbenchComponentID: WorkbenchComponentFailure] = [:]
	private var failedComponents: [WorkbenchComponentID: any WorkbenchComponent] = [:]
	private var recoveryViews: [WorkbenchComponentID: NSView] = [:]

	public init(mountPoints: [WorkbenchComponentID: NSView]) {
		self.mountPoints = mountPoints
	}

	@discardableResult public func mount(_ component: any WorkbenchComponent) -> Bool {
		guard mountPoints[component.id] != nil else {
			recordFailure(component, reason: .missingMountPoint, presentsRecovery: false)
			return false
		}
		if let existing = components[component.id], existing !== component {
			recordFailure(component, reason: .replacementInstance, presentsRecovery: false)
			return false
		}
		if component.isMounted {
			clearFailure(for: component.id)
			return true
		}
		clearFailurePresentation(for: component.id)
		components[component.id] = component
		let shouldBeVisible = isVisible(component.id)
		component.mount(in: self)
		guard component.isMounted, mountedViews[component.id] != nil else {
			detach(component: component.id)
			components[component.id] = nil
			failedComponents[component.id] = component
			recordFailure(component, reason: .mountDidNotAttach, presentsRecovery: true)
			return false
		}
		clearFailure(for: component.id)
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

	public func failure(for id: WorkbenchComponentID) -> WorkbenchComponentFailure? {
		failures[id]
	}

	public func recoveryView(for id: WorkbenchComponentID) -> NSView? {
		recoveryViews[id]
	}

	@discardableResult public func retry(_ id: WorkbenchComponentID) -> Bool {
		guard let component = failedComponents[id] else {
			return false
		}
		return mount(component)
	}

	public func dismissFailure(for id: WorkbenchComponentID) {
		failures[id] = nil
		failedComponents[id] = nil
		clearFailurePresentation(for: id)
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

	private func recordFailure(
		_ component: any WorkbenchComponent,
		reason: WorkbenchComponentFailureReason,
		presentsRecovery: Bool
	) {
		let failure = WorkbenchComponentFailure(component: component.id, reason: reason)
		failures[component.id] = failure
		guard presentsRecovery, let mountPoint = mountPoints[component.id] else {
			return
		}
		clearFailurePresentation(for: component.id)
		let displayName = WorkbenchComponents.firstParty.descriptor(for: component.id)?.displayName ?? component.id.rawValue
		let recoveryView = WorkbenchComponentRecoveryView(
			title: "\(displayName) unavailable",
			message: failureMessage(for: reason),
			retry: { [weak self] in
				_ = self?.retry(component.id)
			}
		)
		recoveryView.frame = mountPoint.bounds
		recoveryView.autoresizingMask = [.width, .height]
		recoveryView.isHidden = !isVisible(component.id)
		mountPoint.addSubview(recoveryView)
		recoveryViews[component.id] = recoveryView
	}

	private func clearFailure(for id: WorkbenchComponentID) {
		failures[id] = nil
		failedComponents[id] = nil
		clearFailurePresentation(for: id)
	}

	private func clearFailurePresentation(for id: WorkbenchComponentID) {
		recoveryViews.removeValue(forKey: id)?.removeFromSuperview()
	}

	private func failureMessage(for reason: WorkbenchComponentFailureReason) -> String {
		switch reason {
		case .missingMountPoint:
			"No mount point is registered for this component."
		case .replacementInstance:
			"A different component instance already owns this surface."
		case .mountDidNotAttach:
			"The component did not attach its view."
		}
	}
}

@MainActor private final class WorkbenchComponentRecoveryView: NSView {
	private let retry: () -> Void

	init(title: String, message: String, retry: @escaping () -> Void) {
		self.retry = retry
		super.init(frame: .zero)
		setAccessibilityLabel(title)
		let titleLabel = NSTextField(labelWithString: title)
		titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
		let messageLabel = NSTextField(wrappingLabelWithString: message)
		messageLabel.textColor = .secondaryLabelColor
		let retryButton = NSButton(title: "Retry", target: self, action: #selector(retryComponent(_:)))
		retryButton.setAccessibilityLabel("Retry \(title)")
		let stack = NSStackView(views: [titleLabel, messageLabel, retryButton])
		stack.orientation = .vertical
		stack.alignment = .leading
		stack.spacing = 8
		stack.translatesAutoresizingMaskIntoConstraints = false
		addSubview(stack)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
			stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16),
			stack.centerYAnchor.constraint(equalTo: centerYAnchor),
		])
	}

	required init?(coder: NSCoder) {
		fatalError("init(coder:) has not been implemented")
	}

	@objc private func retryComponent(_: Any?) {
		retry()
	}
}
