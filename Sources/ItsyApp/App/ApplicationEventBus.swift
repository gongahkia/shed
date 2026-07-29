import Foundation
import ItsyConfig

protocol ApplicationEvent {}

enum ApplicationEvents {
	struct SettingsApplied: ApplicationEvent {
		let settings: ItsySettings
	}

	struct WorkspaceRootsChanged: ApplicationEvent, Equatable {
		let roots: [URL]
	}

	struct ActiveDocumentChanged: ApplicationEvent, Equatable {
		let url: URL?
	}

	struct CommandRequested: ApplicationEvent, Equatable {
		let identifier: String
	}
}

@MainActor final class ApplicationEventSubscription {
	private weak var bus: ApplicationEventBus?
	private let eventType: ObjectIdentifier
	private let id: UUID

	init(bus: ApplicationEventBus, eventType: ObjectIdentifier, id: UUID) {
		self.bus = bus
		self.eventType = eventType
		self.id = id
	}

	func cancel() {
		bus?.removeObserver(id, for: eventType)
		bus = nil
	}
}

@MainActor final class ApplicationEventBus {
	private var handlers: [ObjectIdentifier: [UUID: (Any) -> Void]] = [:]

	func subscribe<Event: ApplicationEvent>(
		_: Event.Type,
		handler: @escaping (Event) -> Void
	) -> ApplicationEventSubscription {
		let eventType = ObjectIdentifier(Event.self)
		let id = UUID()
		handlers[eventType, default: [:]][id] = { event in
			guard let event = event as? Event else {
				return
			}
			handler(event)
		}
		return ApplicationEventSubscription(bus: self, eventType: eventType, id: id)
	}

	func publish<Event: ApplicationEvent>(_ event: Event) {
		let eventType = ObjectIdentifier(Event.self)
		let currentHandlers = handlers[eventType].map { Array($0.values) } ?? []
		for handler in currentHandlers {
			handler(event)
		}
	}

	func observerCount<Event: ApplicationEvent>(for _: Event.Type) -> Int {
		handlers[ObjectIdentifier(Event.self)]?.count ?? 0
	}

	fileprivate func removeObserver(_ id: UUID, for eventType: ObjectIdentifier) {
		handlers[eventType]?[id] = nil
		if handlers[eventType]?.isEmpty == true {
			handlers[eventType] = nil
		}
	}
}
