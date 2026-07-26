@testable import ItsyApp
import Foundation
import Testing

@MainActor @Test func applicationEventBusRoutesOnlyMatchingTypedContracts() {
	let bus = ApplicationEventBus()
	var workspaceRoots: [[URL]] = []
	var commands: [String] = []
	let workspaceSubscription = bus.subscribe(ApplicationEvents.WorkspaceRootsChanged.self) {
		workspaceRoots.append($0.roots)
	}
	let commandSubscription = bus.subscribe(ApplicationEvents.CommandRequested.self) {
		commands.append($0.identifier)
	}

	let root = URL(fileURLWithPath: "/tmp/itsy-events")
	bus.publish(ApplicationEvents.WorkspaceRootsChanged(roots: [root]))
	bus.publish(ApplicationEvents.CommandRequested(identifier: "file.open"))

	#expect(workspaceRoots == [[root]])
	#expect(commands == ["file.open"])
	#expect(bus.observerCount(for: ApplicationEvents.WorkspaceRootsChanged.self) == 1)
	#expect(bus.observerCount(for: ApplicationEvents.CommandRequested.self) == 1)
	_ = commandSubscription
	workspaceSubscription.cancel()
	bus.publish(ApplicationEvents.WorkspaceRootsChanged(roots: []))
	#expect(workspaceRoots == [[root]])
	#expect(bus.observerCount(for: ApplicationEvents.WorkspaceRootsChanged.self) == 0)
}

@MainActor @Test func applicationEventBusPublishesFromStableObserverSnapshots() {
	let bus = ApplicationEventBus()
	var events: [String] = []
	var second: ApplicationEventSubscription?
	let first = bus.subscribe(ApplicationEvents.CommandRequested.self) { _ in
		events.append("first")
		second?.cancel()
	}
	second = bus.subscribe(ApplicationEvents.CommandRequested.self) { _ in
		events.append("second")
	}

	bus.publish(ApplicationEvents.CommandRequested(identifier: "test"))
	#expect(events.count == 2)
	bus.publish(ApplicationEvents.CommandRequested(identifier: "test"))
	#expect(events.count == 3)
	_ = first
}
