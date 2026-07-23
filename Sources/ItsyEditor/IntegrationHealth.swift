import Foundation

public enum IntegrationService: String, CaseIterable, Codable, Equatable, Hashable, Sendable {
	case lsp
	case dap
	case task
	case terminal
	case git
	case gitHub = "github"
	case fileWatch = "file-watch"
	case package
}

public enum IntegrationHealthState: String, CaseIterable, Codable, Equatable, Sendable {
	case healthy
	case degraded
	case unavailable
	case retrying
}

public enum IntegrationLifecycle: String, CaseIterable, Codable, Equatable, Sendable {
	case inactive
	case starting
	case running
	case stopping
	case stopped
}

public struct IntegrationHealthKey: Codable, Equatable, Hashable, Sendable {
	public var service: IntegrationService
	public var identifier: String

	public init(service: IntegrationService, identifier: String = "default") {
		self.service = service
		self.identifier = identifier
	}
}

public struct IntegrationHealthRecord: Codable, Equatable, Identifiable, Sendable {
	public var key: IntegrationHealthKey
	public var lifecycle: IntegrationLifecycle
	public var state: IntegrationHealthState
	public var lastError: String?
	public var remediation: String?
	public var detailLogReference: String?
	public var updatedAt: Date

	public var id: String {
		"\(key.service.rawValue):\(key.identifier)"
	}

	public init(
		key: IntegrationHealthKey,
		lifecycle: IntegrationLifecycle,
		state: IntegrationHealthState,
		lastError: String? = nil,
		remediation: String? = nil,
		detailLogReference: String? = nil,
		updatedAt: Date = .init()
	) {
		self.key = key
		self.lifecycle = lifecycle
		self.state = state
		self.lastError = lastError
		self.remediation = remediation
		self.detailLogReference = detailLogReference
		self.updatedAt = updatedAt
	}
}

public actor IntegrationHealthStore {
	public static let shared = IntegrationHealthStore()

	private var records: [IntegrationHealthKey: IntegrationHealthRecord] = [:]

	public init() {}

	public func report(
		service: IntegrationService,
		identifier: String = "default",
		lifecycle: IntegrationLifecycle,
		state: IntegrationHealthState,
		lastError: String? = nil,
		remediation: String? = nil,
		detailLogReference: String? = nil,
		updatedAt: Date = .init()
	) {
		let key = IntegrationHealthKey(service: service, identifier: identifier)
		records[key] = IntegrationHealthRecord(
			key: key,
			lifecycle: lifecycle,
			state: state,
			lastError: lastError,
			remediation: remediation,
			detailLogReference: detailLogReference,
			updatedAt: updatedAt
		)
	}

	public func record(for key: IntegrationHealthKey) -> IntegrationHealthRecord? {
		records[key]
	}

	public func allRecords() -> [IntegrationHealthRecord] {
		records.values.sorted {
			($0.key.service.rawValue, $0.key.identifier) < ($1.key.service.rawValue, $1.key.identifier)
		}
	}

	public func remove(_ key: IntegrationHealthKey) {
		records.removeValue(forKey: key)
	}

	public func removeAll() {
		records.removeAll()
	}
}
