import Foundation

public enum IntegrationOutputKind: String, CaseIterable, Codable, Equatable, Sendable {
	case command
	case standardOutput = "stdout"
	case standardError = "stderr"
	case protocolOutput = "protocol"
	case event
}

public struct IntegrationOutputScope: Codable, Equatable, Hashable, Sendable {
	public var service: IntegrationService
	public var identifier: String

	public init(service: IntegrationService, identifier: String = "default") {
		self.service = service
		self.identifier = identifier
	}
}

public struct IntegrationOutputEntry: Codable, Equatable, Identifiable, Sendable {
	public var id: UUID
	public var timestamp: Date
	public var scope: IntegrationOutputScope
	public var kind: IntegrationOutputKind
	public var text: String
	public var errorReference: String?

	public init(
		id: UUID = UUID(),
		timestamp: Date = .init(),
		scope: IntegrationOutputScope,
		kind: IntegrationOutputKind,
		text: String,
		errorReference: String? = nil
	) {
		self.id = id
		self.timestamp = timestamp
		self.scope = scope
		self.kind = kind
		self.text = text
		self.errorReference = errorReference
	}
}

public enum IntegrationOutputConsoleError: Error, Equatable, Sendable {
	case invalidRedactionPattern(String)
}

public enum IntegrationOutputRedactor {
	public static func redact(
		_ text: String,
		environment: [String: String] = ProcessInfo.processInfo.environment,
		patterns: [String] = []
	) -> String {
		var redacted = LSPLogRedactor.redact(text, environment: environment)
		redacted = replacing(
			"(?i)(--(?:token|secret|password|api[_-]?key|authorization|cookie|credential)(?:=|\\s+))[^\\s]+",
			in: redacted,
			with: "$1<redacted>"
		)
		for pattern in [
			"\\bgh[pousr]_[A-Za-z0-9_]+\\b",
			"\\bgithub_pat_[A-Za-z0-9_]+\\b",
			"(?i)(https?://)[^/@\\s]+@",
			"(?im)^(\\s*(?:authorization|cookie|set-cookie|x-api-key)\\s*:\\s*)[^\\r\\n]+",
		] + patterns {
			redacted = replacing(pattern, in: redacted)
		}
		return redacted
	}

	public static func validate(patterns: [String]) throws {
		for pattern in patterns where (try? NSRegularExpression(pattern: pattern)) == nil {
			throw IntegrationOutputConsoleError.invalidRedactionPattern(pattern)
		}
	}

	private static func replacing(_ pattern: String, in text: String, with template: String = "<redacted>") -> String {
		guard let expression = try? NSRegularExpression(pattern: pattern) else {
			return text
		}
		return expression.stringByReplacingMatches(
			in: text,
			range: NSRange(text.startIndex..., in: text),
			withTemplate: template
		)
	}
}

public actor IntegrationOutputConsole {
	public static let shared = IntegrationOutputConsole()

	private let maximumEntries: Int
	private let maximumCharacters: Int
	private let environment: [String: String]
	private var redactionPatterns: [String]
	private var storedEntries: [IntegrationOutputEntry] = []
	private var storedCharacterCount = 0

	public init(
		maximumEntries: Int = 2_000,
		maximumCharacters: Int = 1_000_000,
		environment: [String: String] = ProcessInfo.processInfo.environment,
		redactionPatterns: [String] = []
	) {
		self.maximumEntries = max(1, maximumEntries)
		self.maximumCharacters = max(1, maximumCharacters)
		self.environment = environment
		self.redactionPatterns = redactionPatterns.filter { (try? NSRegularExpression(pattern: $0)) != nil }
	}

	public func configure(redactionPatterns: [String]) throws {
		try IntegrationOutputRedactor.validate(patterns: redactionPatterns)
		self.redactionPatterns = redactionPatterns
	}

	public func append(
		service: IntegrationService,
		identifier: String = "default",
		kind: IntegrationOutputKind,
		text: String,
		errorReference: String? = nil,
		timestamp: Date = .init()
	) {
		guard !text.isEmpty else {
			return
		}
		let entry = IntegrationOutputEntry(
			timestamp: timestamp,
			scope: IntegrationOutputScope(service: service, identifier: identifier),
			kind: kind,
			text: IntegrationOutputRedactor.redact(text, environment: environment, patterns: redactionPatterns),
			errorReference: errorReference.map { IntegrationOutputRedactor.redact($0, environment: environment, patterns: redactionPatterns) }
		)
		storedEntries.append(entry)
		storedCharacterCount += entry.text.count
		trimRetention()
	}

	public func entries(scope: IntegrationOutputScope? = nil, matching query: String = "") -> [IntegrationOutputEntry] {
		let query = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		return storedEntries.filter { entry in
			(scope == nil || entry.scope == scope) && (
				query.isEmpty || entry.text.lowercased().contains(query) || entry.kind.rawValue.contains(query) || entry.scope.identifier.lowercased().contains(query)
			)
		}
	}

	public func scopes() -> [IntegrationOutputScope] {
		Array(Set(storedEntries.map(\.scope))).sorted {
			($0.service.rawValue, $0.identifier) < ($1.service.rawValue, $1.identifier)
		}
	}

	public func clear(scope: IntegrationOutputScope? = nil) {
		guard let scope else {
			storedEntries.removeAll()
			storedCharacterCount = 0
			return
		}
		storedEntries.removeAll { $0.scope == scope }
		storedCharacterCount = storedEntries.reduce(0) { $0 + $1.text.count }
	}

	private func trimRetention() {
		while storedEntries.count > maximumEntries || storedCharacterCount > maximumCharacters {
			guard !storedEntries.isEmpty else {
				return
			}
			storedCharacterCount -= storedEntries.removeFirst().text.count
		}
	}
}
